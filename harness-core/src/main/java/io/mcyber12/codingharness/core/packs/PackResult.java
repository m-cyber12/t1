package io.mcyber12.codingharness.core.packs;

import java.util.Collections;
import java.util.List;

/**
 * The outcome of asking for a pack, as a typed value rather than an exception.
 *
 * <p>Same convention as {@code ProvisioningState}: a pack request can fail for
 * reasons the UI has to tell apart — no Play Store on the device, a cancelled
 * download, a payload that arrived broken — and each one leads to a different
 * sentence and a different next action.</p>
 */
public final class PackResult {

    public enum Kind {
        /** Installed and its commands verified. */
        INSTALLED,
        /** Removed, with the bytes it reclaimed. */
        REMOVED,
        /** Already in the requested state; nothing was done. */
        UNCHANGED,
        /** Needs a store download, and this device has no Play Store. */
        UNAVAILABLE,
        /** The download was cancelled or failed in the store client. */
        DELIVERY_FAILED,
        /** Downloaded, but the payload could not be applied. */
        INSTALL_FAILED,
        /** Neither source exists: not a store build, no apt. */
        UNSUPPORTED
    }

    public final Kind kind;
    public final String packId;
    public final String message;
    public final long bytes;
    public final List<String> problems;

    public PackResult(Kind kind, String packId, String message, long bytes,
                      List<String> problems) {
        this.kind = kind;
        this.packId = packId;
        this.message = message;
        this.bytes = bytes;
        this.problems = problems == null
            ? Collections.<String>emptyList()
            : Collections.unmodifiableList(problems);
    }

    public static PackResult ok(Kind kind, String packId, String message) {
        return new PackResult(kind, packId, message, 0, null);
    }

    public static PackResult failed(Kind kind, String packId, String message) {
        return new PackResult(kind, packId, message, 0, null);
    }

    public boolean isSuccess() {
        return kind == Kind.INSTALLED || kind == Kind.REMOVED || kind == Kind.UNCHANGED;
    }

    public String describe() {
        StringBuilder text = new StringBuilder(packId).append(": ").append(kind)
            .append(" — ").append(message);
        if (bytes > 0) text.append(" (").append(bytes).append(" bytes)");
        if (!problems.isEmpty()) text.append(" ").append(problems);
        return text.toString();
    }
}
