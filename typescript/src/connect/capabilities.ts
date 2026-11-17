import { errorFromResponse } from '../core/errors.ts';
import type { CapabilitySet, Verb, VerbCapability } from '../generated/types/connect.ts';

const KNOWN_STATUSES = new Set<string>(['supported', 'partial', 'unverified', 'unsupported']);

function callable(cap: VerbCapability, options: { allowUnverified?: boolean }): boolean {
  const statusOk = cap.status === 'supported' || cap.status === 'partial' || (options.allowUnverified === true && cap.status === 'unverified');
  return statusOk && cap.granted !== false && cap.licensed !== false;
}

/**
 * What one connection supports. Loads `capabilities.get` once and answers from that copy;
 * call `refresh()` after `connection.capabilities_changed`.
 *
 *   if (await client.connect.capabilities.forConnection(connectionId).supports('record.write')) { ... }
 */
export class Capabilities {
  #pending: Promise<CapabilitySet> | null = null;

  readonly connectionId: string;
  readonly #fetchSet: () => Promise<CapabilitySet>;

  constructor(connectionId: string, fetchSet: () => Promise<CapabilitySet>) {
    this.connectionId = connectionId;
    this.#fetchSet = fetchSet;
  }

  load(): Promise<CapabilitySet> {
    this.#pending ??= this.#fetchSet().catch((err: unknown) => {
      this.#pending = null;
      throw err;
    });
    return this.#pending;
  }

  refresh(): Promise<CapabilitySet> {
    this.#pending = null;
    return this.load();
  }

  /** The entry for one verb. A verb the API does not list is unsupported. */
  async verb(verb: Verb | (string & {})): Promise<VerbCapability> {
    const set = await this.load();
    return (set.verbs as Record<string, VerbCapability | undefined>)[verb] ?? { status: 'unsupported' };
  }

  /**
   * True when the verb is supported (fully or `partial`) here, granted to this app and licensed. With
   * `allowUnverified`, verbs that are built but not proven count too (test keys, pilot sites).
   * A status this SDK version does not know counts as not callable.
   */
  async supports(verb: Verb | (string & {}), options: { allowUnverified?: boolean } = {}): Promise<boolean> {
    return callable(await this.verb(verb), options);
  }

  /** Every verb that `supports()` would answer true for, sorted. */
  async callable(options: { allowUnverified?: boolean } = {}): Promise<string[]> {
    const set = await this.load();
    return Object.entries(set.verbs as Record<string, VerbCapability | undefined>)
      .filter(([, cap]) => cap !== undefined && callable(cap, options))
      .map(([verb]) => verb)
      .sort();
  }

  /** The `limits` codes of a `partial` verb, else an empty list. Show unknown codes as text. */
  async limits(verb: Verb | (string & {})): Promise<string[]> {
    const cap = await this.verb(verb);
    return cap.status === 'partial' ? (cap.limits ?? []) : [];
  }

  /** Throws the error the API would answer for this verb, so a UI can explain it before a call. */
  async require(verb: Verb | (string & {}), options: { allowUnverified?: boolean } = {}): Promise<void> {
    const cap = await this.verb(verb);
    const base = { request_id: 'local', verb, connection_id: this.connectionId };
    if (cap.status === 'unsupported') {
      throw errorFromResponse(422, {
        error: { ...base, type: 'capability_error', code: 'verb_unsupported', message: `This connection cannot run ${verb}.`, ...(cap.reason ? { reason: cap.reason } : {}), ...(cap.alternative ? { alternative: cap.alternative } : {}) },
      });
    }
    if (cap.status === 'unverified' && !options.allowUnverified) {
      throw errorFromResponse(422, {
        error: { ...base, type: 'capability_error', code: 'verb_unverified', message: `${verb} is not proven on this system.`, ...(cap.reason ? { reason: cap.reason } : {}) },
      });
    }
    if (!KNOWN_STATUSES.has(cap.status)) {
      // A status added to the contract after this SDK version: not callable until the SDK knows it.
      throw errorFromResponse(422, {
        error: { ...base, type: 'capability_error', code: 'capability_status_unknown', message: `${verb} has the status ${cap.status}, which this SDK version does not know.` },
      });
    }
    if (cap.granted === false) {
      throw errorFromResponse(403, { error: { ...base, type: 'capability_error', code: 'verb_not_granted', message: `The practice admin has not granted ${verb} to this app.` } });
    }
    if (cap.licensed === false) {
      throw errorFromResponse(403, { error: { ...base, type: 'licence_error', code: 'licence_required', message: 'No active licence for this connector.' } });
    }
  }
}
