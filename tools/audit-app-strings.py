#!/usr/bin/env python3
"""Audit app resource translations offline; print actionable resource IDs and counts.

Resource integrity failures exit 1. Source literals are conservative review
candidates, not proof of untranslated text; --strict-ui also fails on candidates.
"""
import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FORMAT = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(<]*)(?:\d+)?(?:\.\d+)?([tT]?[a-zA-Z%])")
# These are deliberately Latin: language self-name, technical labels, formatting,
# and the GSM-7 readable SMS text required by handover section 5.6.
UNCHANGED = {
    "lang_en", "lang_bn", "relays_add_hint", "compose_counter",
    "task_pay_bkash", "task_pay_nagad", "task_pay_received",
    "sos_incoming_detail", "khata_reminder_sms", "sos_sms_text", "radar_peer",
}


def placeholders(text):
    args = []
    implicit = 0
    previous = None
    for match in FORMAT.finditer(text):
        index, flags, kind = match.groups()
        if kind in ("%", "n"):
            continue
        if "<" in flags:
            index = previous
        elif index:
            index = int(index)
        else:
            implicit += 1
            index = implicit
        args.append((index, kind))
        previous = index
    return sorted(args, key=str)


def resources(directory):
    found = {}
    for path in sorted(directory.glob("*.xml")):
        for element in ET.parse(path).getroot():
            if element.tag not in ("string", "plurals", "string-array"):
                continue
            name = element.attrib["name"]
            if name in found:
                raise ValueError(f"duplicate resource {name}: {found[name]['file']}, {path}")
            if element.tag == "string":
                values = {"text": "".join(element.itertext()).strip()}
            else:
                values = {item.get("quantity", str(i)): "".join(item.itertext()).strip()
                          for i, item in enumerate(element)}
            found[name] = {"type": element.tag, "values": values,
                           "translatable": element.get("translatable") != "false",
                           "formatted": element.get("formatted") != "false", "file": str(path)}
    return found


def audit(root):
    res = root / "app/src/main/res"
    base, bangla = resources(res / "values"), resources(res / "values-bn")
    if not base or not bangla:
        raise ValueError("both values and values-bn must contain string resources")
    required = {key for key, value in base.items() if value["translatable"]}
    missing = sorted(required - bangla.keys())
    extra = sorted(bangla.keys() - base.keys())
    errors = [f"missing Bangla: {key} ({base[key]['file']})" for key in missing]
    errors += [f"unknown Bangla resource: {key}" for key in extra]
    unchanged = []
    for key in sorted(required & bangla.keys()):
        source, target = base[key], bangla[key]
        if source["type"] != target["type"]:
            errors.append(f"{key}: resource type differs")
            continue
        if source["type"] == "plurals":
            if "other" not in target["values"]:
                errors.append(f"{key}: Bangla plurals need 'other'")
            pairs = [(quantity, source["values"].get(quantity, source["values"].get("other", "")), text)
                     for quantity, text in target["values"].items()]
        else:
            if source["values"].keys() != target["values"].keys():
                errors.append(f"{key}: array length differs")
            pairs = [(part, text, target["values"].get(part, "")) for part, text in source["values"].items()]
        for part, english, translated in pairs:
            if not translated:
                errors.append(f"{key}/{part}: blank Bangla text")
            if source["formatted"] and placeholders(english) != placeholders(translated):
                errors.append(f"{key}/{part}: format arguments differ: {placeholders(english)} vs {placeholders(translated)}")
        if source["values"] == target["values"]:
            unchanged.append(key)
            if key not in UNCHANGED:
                errors.append(f"{key}: unchanged English text needs translation or an explained exception")

    # Match direct UI sinks only, excluding comments and string bodies from the
    # search. This is not a Kotlin parser and cannot trace data-flow into the UI.
    literal = re.compile(r'"(?:\\.|[^"\\\n])*"')
    tokens = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\\n])*"')
    sink = re.compile(r'(?:\b(?:Text|HButton|Chip|HChip)\s*\(\s*(?:text\s*=\s*)?|\bcontentDescription\s*=\s*|\bcopyToClipboard\s*\(\s*)$')
    candidates = []
    for path in sorted((root / "app/src/main/kotlin").rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        masked = list(text)
        for token in tokens.finditer(text):
            value = token.group()
            if literal.fullmatch(value):
                prefix = "".join(masked[max(0, token.start() - 150):token.start()])
                if sink.search(prefix) and re.search(r"[A-Za-z]{2,}", re.sub(r"\$\w+|\$\{[^}]*\}", "", value)):
                    if value != '"harkara"':  # invariant wordmark, not prose
                        candidates.append({"file": path.relative_to(root).as_posix(),
                                           "line": text.count("\n", 0, token.start()) + 1, "literal": value})
            masked[token.start():token.end()] = ["\n" if c == "\n" else " " for c in value]
    return {"required": len(required), "translated": len(required & bangla.keys()),
            "missing": missing, "extra": extra, "unchanged_exceptions": unchanged,
            "errors": errors, "ui_literal_candidates": candidates}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--strict-ui", action="store_true")
    args = parser.parse_args()
    try:
        result = audit(args.root)
    except (ValueError, OSError, ET.ParseError) as error:
        print(str(error), file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return int(bool(result["errors"] or (args.strict_ui and result["ui_literal_candidates"])))


if __name__ == "__main__":
    sys.exit(main())
