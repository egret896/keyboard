#!/usr/bin/env python3
"""Builds the keyboard's Japanese -> English word list for 英訳 mode from JMdict.

    python3 gen_translate_dict.py JMdict_e.gz  >  translate.jaen

One line per headword:  key<TAB>english<TAB>english…   where the key is a written form (猫) or a
reading in hiragana (ねこ). Lines are sorted by their UTF-8 bytes, which lets the keyboard
binary-search the file in place (it is stored uncompressed in the APK and memory-mapped) instead of
loading a few hundred thousand entries into a table.

Glosses are trimmed to what is worth typing: parenthesised notes go, the "to " of verbs goes
(食べる -> eat), and long explanations are skipped. Common words (JMdict's *_pri markers) are
listed first under a shared key, so かみ offers "paper" and "god" before rarer senses.
"""

import gzip
import re
import sys
import xml.etree.ElementTree as ET

PAREN = re.compile(r"\s*\([^)]*\)\s*")
PER_ENTRY = 3
PER_KEY = 6
MAX_LEN = 25


def to_hiragana(s):
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in s)


def clean(gloss):
    g = PAREN.sub(" ", gloss).strip()
    if g.startswith("to "):
        g = g[3:]
    g = re.sub(r"\s+", " ", g).strip(" ,;")
    if not g or len(g) > MAX_LEN or "\t" in g:
        return None
    return g


def main():
    keys = {}  # key -> [(priority, order, [glosses])]
    order = 0
    opener = gzip.open if sys.argv[1].endswith(".gz") else open
    with opener(sys.argv[1], "rb") as f:
        for _, e in ET.iterparse(f, events=("end",)):
            if e.tag != "entry":
                continue
            glosses = []
            for sense in e.iter("sense"):
                for g in sense.iter("gloss"):
                    lang = g.get("{http://www.w3.org/XML/1998/namespace}lang", "eng")
                    c = clean(g.text or "") if lang == "eng" else None
                    if c and c not in glosses:
                        glosses.append(c)
                        break  # one gloss per sense: different senses are more useful than synonyms
                if len(glosses) >= PER_ENTRY:
                    break
            if glosses:
                common = any(True for t in ("ke_pri", "re_pri") for _ in e.iter(t))
                forms = [k.text for k in e.iter("keb") if k.text]
                forms += [to_hiragana(r.text) for r in e.iter("reb") if r.text]
                for form in dict.fromkeys(forms):
                    keys.setdefault(form, []).append((0 if common else 1, order, glosses))
                order += 1
            e.clear()

    lines = []
    for key, entries in keys.items():
        out = []
        for _, _, glosses in sorted(entries):
            for g in glosses:
                if g not in out:
                    out.append(g)
        lines.append((key + "\t" + "\t".join(out[:PER_KEY])).encode("utf-8"))
    lines.sort(key=lambda b: b.split(b"\t", 1)[0] + b"\t")
    out = sys.stdout.buffer
    for line in lines:
        out.write(line + b"\n")


if __name__ == "__main__":
    main()
