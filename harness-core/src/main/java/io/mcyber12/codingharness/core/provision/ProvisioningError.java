package io.mcyber12.codingharness.core.provision;

/**
 * Typed provisioning failure — the equivalent of an OpenCode {@code NamedError}:
 * a stable {@link Kind} plus human-readable detail, so callers (dialogs, CLI
 * output, diagnostics files) branch on the kind instead of parsing messages.
 */
public final class ProvisioningError {

    public enum Kind {
        /** apt index could not be refreshed. */
        INDEX,
        /** base-system upgrade failed. */
        BASE_UPGRADE,
        /** package install failed. */
        INSTALL,
        /** install reported success but a required command is missing. */
        VERIFY,
        /** local I/O failed before or after the package work. */
        IO,
        /** the calling thread was interrupted. */
        INTERRUPTED
    }

    public final Kind kind;
    public final String message;
    public final String detail;

    public ProvisioningError(Kind kind, String message, String detail) {
        this.kind = kind;
        this.message = message;
        this.detail = detail == null ? "" : detail;
    }

    /** Single string for a dialog, log line or persisted setup result. */
    public String describe() {
        return kind + ": " + message + (detail.isEmpty() ? "" : " — " + detail);
    }

    @Override public String toString() { return describe(); }
}
