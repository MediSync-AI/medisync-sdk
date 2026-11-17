"""App mode of MediSync Connect: the SDK signs the client assertion and keeps the access token."""

from __future__ import annotations

import base64
import json
from typing import Any, Dict, List
from urllib.parse import parse_qs

import httpx
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, ed25519, rsa
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

from medisync import AppAuthError, AppCredentials, AppTokenSource, AuthenticationError, MediSync
from medisync.connect import client_assertion

APP = "app_6Tn3Cw9KhZaB"
EC = ec.generate_private_key(ec.SECP256R1())
ED = ed25519.Ed25519PrivateKey.generate()


def pem(key: Any) -> str:
    return key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode()


def b64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def test_es256_assertion_verifies() -> None:
    jwt = client_assertion(
        AppCredentials(client_id=APP, key_id="k1", private_key=pem(EC)), "https://connect.medisync.me/oauth/token", now=1_800_000_000
    )
    h, p, s = jwt.split(".")
    assert json.loads(b64(h)) == {"alg": "ES256", "kid": "k1", "typ": "JWT"}
    payload = json.loads(b64(p))
    assert payload["iss"] == payload["sub"] == APP and payload["exp"] == 1_800_000_060
    raw = b64(s)
    assert len(raw) == 64
    der = encode_dss_signature(int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big"))
    EC.public_key().verify(der, f"{h}.{p}".encode(), ec.ECDSA(hashes.SHA256()))


def test_eddsa_and_refused_keys() -> None:
    jwt = client_assertion(AppCredentials(client_id=APP, key_id="k2", private_key=ED), "aud")
    h, p, s = jwt.split(".")
    assert json.loads(b64(h))["alg"] == "EdDSA"
    ED.public_key().verify(b64(s), f"{h}.{p}".encode())
    with pytest.raises(TypeError):
        client_assertion(AppCredentials(client_id=APP, key_id="k3", private_key=pem(rsa.generate_private_key(65537, 2048))), "aud")
    with pytest.raises(TypeError):
        client_assertion(AppCredentials(client_id=APP, key_id="k3", private_key="not a pem"), "aud")


def token_server(answers: List[Any]) -> tuple[httpx.Client, List[Dict[str, Any]]]:
    seen: List[Dict[str, Any]] = []
    n = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal n
        n += 1
        form = parse_qs(request.content.decode()) if "/oauth/" in str(request.url) else None
        seen.append({"url": str(request.url), "form": form, "auth": request.headers.get("authorization")})
        nxt = answers.pop(0)
        return nxt(n) if callable(nxt) else nxt

    return httpx.Client(transport=httpx.MockTransport(handler)), seen


def tok(n: int) -> httpx.Response:
    return httpx.Response(200, json={"access_token": f"tok-{n}", "token_type": "Bearer", "expires_in": 900})


def test_token_source_fields_and_errors() -> None:
    http, seen = token_server([tok, httpx.Response(401, json={"error": "invalid_client", "error_description": "No."})])
    source = AppTokenSource(
        AppCredentials(client_id=APP, key_id="k1", private_key=pem(EC), installation_id="ins_3Rv8Qa2MxK", verbs=["patient.context"]),
        http_client=http,
    )
    assert source.get() == "tok-1" and source.get() == "tok-1"
    form = seen[0]["form"]
    assert form["grant_type"] == ["client_credentials"]
    assert json.loads(form["authorization_details"][0]) == [
        {"type": "medisync_connect", "installation_id": "ins_3Rv8Qa2MxK", "verbs": [{"verb": "patient.context", "roles": []}]}
    ]
    source.clear()
    with pytest.raises(AppAuthError) as err:
        source.get()
    assert err.value.status == 401 and err.value.error == "invalid_client"
    with pytest.raises(TypeError):
        AppTokenSource(AppCredentials(client_id="proj_x", key_id="k", private_key=""))


def test_client_in_app_mode_refreshes_after_invalid_token() -> None:
    http, seen = token_server(
        [
            tok,
            httpx.Response(200, json={"data": [], "next_cursor": None}),
            httpx.Response(
                401, json={"error": {"type": "authentication_error", "code": "invalid_token", "message": "Revoked.", "request_id": "r"}}
            ),
            tok,
            httpx.Response(200, json={"data": [], "next_cursor": None}),
            httpx.Response(
                401, json={"error": {"type": "authentication_error", "code": "invalid_token", "message": "Revoked.", "request_id": "r"}}
            ),
            tok,
            httpx.Response(
                401, json={"error": {"type": "authentication_error", "code": "invalid_token", "message": "Revoked.", "request_id": "r"}}
            ),
        ]
    )
    client = MediSync(
        connect_app=AppCredentials(client_id=APP, key_id="k1", private_key=pem(EC)), base_url="https://api.example.test", http_client=http
    )
    client.connect.installations.list()
    assert seen[1]["url"] == "https://api.example.test/v1/connect/installations" and seen[1]["auth"] == "Bearer tok-1"
    client.connect.sites.list()
    assert seen[3]["url"] == "https://connect.medisync.me/oauth/token" and seen[4]["auth"] == "Bearer tok-4"
    with pytest.raises(AuthenticationError):
        client.connect.sites.list()
    with pytest.raises(Exception, match="API key"):
        client.models.list()
