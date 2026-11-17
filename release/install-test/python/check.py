"""Install test of the PyPI package: run in a clean virtual environment. run.sh sets MOCK_URL and
EXPECTED_VERSION."""

import asyncio
import json
import os
import sys
import urllib.request

import medisync
from medisync import AsyncMediSync, MediSync, NotFoundError
from medisync.types.connect import System

MOCK = os.environ["MOCK_URL"]
EXPECTED = os.environ["EXPECTED_VERSION"]
RUNTIME = f"python {sys.version.split()[0]}"


def control(path: str, body: object = None) -> object:
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(f"{MOCK}/__mock/{path}", data=data, headers={"content-type": "application/json"})
    with urllib.request.urlopen(req) as res:
        return json.loads(res.read())


def check(ok: bool, what: str) -> None:
    if not ok:
        raise SystemExit(f"{RUNTIME}: {what}")


check(medisync.__version__ == EXPECTED, f"__version__ is {medisync.__version__}, expected {EXPECTED}")
check(os.path.join("site-packages", "medisync") in medisync.__file__, f"medisync comes from {medisync.__file__}, not the installed package")

control("reset", {"responses": []})
client = MediSync(api_key="msk_test_install", base_url=MOCK)
models = client.models.list()
check(isinstance(models.data, list), "models.list gave no data")
systems = client.connect.systems.list(limit=2)
check(all(isinstance(s, System) for s in systems.data), "connect.systems.list did not give System models")


async def run_async() -> None:
    async with AsyncMediSync(api_key="msk_test_install", base_url=MOCK) as a:
        await a.coding.search(q="Hypertonie")


asyncio.run(run_async())
log = control("requests")
check(isinstance(log, list) and len(log) == 3, f"the mock saw {len(log)} requests, expected 3")
for r in log:
    check(not r["problems"], f"{r['method']} {r['path']}: {'; '.join(r['problems'])}")
    check(r["headers"].get("user-agent") == f"medisync-python/{EXPECTED}", f"User-Agent {r['headers'].get('user-agent')}")

error = {"type": "not_found_error", "code": "not_found", "message": "No such site.", "request_id": "req_install", "param": None}
control("reset", {"responses": [{"status": 404, "body": {"type": "error", "error": error, "request_id": "req_install"}}]})
try:
    client.connect.sites.get("ste_missing")
    check(False, "a 404 raised nothing")
except NotFoundError as e:
    check(e.code == "not_found" and e.request_id == "req_install", f"error code {e.code}, request ID {e.request_id}")

print(f"{RUNTIME}: medisync {medisync.__version__} ok")
