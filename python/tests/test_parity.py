"""The clients have exactly one public method per operationId of both specs, plus the helpers."""

from __future__ import annotations

import inspect
import json
import re
from typing import Any, List, Set

from medisync import HELPERS, OPERATIONS, AsyncMediSync, MediSync, __version__
from medisync._core import AsyncAPIResource, SyncAPIResource

from .conftest import ROOT

NAMES = json.loads((ROOT / "conformance/operations.json").read_text())


def spec_operation_ids(name: str) -> List[str]:
    spec = json.loads((ROOT / "specs" / name).read_text())
    return [item[m]["operationId"] for item in spec["paths"].values() for m in ("get", "post", "put", "patch", "delete") if m in item]


def public_methods(client: Any) -> List[str]:
    out: Set[str] = set()

    def walk(node: Any, prefix: str) -> None:
        for key, value in vars(node).items():
            if key.startswith("_") or not isinstance(value, (SyncAPIResource, AsyncAPIResource)):
                continue
            path = f"{prefix}.{key}" if prefix else key
            for name, _member in inspect.getmembers(type(value), predicate=inspect.isfunction):
                if not name.startswith("_"):
                    out.add(f"{path}.{name}")
            walk(value, path)

    walk(client, "")
    return sorted(out)


def test_one_method_per_operation_and_the_helpers() -> None:
    ids = sorted(
        [f"platform:{i}" for i in spec_operation_ids("openapi.json")] + [f"connect:{i}" for i in spec_operation_ids("connect-openapi.json")]
    )
    assert sorted(f"{o['api']}:{o['operationId']}" for o in NAMES["operations"]) == ids
    assert len(spec_operation_ids("connect-openapi.json")) == 94
    expected = sorted([o["python"] for o in NAMES["operations"]] + [h["python"] for h in NAMES["helpers"]])
    assert public_methods(MediSync(api_key="msk_test_parity")) == expected
    assert public_methods(AsyncMediSync(api_key="msk_test_parity")) == expected
    assert sorted(OPERATIONS) == sorted(o["typescript"] for o in NAMES["operations"])
    assert sorted(HELPERS) == sorted(h["typescript"] for h in NAMES["helpers"])


def test_version_matches_pyproject() -> None:
    text = (ROOT / "python/pyproject.toml").read_text()
    match = re.search(r'^version = "([^"]+)"', text, re.M)
    assert match and match.group(1) == __version__
