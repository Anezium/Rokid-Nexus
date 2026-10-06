"""Verify the unmodified vendored Android sources; optionally compare a Git checkout."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

COMMIT = "95c4ac56cb1f4998003192e44323ed84843eaba0"
root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--upstream", type=Path, help="checkout of flyfishxu/Kadb at the pinned commit")
args = parser.parse_args()
manifest = json.loads((root / "upstream-sha256.json").read_text())
actual_paths = {"LICENSE"} | {
    str(path.relative_to(root))
    for source_set in ("commonMain", "androidMain")
    for path in (root / "src" / source_set).rglob("*") if path.is_file()
}
assert actual_paths == set(manifest), "Vendored file set differs from the manifest"
if args.upstream:
    commit = subprocess.check_output(["git", "-C", str(args.upstream), "rev-parse", "HEAD"], text=True).strip()
    assert commit == COMMIT, f"Expected {COMMIT}, found {commit}"
    expected_paths = {"LICENSE"} | {
        str(path.relative_to(args.upstream / "kadb"))
        for source_set in ("commonMain", "androidMain")
        for path in (args.upstream / "kadb" / "src" / source_set).rglob("*") if path.is_file()
    }
    assert expected_paths == actual_paths, "Upstream source file set differs"
for relative, expected in manifest.items():
    assert hashlib.sha256((root / relative).read_bytes()).hexdigest() == expected, relative
    if args.upstream:
        upstream = args.upstream / relative if relative == "LICENSE" else args.upstream / "kadb" / relative
        assert (root / relative).read_bytes() == upstream.read_bytes(), relative
print(f"Verified {len(manifest)} unmodified upstream files at {COMMIT}")
