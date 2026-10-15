#!/usr/bin/env python3
"""Check vendored bytes offline, including explicitly recorded local clipboard patches."""
import hashlib
import json
from pathlib import Path

base = Path(__file__).resolve().parent.parent
manifest = json.loads((base / "upstream/manifest.json").read_text())
patches = {p["local"]: p for p in json.loads((base / "upstream/local-patches.json").read_text())["files"]}
for entry in manifest["files"]:
    patch = patches.get(entry["local"])
    if patch:
        assert patch["upstreamSha256"] == entry["sha256"], entry["local"]
    expected = patch["patchedSha256"] if patch else entry["sha256"]
    assert hashlib.sha256((base / entry["local"]).read_bytes()).hexdigest() == expected, entry["local"]
print(f"Verified {len(manifest['files'])} upstream files ({len(patches)} documented patches).")
