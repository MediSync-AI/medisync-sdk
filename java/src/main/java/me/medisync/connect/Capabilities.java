package me.medisync.connect;

import java.util.List;
import me.medisync.core.ClientCore;
import me.medisync.models.connect.CapabilitySet;
import me.medisync.models.connect.VerbCapability;

/**
 * What one connection supports. Loads <code>capabilities.get</code> once and answers from that
 * copy; call {@link #refresh()} after <code>connection.capabilities_changed</code>.
 *
 * <pre>{@code
 * if (client.connect().capabilities().forConnection(connectionId).supports("record.write")) { ... }
 * }</pre>
 */
public final class Capabilities {
  private final CapabilitiesAsync async;

  /** Made by <code>connect.capabilities.forConnection</code>. */
  public Capabilities(CapabilitiesAsync async) {
    this.async = async;
  }

  /** The connection. */
  public String getConnectionId() {
    return async.getConnectionId();
  }

  /** The capability set, loaded once. */
  public CapabilitySet load() {
    return ClientCore.join(async.load());
  }

  /** Loads the set again. */
  public CapabilitySet refresh() {
    return ClientCore.join(async.refresh());
  }

  /** The entry for one verb. A verb the API does not list is unsupported. */
  public VerbCapability verb(String verb) {
    return ClientCore.join(async.verb(verb));
  }

  /** True when the verb is supported (fully or <code>partial</code>), granted to this app and licensed. */
  public boolean supports(String verb) {
    return ClientCore.join(async.supports(verb));
  }

  /** As {@link #supports(String)}; with allowUnverified, verbs built but not proven count too. */
  public boolean supports(String verb, boolean allowUnverified) {
    return ClientCore.join(async.supports(verb, allowUnverified));
  }

  /** Every verb that supports() would answer true for, sorted. */
  public List<String> callable() {
    return ClientCore.join(async.callable());
  }

  /** Every verb that supports(verb, allowUnverified) would answer true for, sorted. */
  public List<String> callable(boolean allowUnverified) {
    return ClientCore.join(async.callable(allowUnverified));
  }

  /** The <code>limits</code> codes of a <code>partial</code> verb, else an empty list. */
  public List<String> limits(String verb) {
    return ClientCore.join(async.limits(verb));
  }

  /** Throws the exception the API would answer for this verb. */
  public void require(String verb) {
    ClientCore.join(async.require(verb));
  }

  /** As {@link #require(String)}; with allowUnverified, verbs built but not proven pass. */
  public void require(String verb, boolean allowUnverified) {
    ClientCore.join(async.require(verb, allowUnverified));
  }
}
