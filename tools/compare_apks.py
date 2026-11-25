#!/usr/bin/env python3
"""Offline APK comparison. Differences are informational unless --strict is used.

Missing inputs, corrupt ZIPs and duplicate entries always fail, not masquerade as
reproducibility results. --summary writes the complete report for CI artifacts.
"""
import argparse
import hashlib
import sys
import zipfile
from pathlib import Path


def entries(apk: Path) -> dict:
    out = {}
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            if info.filename in out:
                raise ValueError(f"{apk.name}: duplicate ZIP entry {info.filename}")
            out[info.filename] = (
                hashlib.sha256(archive.read(info)).hexdigest(),
                (info.date_time, info.compress_type, info.compress_size,
                 info.external_attr, info.internal_attr, info.create_system,
                 info.create_version, info.extract_version, info.flag_bits,
                 info.extra.hex(), info.comment.hex()),
            )
    if not out:
        raise ValueError(f"{apk.name}: empty ZIP")
    return out


def compare(a: Path, b: Path) -> tuple[str, bool]:
    for directory in (a, b):
        if not directory.is_dir():
            raise ValueError(f"missing APK directory: {directory}")
    names_a = {p.name for p in a.glob("*.apk")}
    names_b = {p.name for p in b.glob("*.apk")}
    if not names_a or names_a != names_b:
        raise ValueError(f"APK sets missing or unequal: A={sorted(names_a)}, B={sorted(names_b)}")
    lines = ["# Reproducibility check", ""]
    identical = True
    for name in sorted(names_a):
        pa, pb = a / name, b / name
        ea, eb = entries(pa), entries(pb)
        ha = hashlib.sha256(pa.read_bytes()).hexdigest()
        hb = hashlib.sha256(pb.read_bytes()).hexdigest()
        whole = ha == hb
        identical &= whole
        lines += [f"## `{name}`", f"- Whole file identical: **{'yes' if whole else 'no'}**",
                  f"- A SHA-256: `{ha}`", f"- B SHA-256: `{hb}`",
                  f"- Entry order identical: **{'yes' if list(ea) == list(eb) else 'no'}**"]
        for key in sorted(ea.keys() | eb.keys()):
            if key not in ea or key not in eb:
                lines.append(f"- `{key}`: only in {'A' if key in ea else 'B'}")
            elif ea[key][0] != eb[key][0]:
                lines.append(f"- `{key}`: content differs; A `{ea[key][0]}`, B `{eb[key][0]}`")
            elif ea[key][1] != eb[key][1]:
                lines.append(f"- `{key}`: ZIP metadata/compression differs; A `{ea[key][1]}`, B `{eb[key][1]}`")
        if not whole and ea == eb:
            lines.append("- Equal entry payloads/metadata; container bytes differ (entry order, local headers, padding, ZIP comment or signing block).")
        lines.append("")
    lines += [f"Result: **{'MATCH' if identical else 'DIFFERENT'}**", ""]
    return "\n".join(lines), identical


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("a", type=Path)
    parser.add_argument("b", type=Path)
    parser.add_argument("--summary", type=Path)
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()
    try:
        report, identical = compare(args.a, args.b)
        status = int(args.strict and not identical)
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as error:
        report = f"# Reproducibility check\n\nResult: **ERROR**\n\n{error}\n"
        status = 1
    print(report)
    if args.summary:
        args.summary.write_text(report, encoding="utf-8")
    return status


if __name__ == "__main__":
    sys.exit(main())
