package io.mcyber12.codingharness.core.packs;

/**
 * Where an optional pack comes from, as one port with two implementations.
 *
 * <p>The rest of the harness asks "is the pack there?", "install it", "remove
 * it" and never learns which channel answered. That is what lets the same UI
 * drive a Play build (feature delivery, no network at runtime for code) and an
 * F-Droid/direct build ({@code apt}, which may fetch packages) without a second
 * code path in the Activity.</p>
 *
 * <p>Mirrors OpenCode's service interfaces: methods return typed results rather
 * than throwing, and the caller renders the result.</p>
 */
public interface PackInstaller {

    /** Which channel this installer uses, for diagnostics. */
    String channel();

    /** Current state of a pack, cheap to call from the UI thread's worker. */
    PackState state(Pack pack);

    /** Install a pack. Blocking: call off the main thread. */
    PackResult install(Pack pack);

    /** Remove a pack, reclaiming its bytes. Blocking: call off the main thread. */
    PackResult remove(Pack pack);
}
