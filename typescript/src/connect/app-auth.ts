import { APIConnectionError, MediSyncError } from '../core/errors.ts';

// App mode: a partner app authenticates with its own key pair instead of an `msk_` key
// (Connect docs, "Apps and tokens"). The SDK signs a client assertion (RFC 7523,
// `private_key_jwt`) with the app's private key, trades it at Connect's token endpoint for a
// 15-minute access token, and gets a new one before it expires. The private key never leaves
// the process; MediSync holds only the public key.

export const DEFAULT_ISSUER = 'https://connect.medisync.me';
const ASSERTION_TYPE = 'urn:ietf:params:oauth:client-assertion-type:jwt-bearer';
/** A new token this long before the old one expires. */
const REFRESH_MARGIN_MS = 60_000;
/** An assertion lives one minute; Connect allows five. */
const ASSERTION_SECONDS = 60;

export type AppCredentials = {
  /** The app id from the console, `app_…`. It is the OAuth client_id. */
  clientId: string;
  /** The key id (`kid`) the public key has in the console. */
  keyId: string;
  /**
   * The app's private key: a PKCS8 PEM (`-----BEGIN PRIVATE KEY-----`) or a WebCrypto CryptoKey.
   * EC P-256 signs with ES256, Ed25519 with EdDSA.
   */
  privateKey: string | CryptoKey;
  /**
   * Act at one site: the installation id (`ins_…`). The token then reaches only that site and
   * carries the installation's verbs. Leave it out for an app token (sites.create, lists).
   */
  installationId?: string;
  /** Fewer verbs than the installation holds, for this client's token. */
  verbs?: string[];
  /** Fewer scopes than the app holds. */
  scopes?: string[];
  /** An app token of an app with a live and a test project names the environment. */
  environment?: 'live' | 'test';
  /** The authorization server. Default `https://connect.medisync.me`. */
  issuer?: string;
};

/** The token endpoint refused: `error` is the OAuth error code (RFC 6749 5.2). */
export class AppAuthError extends MediSyncError {
  readonly status: number;
  readonly error: string;

  constructor(status: number, error: string, description: string) {
    super(`${error}: ${description}`);
    this.status = status;
    this.error = error;
  }
}

export type TokenAnswer = {
  access_token: string;
  token_type: string;
  expires_in: number;
  scope?: string;
  installation_id?: string;
  authorization_details?: unknown[];
};

// btoa and atob, not Buffer: the SDK runs on any runtime with fetch and Web Crypto.
const b64url = (input: Uint8Array | string) => {
  const bytes = typeof input === 'string' ? new TextEncoder().encode(input) : input;
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};
const fromB64 = (text: string) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));

