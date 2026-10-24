package io.mcyber12.codingharness.core.provision;

import java.util.List;

import io.mcyber12.codingharness.core.HarnessConfig;

/**
 * The provisioning module: one service interface, one factory, one event
 * contract. Follows OpenCode's module conventions —
 *
 * <pre>
 * // OpenCode (TypeScript)                  // this module (Java)
 * export class Service extends Context.Service&lt;Service, Interface&gt;()
 *      ("@opencode/Provisioning") {}        Provisioning.Service
 * Layer.effect(Service, ...)                Provisioning.create(config, packages)
 * Bus events + subscriber streams           Provisioning.Listener + subscribe()
 * Info schemas (Tier, Plan, State, Error)   PackageTier / ProvisioningPlan / ...
 * </pre>
 *
 * <p>Services are per-location (one per prefix), own no mutable globals, and
 * publish immutable {@link ProvisioningState} snapshots so clients react to
 * completion immediately instead of polling or restarting.</p>
 */
public final class Provisioning {

    private Provisioning() {}

    /** Receives every state transition, starting with the current snapshot. */
    public interface Listener {
        void onState(ProvisioningState state);
    }

    /** The provisioning service. Blocking methods: call off the main thread. */
    public interface Service {

        /** Current snapshot; safe to read from any thread. */
        ProvisioningState state();

        /** Registers a listener and immediately delivers the current state. */
        void subscribe(Listener listener);

        void unsubscribe(Listener listener);

        /** True when every required command of the core tier is present. */
        boolean isReady();

        /**
         * Installs the requested tiers (defaults when {@code tierIds} is
         * null/empty). Concurrent calls collapse onto the run in progress.
         */
        ProvisioningState provision(List<String> tierIds);

        /** Installs one tier by id; same pipeline, no base-system upgrade. */
        ProvisioningState installTier(String tierId);

        /**
         * Removes an optional tier and the dependencies nothing else needs,
         * then reclaims storage. Core is not removable: it is the harness.
         */
        ProvisioningState removeTier(String tierId);

        /** Default tier ids, for callers that just want "make it ready". */
        List<String> defaultTierIds();
    }

    /** Service over the platform apt/dpkg implementation. */
    public static Service create(HarnessConfig config) {
        return create(config, new PrefixPackageManager(config));
    }

    /** Service over an explicit port — the seam used by tests and ports. */
    public static Service create(HarnessConfig config, PackageManager packages) {
        return new DefaultProvisioning(config, packages);
    }
}
