#!/usr/bin/env python3
"""Pulls katakana loanwords and their English glosses out of JMdict.

    python3 extract_jmdict_english.py JMdict_e.gz|jmdict-eng.json.zip  >  reading<TAB>gloss<TAB>名詞 lines

Only entries whose reading is written entirely in katakana are taken, and the reading is turned
into hiragana (what the keyboard composes). Every English gloss is emitted; most are translations
rather than spellings, so the output is meant to go through filter_katakana_english.py, which keeps
only the glosses that sound like the reading (デザイン -> design, but not デザイン -> plan).

Also accepts plain `reading<TAB>word<TAB>pos` lines on stdin with --tsv, so the older dictionary
goes through the same katakana/hiragana handling instead of a locale-dependent awk range.
"""

import re
import sys

import jmdict_source

KATAKANA = re.compile(r"^[ァ-ヶー]+$")
HIRAGANA = re.compile(r"^[ぁ-ゖー]+$")


def to_hiragana(s):
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in s)


def from_jmdict(path):
    for e in jmdict_source.entries(path):
        readings = [r for r in e["kana"] if KATAKANA.match(r)]
        if not readings:
            continue
        glosses = list(dict.fromkeys(g for sense in e["senses"] for g in sense))
        for r in readings:
            for g in glosses:
                print(f"{to_hiragana(r)}\t{g}\t名詞")


def from_tsv():
    for line in sys.stdin:
        cols = line.rstrip("\n").split("\t")
        if len(cols) < 3 or not cols[1]:
            continue
        reading = to_hiragana(cols[0])
        if HIRAGANA.match(reading):
            print(f"{reading}\t{cols[1]}\t{cols[2]}")


if __name__ == "__main__":
    if sys.argv[1:] == ["--tsv"]:
        from_tsv()
    else:
        from_jmdict(sys.argv[1])
