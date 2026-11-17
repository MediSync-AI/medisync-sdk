import type * as Schemas from '../generated/types/connect.ts';

export type ReleaseBy = {
  /** The member who read the preview and released it. */
  memberId: string;
  displayName?: string;
  /** Their role at the site. The grant names the roles that may release a verb. */
  role: Schemas.Role;
  /** Their practitioner id in the target system, as set on the member. */
  practitionerLink: string;
  /**
   * The patient the person confirmed on screen: id, and name and birth date when your app showed
   * them. The hub compares it with the patient of the plan and with the record.
   */
  patientConfirmed: Schemas.PatientRef;
  /** When the person released it. Default: now. */
  releasedAt?: Date | string;
};

/** RFC 3339 with seconds and without milliseconds, as the API takes it. */
function timestamp(value: Date | string): string {
  const date = typeof value === 'string' ? new Date(value) : value;
  if (Number.isNaN(date.getTime())) throw new TypeError(`releasedAt is not a time: ${String(value)}`);
  return date.toISOString().replace(/\.\d{3}Z$/, 'Z');
}

/**
 * The `release` of `transfers.create` for a plan a person reviewed. Takes `document_hash` and
 * `plan_hash` from the plan, so the release names exactly what the person saw.
 *
 * Call it only after the person released the preview in your app. The SDK does not check that;
 * Connect and the hub check the member, role, practitioner link, patient and hashes.
 */
export function buildRelease(plan: Pick<Schemas.Plan, 'document_hash' | 'plan_hash'>, by: ReleaseBy): Schemas.ReleaseInput {
  return {
    released_by: { member_id: by.memberId, ...(by.displayName ? { display_name: by.displayName } : {}) },
    role: by.role,
    practitioner_link: by.practitionerLink,
    patient_confirmed: by.patientConfirmed,
    document_hash: plan.document_hash,
    plan_hash: plan.plan_hash,
    released_at: timestamp(by.releasedAt ?? new Date()),
  };
}
