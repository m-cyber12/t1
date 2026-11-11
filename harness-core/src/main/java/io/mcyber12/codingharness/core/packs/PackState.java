package io.mcyber12.codingharness.core.packs;

import java.util.Collections;
import java.util.List;

/**
 * What a pack currently is: a value the UI can render without asking again.
 *
 * <p>{@link #source} matters as much as {@link #status}: an unavailable pack on a
 * device without Play is not a bug in the app, it is the delivery channel being
 * absent, and the message says which channel would have served it.</p>
 */
public final class PackState {

    public enum Status {
        /** Commands present and working. */
        INSTALLED,
        /** Not installed, but the bytes are on the device (a universal APK). */
        AVAILABLE_LOCALLY,
        /** Not installed; a store download is required. */
        AVAILABLE_FROM_STORE,
        /** Not installed; the package manager can fetch it (the apt build). */
        AVAILABLE_FROM_PACKAGES,
        /** Not installed and nothing can install it here. */
        UNAVAILABLE
    }

    public final Pack pack;
    public final Status status;
    public final String source;
    public final List<String> missingCommands;

    public PackState(Pack pack, Status status, String source, List<String> missingCommands) {
        this.pack = pack;
        this.status = status;
        this.source = source;
        this.missingCommands = missingCommands == null
            ? Collections.<String>emptyList()
            : Collections.unmodifiableList(missingCommands);
    }

    public boolean isInstalled() { return status == Status.INSTALLED; }

    /** True when {@link PackInstaller#install} has something to work with. */
    public boolean isInstallable() {
        return status == Status.AVAILABLE_LOCALLY
            || status == Status.AVAILABLE_FROM_STORE
            || status == Status.AVAILABLE_FROM_PACKAGES;
    }

    public String describe() {
        StringBuilder text = new StringBuilder(pack.label).append(": ").append(status)
            .append(" via ").append(source);
        if (!missingCommands.isEmpty()) text.append(", missing ").append(missingCommands);
        return text.toString();
    }
}
