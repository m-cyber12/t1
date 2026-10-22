package io.mcyber12.codingharness.core.provision;

/**
 * Immutable snapshot of provisioning progress, published to listeners.
 *
 * <p>Immutable snapshots (rather than mutable flags) are what let a UI react
 * to "setup finished" the moment it happens, instead of polling or requiring
 * an app restart — the concrete bug this replaced.</p>
 */
public final class ProvisioningState {
    public final ProvisioningStatus status;
    public final String message;
    /** Extra detail for diagnostics (size summary, apt output tail). */
    public final String detail;
    public final ProvisioningError error;
    public final long updatedAtMillis;

    private ProvisioningState(ProvisioningStatus status, String message,
                              String detail, ProvisioningError error) {
        this.status = status;
        this.message = message == null ? "" : message;
        this.detail = detail == null ? "" : detail;
        this.error = error;
        this.updatedAtMillis = System.currentTimeMillis();
    }

    public static ProvisioningState idle() {
        return new ProvisioningState(ProvisioningStatus.IDLE, "not started", "", null);
    }

    public static ProvisioningState of(ProvisioningStatus status, String message) {
        return new ProvisioningState(status, message, "", null);
    }

    public static ProvisioningState of(ProvisioningStatus status, String message, String detail) {
        return new ProvisioningState(status, message, detail, null);
    }

    public static ProvisioningState failed(ProvisioningError error) {
        return new ProvisioningState(ProvisioningStatus.FAILED, error.describe(), "", error);
    }

    public boolean isReady() { return status == ProvisioningStatus.READY; }
    public boolean isFailed() { return status == ProvisioningStatus.FAILED; }
    public boolean isTerminal() { return isReady() || isFailed(); }

    @Override public String toString() { return status + ": " + message; }
}
