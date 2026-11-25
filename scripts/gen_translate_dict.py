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

import re
import sys

import jmdict_source

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
    for order, e in enumerate(jmdict_source.entries(sys.argv[1])):
        glosses = []
        for sense in e["senses"]:
            # one gloss per sense: different senses are more useful than synonyms
            for g in sense:
                c = clean(g)
                if c and c not in glosses:
                    glosses.append(c)
                    break
            if len(glosses) >= PER_ENTRY:
                break
        if not glosses:
            continue
        forms = e["kanji"] + [to_hiragana(r) for r in e["kana"]]
        for form in dict.fromkeys(forms):
            keys.setdefault(form, []).append((0 if e["common"] else 1, order, glosses))

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
