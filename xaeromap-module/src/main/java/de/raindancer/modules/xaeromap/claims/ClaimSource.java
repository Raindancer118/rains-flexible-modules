package de.raindancer.modules.xaeromap.claims;

import de.raindancer.modules.xaeromap.model.ClaimFacts;

import java.util.List;

/**
 * Where the claims come from.
 *
 * <p>An interface with exactly one real implementation today, which is not over-engineering: it is what
 * keeps every claims-module type inside this package. Everything else in the module is written against
 * {@link ClaimFacts}, so a server with no claims plugin loads this module and runs the half of it that
 * is about worlds rather than claims, instead of dying on a class that is not there.
 *
 * <p>Also what makes the sync testable at all — a test hands over a list of claims and reads the
 * packets that come out, with no server and no claims plugin anywhere near it.
 */
public interface ClaimSource {

    /** Nothing at all, for a server with no claims plugin. */
    ClaimSource NONE = new ClaimSource() {

        @Override
        public String name() {
            return "no claims plugin";
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public List<ClaimFacts> claims() {
            return List.of();
        }
    };

    /** Who is answering, for the line in the log that says so. */
    String name();

    /** Whether there is anything behind this at all. */
    boolean available();

    /**
     * Every claim there is, already reduced to what a map needs.
     *
     * <p>Called on a timer, on the server thread. Expected to be a read of an in-memory registry — this
     * is not the place for a file or a database round trip.
     */
    List<ClaimFacts> claims();
}
