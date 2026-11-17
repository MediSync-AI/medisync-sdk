package me.medisync.connect;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import me.medisync.core.Json;
import me.medisync.errors.ApiErrors;
import me.medisync.models.connect.CapabilitySet;
import me.medisync.models.connect.VerbCapability;
import org.jspecify.annotations.Nullable;

/**
 * What one connection supports, with async methods. Loads <code>capabilities.get</code> once and
 * answers from that copy; call {@link #refresh()} after <code>connection.capabilities_changed</code>.
 */
public final class CapabilitiesAsync {
  private static final Set<String> KNOWN_STATUSES = new HashSet<>(Arrays.asList("supported", "partial", "unverified", "unsupported"));
  private static final HttpHeaders NO_HEADERS = HttpHeaders.of(Collections.emptyMap(), (a, b) -> true);

  private final String connectionId;
  private final Supplier<CompletableFuture<CapabilitySet>> fetchSet;
  private @Nullable CompletableFuture<CapabilitySet> pending;

  /** Made by <code>connect.capabilities.forConnection</code>. */
  public CapabilitiesAsync(String connectionId, Supplier<CompletableFuture<CapabilitySet>> fetchSet) {
    this.connectionId = connectionId;
    this.fetchSet = fetchSet;
  }

  /** The connection. */
  public String getConnectionId() {
    return connectionId;
  }

  /** The capability set, loaded once. */
  public synchronized CompletableFuture<CapabilitySet> load() {
    CompletableFuture<CapabilitySet> p = pending;
    if (p == null) {
      p = fetchSet.get();
      pending = p;
      CompletableFuture<CapabilitySet> mine = p;
      p.whenComplete(
          (set, err) -> {
            if (err != null) {
              synchronized (this) {
                if (pending == mine) pending = null;
              }
            }
          });
    }
    return p;
  }

  /** Loads the set again. */
  public CompletableFuture<CapabilitySet> refresh() {
    synchronized (this) {
      pending = null;
    }
    return load();
  }

  private static VerbCapability unsupported() {
    return new VerbCapability().status("unsupported");
  }

  /** The entry for one verb. A verb the API does not list is unsupported. */
  public CompletableFuture<VerbCapability> verb(String verb) {
    return load()
        .thenApply(
            set -> {
              Map<String, VerbCapability> verbs = set.getVerbs();
              VerbCapability cap = verbs == null ? null : verbs.get(verb);
              return cap == null ? unsupported() : cap;
            });
  }

  private static boolean isCallable(VerbCapability cap, boolean allowUnverified) {
    String s = cap.getStatus();
    boolean statusOk = "supported".equals(s) || "partial".equals(s) || (allowUnverified && "unverified".equals(s));
    return statusOk && !Boolean.FALSE.equals(cap.getGranted()) && !Boolean.FALSE.equals(cap.getLicensed());
  }

  /**
   * True when the verb is supported (fully or <code>partial</code>) here, granted to this app and
   * licensed. A status this SDK version does not know counts as not callable.
   */
  public CompletableFuture<Boolean> supports(String verb) {
    return supports(verb, false);
  }

  /** As {@link #supports(String)}; with allowUnverified, verbs built but not proven count too. */
  public CompletableFuture<Boolean> supports(String verb, boolean allowUnverified) {
    return verb(verb).thenApply(cap -> isCallable(cap, allowUnverified));
  }

  /** Every verb that supports() would answer true for, sorted. */
  public CompletableFuture<List<String>> callable() {
    return callable(false);
  }

  /** Every verb that supports(verb, allowUnverified) would answer true for, sorted. */
  public CompletableFuture<List<String>> callable(boolean allowUnverified) {
    return load()
        .thenApply(
            set -> {
              List<String> out = new ArrayList<>();
              Map<String, VerbCapability> verbs = set.getVerbs();
              if (verbs != null) verbs.forEach((v, cap) -> {
                if (cap != null && isCallable(cap, allowUnverified)) out.add(v);
              });
              Collections.sort(out);
              return out;
            });
  }

  /** The <code>limits</code> codes of a <code>partial</code> verb, else an empty list. Show unknown codes as text. */
  public CompletableFuture<List<String>> limits(String verb) {
    return verb(verb).thenApply(cap -> "partial".equals(cap.getStatus()) && cap.getLimits() != null ? cap.getLimits() : Collections.<String>emptyList());
  }

  /** Fails with the exception the API would answer for this verb, so a UI can explain it before a call. */
  public CompletableFuture<Void> require(String verb) {
    return require(verb, false);
  }

  /** As {@link #require(String)}; with allowUnverified, verbs built but not proven pass. */
  public CompletableFuture<Void> require(String verb, boolean allowUnverified) {
    return verb(verb)
        .thenApply(
            cap -> {
              String status = String.valueOf(cap.getStatus());
              if ("unsupported".equals(status)) {
                ObjectNode e = error(verb, "capability_error", "verb_unsupported", "This connection cannot run " + verb + ".");
                if (cap.getReason() != null) e.put("reason", cap.getReason());
                if (cap.getAlternative() != null) e.put("alternative", cap.getAlternative());
                throw ApiErrors.fromJson(422, wrap(e), NO_HEADERS, null);
              }
              if ("unverified".equals(status) && !allowUnverified) {
                ObjectNode e = error(verb, "capability_error", "verb_unverified", verb + " is not proven on this system.");
                if (cap.getReason() != null) e.put("reason", cap.getReason());
                throw ApiErrors.fromJson(422, wrap(e), NO_HEADERS, null);
              }
              if (!KNOWN_STATUSES.contains(status)) {
                // A status added to the contract after this SDK version: not callable until the SDK knows it.
                throw ApiErrors.fromJson(422, wrap(error(verb, "capability_error", "capability_status_unknown", verb + " has the status " + status + ", which this SDK version does not know.")), NO_HEADERS, null);
              }
              if (Boolean.FALSE.equals(cap.getGranted())) {
                throw ApiErrors.fromJson(403, wrap(error(verb, "capability_error", "verb_not_granted", "The practice admin has not granted " + verb + " to this app.")), NO_HEADERS, null);
              }
              if (Boolean.FALSE.equals(cap.getLicensed())) {
                throw ApiErrors.fromJson(403, wrap(error(verb, "licence_error", "licence_required", "No active licence for this connector.")), NO_HEADERS, null);
              }
              return null;
            });
  }

  private ObjectNode error(String verb, String type, String code, String message) {
    return Json.MAPPER.createObjectNode().put("request_id", "local").put("verb", verb).put("connection_id", connectionId).put("type", type).put("code", code).put("message", message);
  }

  private static ObjectNode wrap(ObjectNode error) {
    ObjectNode body = Json.MAPPER.createObjectNode();
    body.set("error", error);
    return body;
  }
}
