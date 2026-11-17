"""MediSync Connect helpers: the release of a reviewed plan, app auth, and following transfers.

The Connect operations are on the client: `client.connect.transfers.create(...)`.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Mapping, Optional, Union, cast

from .._app_auth import AppAuthError, AppCredentials, AppTokenSource, AsyncAppTokenSource, client_assertion
from .._generated.connect_types import PatientRef, Plan, ReleaseInput
from .._helpers.capabilities import AsyncCapabilities, Capabilities
from .._helpers.follow import FINAL_TRANSFER_STATUSES, TransferWaitTimeoutError, is_final_transfer_status

__all__ = [
    "build_release",
    "AppCredentials",
    "AppTokenSource",
    "AsyncAppTokenSource",
    "AppAuthError",
    "client_assertion",
    "Capabilities",
    "AsyncCapabilities",
    "FINAL_TRANSFER_STATUSES",
    "TransferWaitTimeoutError",
    "is_final_transfer_status",
]


def _timestamp(value: Union[datetime, str]) -> str:
    """RFC 3339 with seconds and without fractions, in UTC, as the API takes it."""
    moment = datetime.fromisoformat(value.replace("Z", "+00:00")) if isinstance(value, str) else value
    if moment.tzinfo is None:
        raise ValueError("released_at needs a time zone.")
    return moment.astimezone(timezone.utc).replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ")


def build_release(
    plan: Union[Plan, Mapping[str, Any]],
    *,
    member_id: str,
    role: str,
    practitioner_link: Optional[str],
    patient_confirmed: Union[PatientRef, Mapping[str, Any]],
    display_name: Optional[str] = None,
    released_at: Union[datetime, str, None] = None,
) -> ReleaseInput:
    """The `release` of `transfers.create` for a plan a person reviewed.

    Takes `document_hash` and `plan_hash` from the plan, so the release names exactly what the
    person saw. Call it only after the person released the preview in your app. The SDK does not
    check that; Connect and the hub check the member, role, practitioner link, patient and hashes.
    """
    data = plan.to_dict() if isinstance(plan, Plan) else dict(plan)
    patient = patient_confirmed.to_dict() if isinstance(patient_confirmed, PatientRef) else dict(patient_confirmed)
    released_by: dict[str, Any] = {"member_id": member_id}
    if display_name:
        released_by["display_name"] = display_name
    release: dict[str, Any] = {
        "released_by": released_by,
        "role": role,
        "practitioner_link": practitioner_link,
        "patient_confirmed": patient,
        "document_hash": cast(str, data["document_hash"]),
        "plan_hash": data.get("plan_hash"),
        "released_at": _timestamp(released_at or datetime.now(timezone.utc)),
    }
    return ReleaseInput.model_validate(release)
