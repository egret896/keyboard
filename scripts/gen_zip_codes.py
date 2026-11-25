#!/usr/bin/env python3
"""Turns Japan Post's KEN_ALL.CSV into the keyboard's postal-code asset.

    python3 gen_zip_codes.py ken_all.zip  >  zipcode.tsv     (code<TAB>address[<TAB>address…])

KEN_ALL quirks handled here:
  * Shift_JIS (cp932) text inside a zip archive.
  * A town name too long for one row continues on the following rows: an opening （ without its
    closing ） means "join the next rows until it closes".
  * Parenthesised notes — （１～１９丁目）, （次のビルを除く） — are dropped; an address
    candidate is the part people actually write.
  * 以下に掲載がない場合, "…の次に番地がくる場合" and 一円 mean "no town name", so only the
    prefecture and city are kept.
The output is sorted by code so the keyboard can binary-search it without building a map.
"""

import csv
import io
import re
import sys
import zipfile

NO_TOWN = re.compile(r"以下に掲載がない場合|の次に番地がくる場合|^一円$")
PAREN = re.compile(r"（.*?）|（.*$")


def rows(path):
    with zipfile.ZipFile(path) as z:
        name = next(n for n in z.namelist() if n.upper().endswith(".CSV"))
        text = z.read(name).decode("cp932")
    yield from csv.reader(io.StringIO(text))


def main():
    out = {}
    pending = None  # (code, pref, city, town-so-far) while a （ is open
    for r in rows(sys.argv[1]):
        if len(r) < 9:
            continue
        code, pref, city, town = r[2], r[6], r[7], r[8]
        if pending:
            pending[3] += town
            if "）" not in town:
                continue
            code, pref, city, town = pending
            pending = None
        elif "（" in town and "）" not in town:
            pending = [code, pref, city, town]
            continue
        if NO_TOWN.search(town):
            town = ""
        town = PAREN.sub("", town)
        address = pref + city + town
        lst = out.setdefault(code, [])
        if address not in lst and len(lst) < 3:
            lst.append(address)
    for code in sorted(out):
        print(code + "\t" + "\t".join(out[code]))


if __name__ == "__main__":
    main()
