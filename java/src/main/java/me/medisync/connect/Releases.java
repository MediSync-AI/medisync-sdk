package me.medisync.connect;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import me.medisync.models.connect.MemberRef;
import me.medisync.models.connect.Plan;
import me.medisync.models.connect.ReleaseInput;

/** The release of a plan a person reviewed. */
public final class Releases {
  private Releases() {}

  /**
   * The <code>release</code> of <code>transfers.create</code> for a plan a person reviewed. Takes
   * <code>document_hash</code> and <code>plan_hash</code> from the plan, so the release names exactly
   * what the person saw.
   *
   * <p>Call it only after the person released the preview in your app. The SDK does not check
   * that; Connect and the hub check the member, role, practitioner link, patient and hashes.
   */
  public static ReleaseInput buildRelease(Plan plan, ReleaseBy by) {
    MemberRef member = new MemberRef().memberId(by.getMemberId());
    if (by.getDisplayName() != null) member.setDisplayName(by.getDisplayName());
    Instant at = by.getReleasedAt() != null ? by.getReleasedAt() : Instant.now();
    // RFC 3339 with seconds and without fractions, as the API takes it.
    String releasedAt = DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.SECONDS));
    ReleaseInput release =
        new ReleaseInput()
            .releasedBy(member)
            .role(by.getRole())
            .practitionerLink(by.getPractitionerLink())
            .patientConfirmed(by.getPatientConfirmed())
            .documentHash(plan.getDocumentHash())
            .releasedAt(releasedAt);
    if (plan.getPlanHash() != null) release.setPlanHash(plan.getPlanHash());
    return release;
  }
}
