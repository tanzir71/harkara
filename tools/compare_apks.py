#!/usr/bin/env python3
"""Compare two builds of the same APKs entry by entry (a zip-entry hash diff, handover M12).

Usage: python3 tools/compare_apks.py DIR_A DIR_B [--summary FILE]
Prints a Markdown report; exit code is always 0 (this never gates a release).
"""
import hashlib
import sys
import zipfile
from pathlib import Path


def entries(apk: Path) -> dict:
    out = {}
    with zipfile.ZipFile(apk) as z:
        for info in z.infolist():
            out[info.filename] = hashlib.sha256(z.read(info.filename)).hexdigest()
    return out


def main() -> None:
    a, b = Path(sys.argv[1]), Path(sys.argv[2])
    summary = Path(sys.argv[sys.argv.index("--summary") + 1]) if "--summary" in sys.argv else None
    lines = ["# Reproducibility check", ""]
    names = sorted({p.name for p in a.glob("*.apk")} | {p.name for p in b.glob("*.apk")})
    if not names:
        lines.append("No APKs found.")
    for name in names:
        pa, pb = a / name, b / name
        if not (pa.exists() and pb.exists()):
            lines.append(f"- `{name}`: only in one build")
            continue
        whole = hashlib.sha256(pa.read_bytes()).hexdigest() == hashlib.sha256(pb.read_bytes()).hexdigest()
        ea, eb = entries(pa), entries(pb)
        differing = sorted(k for k in set(ea) | set(eb) if ea.get(k) != eb.get(k))
        lines.append(f"## `{name}`")
        lines.append(f"- whole file identical: **{'yes' if whole else 'no'}**; entries: {len(ea)}; differing entries: **{len(differing)}**")
        for k in differing[:50]:
            lines.append(f"  - `{k}`")
        lines.append("")
    report = "\n".join(lines) + "\n"
    print(report)
    if summary:
        summary.write_text(report, encoding="utf-8")


if __name__ == "__main__":
    main()
