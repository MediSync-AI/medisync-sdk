from __future__ import annotations

import json
import os
import subprocess
import urllib.request
from pathlib import Path
from typing import Any, Dict, Iterator, List

import pytest

ROOT = Path(__file__).resolve().parents[2]


class Mock:
    """The mock server of conformance/mock: started with bun, or MEDISYNC_MOCK_URL."""

    def __init__(self, url: str) -> None:
        self.url = url

    def _call(self, path: str, body: Any = None) -> Any:
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(
            self.url + path, data=data, method="POST" if data else "GET", headers={"content-type": "application/json"}
        )
        with urllib.request.urlopen(request) as response:  # noqa: S310 - local mock
            return json.loads(response.read())

    def reset(self, responses: List[Any] | None = None, ws: Dict[str, Any] | None = None) -> None:
        self._call("/__mock/reset", {"responses": responses or [], "ws": ws or {}})

    def requests(self) -> List[Dict[str, Any]]:
        return list(self._call("/__mock/requests"))

    def ws(self) -> List[Dict[str, Any]]:
        return list(self._call("/__mock/ws"))


@pytest.fixture(scope="session")
def mock() -> Iterator[Mock]:
    url = os.environ.get("MEDISYNC_MOCK_URL")
    if url:
        yield Mock(url)
        return
    bun = os.environ.get("BUN", "bun")
    process = subprocess.Popen([bun, str(ROOT / "conformance/mock/server.ts"), "--port", "0"], stdout=subprocess.PIPE, text=True)
    try:
        assert process.stdout is not None
        line = process.stdout.readline()
        if not line.startswith("MOCK_URL="):
            raise RuntimeError(f"The mock server did not start: {line!r}. Is bun installed?")
        yield Mock(line.strip().split("=", 1)[1])
    finally:
        process.terminate()
        process.wait(10)
