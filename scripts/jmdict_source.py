"""Reads JMdict in either of the forms the build can get it in.

  * JMdict_e.gz — EDRDG's own XML (ftp.edrdg.org), the original.
  * jmdict-eng-*.json(.zip) — scriptin/jmdict-simplified, the same data as JSON, published as
    GitHub release assets. GitHub is far more reachable from CI than EDRDG's server, which has
    been seen to time out for minutes at a time.

Both come out as the same plain records:
    {"kanji": [...], "kana": [...], "common": bool, "senses": [[english gloss, ...], ...]}
"""

import gzip
import json
import zipfile
import xml.etree.ElementTree as ET

XML_LANG = "{http://www.w3.org/XML/1998/namespace}lang"


def entries(path):
    if path.endswith(".zip") or path.endswith(".json"):
        yield from _from_json(path)
    else:
        yield from _from_xml(path)


def _from_xml(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rb") as f:
        for _, e in ET.iterparse(f, events=("end",)):
            if e.tag != "entry":
                continue
            yield {
                "kanji": [k.text for k in e.iter("keb") if k.text],
                "kana": [r.text for r in e.iter("reb") if r.text],
                "common": any(True for t in ("ke_pri", "re_pri") for _ in e.iter(t)),
                "senses": [
                    [g.text for g in s.iter("gloss") if g.text and g.get(XML_LANG, "eng") == "eng"]
                    for s in e.iter("sense")
                ],
            }
            e.clear()


def _from_json(path):
    if path.endswith(".zip"):
        with zipfile.ZipFile(path) as z:
            name = next(n for n in z.namelist() if n.endswith(".json"))
            data = json.loads(z.read(name))
    else:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    for w in data["words"]:
        kanji = w.get("kanji", [])
        kana = w.get("kana", [])
        yield {
            "kanji": [k["text"] for k in kanji],
            "kana": [k["text"] for k in kana],
            "common": any(k.get("common") for k in kanji + kana),
            "senses": [
                [g["text"] for g in s.get("gloss", []) if g.get("lang", "eng") == "eng" and g.get("text")]
                for s in w.get("sense", [])
            ],
        }
