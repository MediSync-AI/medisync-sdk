"""Read-only calls against the live API: models, a coding search and the Connect catalogue.

    MEDISYNC_API_KEY=msk_test_… uv run python scripts/smoke_live.py

Without MEDISYNC_API_KEY it reads ~/.config/medisync-sdk/test-key. It prints counts and request
ids, never the key. MEDISYNC_BASE_URL points it at another server.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import List

from medisync import MediSync


def main() -> int:
    key_file = Path.home() / ".config/medisync-sdk/test-key"
    api_key = os.environ.get("MEDISYNC_API_KEY") or (key_file.read_text().strip() if key_file.exists() else "")
    if not api_key:
        print(f"No key: set MEDISYNC_API_KEY or write it to {key_file}.", file=sys.stderr)
        return 2
    ids: List[str] = []
    with MediSync(api_key=api_key, on_response=lambda r: ids.append(f"{r.operation} {r.status} {r.request_id or '-'}")) as client:
        models = client.models.list()
        print(f"models.list: {len(models.data)} models")
        search = client.coding.search(q="Hypertonie", limit=3)
        print(f"coding.search: {len(search.model_dump_json())} bytes")
        systems = client.connect.systems.list(limit=5)
        print(f"connect.systems.list: {len(systems.data)} systems on the first page")
    print("requests:\n  " + "\n  ".join(ids))
    return 0


if __name__ == "__main__":
    sys.exit(main())
