"""App mode of MediSync Connect: a partner app authenticates with its own key pair.

The SDK signs a client assertion (RFC 7523, `private_key_jwt`) with the app's private key, trades
it at Connect's token endpoint for a 15-minute access token, and gets a new one before it expires.
The private key never leaves the process; MediSync holds only the public key. Signing needs the
`cryptography` package: `pip install "medisync[app]"`.
"""

from __future__ import annotations

import asyncio
import base64
import json
import re
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, Optional, Sequence, Tuple, cast

import httpx
from typing_extensions import Literal

from ._exceptions import APIConnectionError, MediSyncError

__all__ = ["AppCredentials", "AppAuthError", "AppTokenSource", "AsyncAppTokenSource", "client_assertion", "DEFAULT_ISSUER"]

DEFAULT_ISSUER = "https://connect.medisync.me"
ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
REFRESH_MARGIN = 60.0
"""A new token this long before the old one expires, in seconds."""
ASSERTION_SECONDS = 60
"""An assertion lives one minute; Connect allows five."""


@dataclass(frozen=True)
class AppCredentials:
    """The credentials of a MediSync Connect app."""

    client_id: str
    """The app id from the console, `app_…`. It is the OAuth client_id."""
    key_id: str
    """The key id (`kid`) the public key has in the console."""
    private_key: Any
    """A PKCS8 PEM (`-----BEGIN PRIVATE KEY-----`) or a `cryptography` private key. EC P-256 signs with ES256, Ed25519 with EdDSA."""
    installation_id: Optional[str] = None
    """Act at one site: the installation id (`ins_…`). Leave it out for an app token (sites.create, lists)."""
    verbs: Sequence[str] = field(default_factory=lambda: ())
    """Fewer verbs than the installation holds, for this client's token."""
    scopes: Sequence[str] = field(default_factory=lambda: ())
    """Fewer scopes than the app holds."""
    environment: Optional[Literal["live", "test"]] = None
    """An app token of an app with a live and a test project names the environment."""
    issuer: str = DEFAULT_ISSUER
    """The authorization server."""


class AppAuthError(MediSyncError):
    """The token endpoint refused: `error` is the OAuth error code (RFC 6749 5.2)."""

    def __init__(self, status: int, error: str, description: str) -> None:
        super().__init__(f"{error}: {description}")
        self.status = status
        self.error = error


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _signer(private_key: Any) -> Tuple[str, Any]:
    try:
        from cryptography.hazmat.primitives.asymmetric import ec, ed25519
        from cryptography.hazmat.primitives.serialization import load_pem_private_key
    except ImportError as e:  # pragma: no cover
        raise MediSyncError('App auth needs the cryptography package: pip install "medisync[app]".') from e
    key = private_key
    if isinstance(private_key, (str, bytes)):
        text = private_key.decode() if isinstance(private_key, bytes) else private_key
        text = text.replace("\\n", "\n")
        if "-----BEGIN PRIVATE KEY-----" not in text:
            raise TypeError("private_key must be a PKCS8 PEM (-----BEGIN PRIVATE KEY-----).")
        key = load_pem_private_key(text.encode(), password=None)
    if isinstance(key, ec.EllipticCurvePrivateKey) and isinstance(key.curve, ec.SECP256R1):
        return "ES256", key
    if isinstance(key, ed25519.Ed25519PrivateKey):
        return "EdDSA", key
    raise TypeError("private_key must be an EC P-256 or an Ed25519 private key.")


def client_assertion(credentials: AppCredentials, audience: str, now: Optional[int] = None) -> str:
    """A client assertion (RFC 7523 2.2): iss = sub = client_id, aud = the endpoint, one-time jti."""
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

    alg, key = _signer(credentials.private_key)
    issued = int(time.time()) if now is None else now
    header = _b64url(json.dumps({"alg": alg, "kid": credentials.key_id, "typ": "JWT"}, separators=(",", ":")).encode())
    payload = _b64url(
        json.dumps(
            {
                "iss": credentials.client_id,
                "sub": credentials.client_id,
                "aud": audience,
                "iat": issued,
                "exp": issued + ASSERTION_SECONDS,
                "jti": str(uuid.uuid4()),
            },
            separators=(",", ":"),
        ).encode()
    )
    signing_input = f"{header}.{payload}".encode()
    if alg == "ES256":
        der = key.sign(signing_input, ec.ECDSA(hashes.SHA256()))
        r, s = decode_dss_signature(der)
        signature = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    else:
        signature = key.sign(signing_input)
    return f"{header}.{payload}.{_b64url(signature)}"


