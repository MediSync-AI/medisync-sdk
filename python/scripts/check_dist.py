"""Checks the built wheel and sdist before a publish: the version, the files, py.typed, no tests.

    python scripts/check_dist.py dist/
"""

from __future__ import annotations

import re
import sys
import tarfile
import zipfile
from pathlib import Path


def main(folder: str) -> int:
    dist = Path(folder)
    text = (Path(__file__).resolve().parents[1] / "pyproject.toml").read_text()
    version = re.search(r'^version = "([^"]+)"', text, re.M)
    assert version, "no version in pyproject.toml"
    v = version.group(1)
    failures = []
    wheels = list(dist.glob(f"medisync-{v}-*.whl"))
    sdists = list(dist.glob(f"medisync-{v}.tar.gz"))
    if len(wheels) != 1 or len(sdists) != 1:
        failures.append(f"expected one wheel and one sdist of {v} in {dist}, found {[p.name for p in dist.iterdir()]}")
    for wheel in wheels:
        names = zipfile.ZipFile(wheel).namelist()
        for need in ("medisync/__init__.py", "medisync/py.typed", "medisync/_generated/resources.py", "medisync/types/connect.py"):
            if need not in names:
                failures.append(f"{wheel.name}: {need} missing")
        stray = [n for n in names if not (n.startswith("medisync/") or n.startswith(f"medisync-{v}.dist-info/"))]
        if stray:
            failures.append(f"{wheel.name}: unexpected files {stray}")
        version_file = zipfile.ZipFile(wheel).read("medisync/_version.py").decode()
        if f'"{v}"' not in version_file:
            failures.append(f"{wheel.name}: _version.py is not {v}")
    for sdist in sdists:
        names = tarfile.open(sdist).getnames()
        if any("/tests/" in n for n in names):
            failures.append(f"{sdist.name}: holds tests")
    if failures:
        print("\n".join(f"- {f}" for f in failures), file=sys.stderr)
        return 1
    print(f"medisync {v}: wheel and sdist look right")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "dist"))
