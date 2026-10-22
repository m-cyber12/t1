package io.mcyber12.codingharness.core.provision;

/**
 * Provisioning lifecycle. Mirrors OpenCode's status enums: explicit,
 * serialisable phases that a client (CLI, TUI, HTTP) can render directly
 * instead of inferring progress from log text.
 */
public enum ProvisioningStatus {
    IDLE,
    RESOLVING,
    UPDATING_INDEX,
    UPGRADING_BASE,
    INSTALLING,
    VERIFYING,
    CLEANING,
    READY,
    FAILED
}