async function signingKey(key: string | CryptoKey): Promise<{ key: CryptoKey; alg: 'ES256' | 'EdDSA' }> {
  const subtle = globalThis.crypto.subtle;
  if (typeof key !== 'string') {
    const name = key.algorithm.name;
    if (name === 'ECDSA') return { key, alg: 'ES256' };
    if (name === 'Ed25519') return { key, alg: 'EdDSA' };
    throw new TypeError('The app key must be an EC P-256 or an Ed25519 key.');
  }
  const body = key.replace(/\\n/g, '\n').match(/-----BEGIN PRIVATE KEY-----([\s\S]+?)-----END PRIVATE KEY-----/)?.[1];
  if (!body) throw new TypeError('privateKey must be a PKCS8 PEM (-----BEGIN PRIVATE KEY-----).');
  const der = fromB64(body.replace(/\s+/g, ''));
  try {
    return { key: await subtle.importKey('pkcs8', der, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']), alg: 'ES256' };
  } catch {
    try {
      return { key: await subtle.importKey('pkcs8', der, { name: 'Ed25519' }, false, ['sign']), alg: 'EdDSA' };
    } catch {
      throw new TypeError('privateKey must be an EC P-256 or an Ed25519 private key.');
    }
  }
}

/** A client assertion (RFC 7523 2.2): iss = sub = client_id, aud = the endpoint, one-time jti. */
export async function clientAssertion(c: Pick<AppCredentials, 'clientId' | 'keyId' | 'privateKey'>, audience: string, now: number = Math.floor(Date.now() / 1000)): Promise<string> {
  const { key, alg } = await signingKey(c.privateKey);
  const header = b64url(JSON.stringify({ alg, kid: c.keyId, typ: 'JWT' }));
  const payload = b64url(JSON.stringify({ iss: c.clientId, sub: c.clientId, aud: audience, iat: now, exp: now + ASSERTION_SECONDS, jti: globalThis.crypto.randomUUID() }));
  const input = new TextEncoder().encode(`${header}.${payload}`);
  // WebCrypto signs ECDSA in the JWS form (r || s), as ES256 wants it.
  const signature = await globalThis.crypto.subtle.sign(alg === 'ES256' ? { name: 'ECDSA', hash: 'SHA-256' } : { name: 'Ed25519' }, key, input);
  return `${header}.${payload}.${b64url(new Uint8Array(signature))}`;
}

/**
 * Gets and keeps an access token. `get()` answers the current token and fetches a new one when
 * it expires within a minute; calls at the same moment share one request. `clear()` drops the
 * token, so the next call fetches one (after a 401 `invalid_token`).
 */
export class AppTokenSource {
  private token: { value: string; expiresAt: number; answer: TokenAnswer } | null = null;
  private pending: Promise<TokenAnswer> | null = null;
  readonly issuer: string;
  private readonly credentials: AppCredentials;
  private readonly fetchImpl: (input: Request) => Promise<Response>;
  private readonly now: () => number;

  constructor(credentials: AppCredentials, fetchImpl: (input: Request) => Promise<Response> = (input) => globalThis.fetch(input), now: () => number = () => Date.now()) {
    this.credentials = credentials;
    this.fetchImpl = fetchImpl;
    this.now = now;
    if (!/^app_[A-Za-z0-9]{8,32}$/.test(credentials.clientId)) throw new TypeError('clientId is the app id from the console, app_….');
    this.issuer = (credentials.issuer ?? DEFAULT_ISSUER).replace(/\/+$/, '');
  }

  async get(): Promise<string> {
    if (this.token && this.token.expiresAt - REFRESH_MARGIN_MS > this.now()) return this.token.value;
    return (await this.fetchToken()).access_token;
  }

  /** The last token answer: installation id and authorization details. Null before the first call. */
  get answer(): TokenAnswer | null {
    return this.token?.answer ?? null;
  }

  clear(): void {
    this.token = null;
  }

  /** Ends the current token at Connect (RFC 7009), for example when the app signs out. */
  async revoke(): Promise<void> {
    const current = this.token?.value;
    this.token = null;
    if (!current) return;
    await this.post('/oauth/revoke', { token: current });
  }

  private fetchToken(): Promise<TokenAnswer> {
    this.pending ??= (async () => {
      try {
        const c = this.credentials;
        const fields: Record<string, string> = { grant_type: 'client_credentials' };
        if (c.scopes?.length) fields.scope = c.scopes.join(' ');
        if (c.installationId) {
          fields.authorization_details = JSON.stringify([
            { type: 'medisync_connect', installation_id: c.installationId, ...(c.verbs?.length ? { verbs: c.verbs.map((verb) => ({ verb, roles: [] })) } : {}) },
          ]);
        } else if (c.environment) {
          fields.environment = c.environment;
        }
        const answer = (await this.post('/oauth/token', fields)) as TokenAnswer;
        if (typeof answer?.access_token !== 'string' || typeof answer.expires_in !== 'number') throw new AppAuthError(502, 'invalid_response', 'The token endpoint answered without a token.');
        this.token = { value: answer.access_token, expiresAt: this.now() + answer.expires_in * 1000, answer };
        return answer;
      } finally {
        this.pending = null;
      }
    })();
    return this.pending;
  }

  private async post(path: string, fields: Record<string, string>): Promise<unknown> {
    const url = `${this.issuer}${path}`;
    const body = new URLSearchParams({ ...fields, client_assertion_type: ASSERTION_TYPE, client_assertion: await clientAssertion(this.credentials, url) });
    let res: Response;
    try {
      res = await this.fetchImpl(new Request(url, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded', accept: 'application/json' }, body }));
    } catch (err) {
      throw new APIConnectionError(`POST ${path} failed`, { cause: err });
    }
    const text = await res.text();
    let json: Record<string, unknown> | null = null;
    try {
      json = text ? (JSON.parse(text) as Record<string, unknown>) : {};
    } catch {
      json = null;
    }
    if (!res.ok) {
      throw new AppAuthError(res.status, typeof json?.error === 'string' ? json.error : `http_${res.status}`, typeof json?.error_description === 'string' ? json.error_description : text.slice(0, 200));
    }
    return json;
  }
}
