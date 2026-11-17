from __future__ import annotations

from ._generated.resources import AsyncMediSyncResources, MediSyncResources

__all__ = ["MediSync", "AsyncMediSync"]


class MediSync(MediSyncResources):
    """The MediSync API client: speech, notes and letters, ICD-10-GM coding, decisions, account
    reads and MediSync Connect. One method per API operation, named after the operation:

        client = MediSync()  # reads MEDISYNC_API_KEY
        note = client.notes.create(...)
        transfer = client.connect.transfers.create(plan_id=plan.id, release=release)

    Arguments: `api_key` (default: MEDISYNC_API_KEY), `connect_app` (MediSync Connect app
    credentials), `base_url` (default: MEDISYNC_BASE_URL, else https://api.medisync.me),
    `version` (the MediSync-Version header), `timeout` (seconds per attempt, default 120),
    `max_retries` (default 2), `default_headers`, `http_client` and `on_response`.

    Everything the API generates is a draft that a clinician reviews and releases.
    """


class AsyncMediSync(AsyncMediSyncResources):
    """The asyncio form of `MediSync`. Takes the same arguments."""
