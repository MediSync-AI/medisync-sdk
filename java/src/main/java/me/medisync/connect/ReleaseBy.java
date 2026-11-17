package me.medisync.connect;

import java.time.Instant;
import me.medisync.models.connect.PatientRef;
import org.jspecify.annotations.Nullable;

/** Who released a reviewed plan, for {@link Releases#buildRelease}. */
public final class ReleaseBy {
  private final String memberId;
  private final @Nullable String displayName;
  private final String role;
  private final String practitionerLink;
  private final PatientRef patientConfirmed;
  private final @Nullable Instant releasedAt;

  private ReleaseBy(Builder b) {
    this.memberId = b.memberId;
    this.displayName = b.displayName;
    this.role = b.role;
    this.practitionerLink = b.practitionerLink;
    this.patientConfirmed = b.patientConfirmed;
    this.releasedAt = b.releasedAt;
  }

  /**
   * A builder with the member who read the preview and released it, their role at the site (the
   * grant names the roles that may release a verb), their practitioner id in the target system, and
   * the patient the person confirmed on screen.
   */
  public static Builder builder(String memberId, String role, String practitionerLink, PatientRef patientConfirmed) {
    return new Builder(memberId, role, practitionerLink, patientConfirmed);
  }

  String getMemberId() {
    return memberId;
  }

  @Nullable String getDisplayName() {
    return displayName;
  }

  String getRole() {
    return role;
  }

  String getPractitionerLink() {
    return practitionerLink;
  }

  PatientRef getPatientConfirmed() {
    return patientConfirmed;
  }

  @Nullable Instant getReleasedAt() {
    return releasedAt;
  }

  /** Builds {@link ReleaseBy}. */
  public static final class Builder {
    private final String memberId;
    private final String role;
    private final String practitionerLink;
    private final PatientRef patientConfirmed;
    private @Nullable String displayName;
    private @Nullable Instant releasedAt;

    private Builder(String memberId, String role, String practitionerLink, PatientRef patientConfirmed) {
      this.memberId = memberId;
      this.role = role;
      this.practitionerLink = practitionerLink;
      this.patientConfirmed = patientConfirmed;
    }

    /** The member's name. */
    public Builder displayName(String value) {
      this.displayName = value;
      return this;
    }

    /** When the person released it. Default: now. */
    public Builder releasedAt(Instant value) {
      this.releasedAt = value;
      return this;
    }

    /** The release author. */
    public ReleaseBy build() {
      return new ReleaseBy(this);
    }
  }
}
