package de.raindancer.modules.claims.extension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every {@link ClaimMenuExtension} currently registered.
 *
 * <p>Static and process-wide rather than owned by one {@code ClaimServices} instance: the modules that
 * register here are hosted by whichever plugin loaded them, not by this one, and there is exactly one
 * of these per server regardless of how many plugins are involved — the same reasoning {@code Messages}
 * already follows for being a flat, server-wide namespace.
 *
 * <p>A module registers when it enables and unregisters when it disables. Left registered past that
 * point would mean a claim page still asking a module that has already unwound its own state for an
 * answer, which is exactly the kind of half-torn-down code this reactor's own {@code ModuleRegistry}
 * exists to prevent everywhere else.
 */
public final class ClaimMenuExtensions {

    private static final List<ClaimMenuExtension> extensions = new CopyOnWriteArrayList<>();

    private ClaimMenuExtensions() {
    }

    public static void register(ClaimMenuExtension extension) {
        if (extension != null) {
            extensions.add(extension);
        }
    }

    public static void unregister(ClaimMenuExtension extension) {
        extensions.remove(extension);
    }

    /** Every contributor currently registered, in the order they registered. */
    public static List<ClaimMenuExtension> all() {
        return List.copyOf(extensions);
    }
}
