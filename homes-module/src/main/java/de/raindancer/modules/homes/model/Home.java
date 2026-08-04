package de.raindancer.modules.homes.model;

import de.raindancer.core.world.poi.Poi;

import java.util.Optional;
import java.util.UUID;

/**
 * Somewhere of one player's own to come back to.
 *
 * <h2>Why this wraps a place rather than replacing it</h2>
 * A home <em>is</em> a place with a name, a world and coordinates belonging to somebody — which is
 * what a {@link Poi} is, down to the world being a name so that an unloaded world makes a home
 * unreachable rather than lost. Writing that again would mean two answers to every one of those
 * questions and two files to keep in step. So the place is the POI, and this adds only what a home has
 * on top of one: the block it shows as, and who it belongs to by name for whoever reads the store by
 * hand.
 *
 * <p>The practical payoff is that everything which already understands places understands homes: a
 * ghast line can fly to one, a menu can list them beside warps, and deleting a world takes its homes
 * with it.
 */
public record Home(Poi poi) {

    /** Where a home's chosen icon is kept on the underlying place. */
    private static final String TAG_ICON = "icon";

    /** Where the owner's last-known name is kept. */
    private static final String TAG_OWNER_NAME = "owner-name";

    public String name() {
        return poi.name();
    }

    public UUID owner() {
        return poi.owner();
    }

    public String world() {
        return poi.world();
    }

    /**
     * The block chosen for it, or empty for one chosen by its world.
     *
     * <p>A material <em>name</em> rather than a {@code Material}: a home saved on a newer server, or
     * one where a block has been renamed, then degrades to no icon on an older one instead of failing
     * to load at all.
     */
    public Optional<String> icon() {
        return poi.tag(TAG_ICON).filter(icon -> !icon.isBlank());
    }

    public boolean hasIcon() {
        return icon().isPresent();
    }

    /** Who it belongs to, by the name they last had. For a diagnostic, never for a decision. */
    public Optional<String> ownerName() {
        return poi.tag(TAG_OWNER_NAME).filter(named -> !named.isBlank());
    }

    /** Whether the world it is in is loaded right now. */
    public boolean isReachable() {
        return poi.isReachable();
    }

    /** "x, y, z", rounded — the useful part of a place, for a lore line. */
    public String coordinates() {
        return poi.coordinates();
    }

    /** Whether this home is in the world of that name. */
    public boolean isIn(String otherWorld) {
        return otherWorld != null && otherWorld.equals(poi.world());
    }
}
