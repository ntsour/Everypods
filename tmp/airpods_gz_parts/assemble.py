#!/usr/bin/env python3
from pathlib import Path
import base64, gzip
parts = sorted(Path("tmp/airpods_gz_parts").glob("part_*.txt"))
b64 = "".join(p.read_text().strip() for p in parts)
raw = gzip.decompress(base64.b64decode(b64))
out = Path("android/app/src/main/java/io/automated/ventures/everypods/services/AirPodsService.kt")
out.write_bytes(raw)
print(f"wrote {out} ({len(raw)} bytes) from {len(parts)} parts")
