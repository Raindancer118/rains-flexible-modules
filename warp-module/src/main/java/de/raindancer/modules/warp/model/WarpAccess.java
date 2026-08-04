package de.raindancer.modules.warp.model;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Who a warp is for: everybody, the staff, or whoever holds one particular permission.
 *
 * <h2>Why three and not a boolean</h2>
 * Because "staff only" and "the people who may reach the build world" are different groups on every
 * server that has both. With a boolean the second has to be expressed as the first, and then every
 * builder has to be made staff to reach the build world — which is a permissions problem created by
 * a data model.
 *
 * <h2>Why it is nothing but a permission node underneath</h2>
 * RainsCore already keeps one permission on a place, and all three of these are questions about a
 * permission. A second tag saying "this one is staff" would be two things to keep in step, and when
 * they disagreed the winner would decide whether the staff warp is open to the whole server.
 *
 * <p>So: no node at all is {@link #EVERYONE}, the one well-known node is {@link #STAFF}, and any
 * other node is {@link Needing} that node. Nothing else is stored and nothing can drift.
 *
 * <h2>What this is not</h2>
 * It is not the whole answer to "may they use this warp". An admin is let past whatever this says —
 * somebody has to be able to fix a warp they cannot use — and that lives in {@code WarpAccessRule},
 * where it can be seen beside the rest of the decision rather than hidden in a value type.
 */
public sealed interface WarpAccess {

    /**
     * The node that means "the staff".
     *
     * <p>One well-known node rather than a flag, so that a server's existing staff group already
     * grants every staff warp at once.
     */
    String STAFF_PERMISSION = "rainswarps.warp.staff";

    /** What a warp's own node is prefixed with. */
    String OWN_PERMISSION_PREFIX = "rainswarps.warp.";

    /** Anybody, including somebody who has just joined. */
    WarpAccess EVERYONE = new Everyone();

    /** Whoever holds {@link #STAFF_PERMISSION}. */
    WarpAccess STAFF = new Staff();

    /** Anybody at all. */
    record Everyone() implements WarpAccess {

        @Override
        public boolean allows(Predicate<String> hasPermission) {
            return true;
        }

        @Override
        public Optional<String> permission() {
            // Stored as nothing, deliberately — not as a node that defaults to true. Such a node
            // works until somebody negates it in a permissions plugin, at which point every public
            // warp on the server quietly becomes a staff warp.
            return Optional.empty();
        }

        @Override
        public String describe() {
            return "Anybody may use it";
        }

        @Override
        public boolean isRestricted() {
            return false;
        }
    }

    /** The staff, as a group. */
    record Staff() implements WarpAccess {

        @Override
        public boolean allows(Predicate<String> hasPermission) {
            return hasPermission != null && hasPermission.test(STAFF_PERMISSION);
        }

        @Override
        public Optional<String> permission() {
            return Optional.of(STAFF_PERMISSION);
        }

        @Override
        public String describe() {
            return "Staff only";
        }

        @Override
        public boolean isRestricted() {
            return true;
        }
    }

    /**
     * Whoever holds this one node.
     *
     * <p>The staff node deliberately does not open one of these. Two groups that overlap are still
     * two groups, and a staff node that opened everything would make this third kind pointless.
     */
    record Needing(String node) implements WarpAccess {

        public Needing {
            if (node == null || node.isBlank()) {
                throw new IllegalArgumentException("A warp that needs a permission needs its name.");
            }
            // Lower-cased on the way in, so that two warps asking for the same node with different
            // capitals are one node rather than two — and so that equality means what it looks like.
            node = node.trim().toLowerCase(Locale.ROOT);
        }

        @Override
        public boolean allows(Predicate<String> hasPermission) {
            return hasPermission != null && hasPermission.test(node);
        }

        /**
         * The node itself.
         *
         * <p>The component is called {@code node} rather than {@code permission} because a record
         * component and an interface method of the same name have to have the same type, and this
         * one is an {@code Optional} on the interface — every other kind of access has no node.
         */
        @Override
        public Optional<String> permission() {
            return Optional.of(node);
        }

        @Override
        public String describe() {
            return "Needs " + node;
        }

        @Override
        public boolean isRestricted() {
            return true;
        }
    }

    /** Whether this player may use a warp with this access. */
    boolean allows(Predicate<String> hasPermission);

    /** The node to write onto the warp, or empty for one anybody may use. */
    Optional<String> permission();

    /** A line for a lore or a chat row. */
    String describe();

    /** Whether it is hidden from somebody who has been granted nothing. */
    boolean isRestricted();

    /**
     * What the node on a warp means.
     *
     * <p>Case-insensitively for the staff node: permissions are lower case by convention rather than
     * by rule, and an admin who typed a capital into the file would otherwise get a warp described
     * as "Needs RainsWarps.Warp.Staff" — which works, and reads as a bug.
     */
    static WarpAccess from(String permissionOnTheWarp) {
        if (permissionOnTheWarp == null || permissionOnTheWarp.isBlank()) {
            return EVERYONE;
        }
        String node = permissionOnTheWarp.trim();
        return node.equalsIgnoreCase(STAFF_PERMISSION) ? STAFF : new Needing(node);
    }

    /**
     * The node a warp gets when an admin asks for "its own permission".
     *
     * <p>Anything that is not a permission character is dropped rather than escaped: a node with a
     * space in it can be granted and will never match, which reads to an admin as a permissions
     * plugin that has stopped working.
     *
     * <p>A name with nothing usable in it falls back to the staff node. Never a bare prefix — that
     * is a node nobody can be granted, so the warp would be reachable by nobody at all and there
     * would be nothing on screen to say why.
     */
    static String ownPermissionFor(String warpName) {
        if (warpName == null) {
            return STAFF_PERMISSION;
        }
        String cleaned = warpName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        return cleaned.isEmpty() ? STAFF_PERMISSION : OWN_PERMISSION_PREFIX + cleaned;
    }
}