class _TokenBase:
    def __init__(self, credentials: AppCredentials) -> None:
        if not re.fullmatch(r"app_[A-Za-z0-9]{8,32}", credentials.client_id):
            raise TypeError("client_id is the app id from the console, app_….")
        self.credentials = credentials
        self.issuer = credentials.issuer.rstrip("/")
        self._token: Optional[Tuple[str, float, Dict[str, Any]]] = None

    @property
    def answer(self) -> Optional[Dict[str, Any]]:
        """The last token answer: installation id and authorization details. None before the first call."""
        return self._token[2] if self._token else None

    def clear(self) -> None:
        """Drops the token, so the next call fetches one (after a 401 `invalid_token`)."""
        self._token = None

    def _fresh(self) -> Optional[str]:
        if self._token and self._token[1] - REFRESH_MARGIN > time.time():
            return self._token[0]
        return None

    def _fields(self) -> Dict[str, str]:
        c = self.credentials
        fields: Dict[str, str] = {"grant_type": "client_credentials"}
        if c.scopes:
            fields["scope"] = " ".join(c.scopes)
        if c.installation_id:
            detail: Dict[str, Any] = {"type": "medisync_connect", "installation_id": c.installation_id}
            if c.verbs:
                detail["verbs"] = [{"verb": v, "roles": []} for v in c.verbs]
            fields["authorization_details"] = json.dumps([detail])
        elif c.environment:
            fields["environment"] = c.environment
        return fields

    def _form(self, path: str, fields: Dict[str, str]) -> Tuple[str, Dict[str, str]]:
        url = f"{self.issuer}{path}"
        return url, {**fields, "client_assertion_type": ASSERTION_TYPE, "client_assertion": client_assertion(self.credentials, url)}

    @staticmethod
    def _read(response: httpx.Response) -> Dict[str, Any]:
        text = response.text
        try:
            body: Any = json.loads(text) if text else {}
        except ValueError:
            body = None
        data = cast(Dict[str, Any], body) if isinstance(body, dict) else None
        if not response.is_success:
            error = data.get("error") if data else None
            description = data.get("error_description") if data else None
            raise AppAuthError(
                response.status_code,
                error if isinstance(error, str) else f"http_{response.status_code}",
                description if isinstance(description, str) else text[:200],
            )
        return data or {}

    def _store(self, answer: Dict[str, Any]) -> str:
        token = answer.get("access_token")
        expires = answer.get("expires_in")
        if not isinstance(token, str) or not isinstance(expires, (int, float)):
            raise AppAuthError(502, "invalid_response", "The token endpoint answered without a token.")
        self._token = (token, time.time() + float(expires), answer)
        return token


class AppTokenSource(_TokenBase):
    """Gets and keeps an access token for the synchronous client. Share one between clients."""

    def __init__(self, credentials: AppCredentials, *, http_client: Optional[httpx.Client] = None) -> None:
        super().__init__(credentials)
        self._http = http_client or httpx.Client()
        self._lock = threading.Lock()

    def get(self) -> str:
        """The current token; a new one when it expires within a minute."""
        with self._lock:
            fresh = self._fresh()
            if fresh:
                return fresh
            return self._store(self._post("/oauth/token", self._fields()))

    def revoke(self) -> None:
        """Ends the current token at Connect (RFC 7009), for example when the app signs out."""
        current = self._token[0] if self._token else None
        self._token = None
        if current:
            self._post("/oauth/revoke", {"token": current})

    def _post(self, path: str, fields: Dict[str, str]) -> Dict[str, Any]:
        url, form = self._form(path, fields)
        try:
            response = self._http.post(url, data=form, headers={"accept": "application/json"})
        except httpx.TransportError as e:
            raise APIConnectionError(f"POST {path} failed: {e}") from e
        return self._read(response)


class AsyncAppTokenSource(_TokenBase):
    """Gets and keeps an access token for the async client. Share one between clients."""

    def __init__(self, credentials: AppCredentials, *, http_client: Optional[httpx.AsyncClient] = None) -> None:
        super().__init__(credentials)
        self._http = http_client or httpx.AsyncClient()
        self._lock: Optional[asyncio.Lock] = None

    async def get(self) -> str:
        """The current token; a new one when it expires within a minute. Calls at the same moment share one request."""
        if self._lock is None:
            self._lock = asyncio.Lock()
        async with self._lock:
            fresh = self._fresh()
            if fresh:
                return fresh
            return self._store(await self._post("/oauth/token", self._fields()))

    async def revoke(self) -> None:
        """Ends the current token at Connect (RFC 7009)."""
        current = self._token[0] if self._token else None
        self._token = None
        if current:
            await self._post("/oauth/revoke", {"token": current})

    async def _post(self, path: str, fields: Dict[str, str]) -> Dict[str, Any]:
        url, form = self._form(path, fields)
        try:
            response = await self._http.post(url, data=form, headers={"accept": "application/json"})
        except httpx.TransportError as e:
            raise APIConnectionError(f"POST {path} failed: {e}") from e
        return self._read(response)
