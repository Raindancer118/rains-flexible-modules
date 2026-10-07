package de.raindancer.modules.warp.model;

import de.raindancer.core.world.poi.Poi;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A warp: somewhere with a name that a player can be sent to.
 *
 * <h2>Why this wraps a {@link Poi} rather than replacing it</h2>
 * A warp <em>is</em> a place with a name, a world and coordinates — which is what a POI is, down to
 * the "the world is a name, not a World" reasoning and the handling of a world that is not loaded.
 * Writing that twice would mean two answers to every one of those questions and two files to keep in
 * step. So the place is the POI and this adds only what a warp actually has on top of one: who may
 * use it and what it is filed under.
 *
 * <p>The practical payoff is that everything which already understands places understands warps: a
 * ghast line can fly to one, a menu can list them beside homes, and deleting a world takes its warps
 * with it — none of which would work if a warp were a second store that happened to look the same.
 * {@link de.raindancer.core.world.poi.PoiStore} is Core's, shared with every module that stores a
 * named place; the meaning of a warp specifically is this module's.
 */
public record Warp(Poi poi) {

    /** Where a warp's permission is kept on the underlying place. */
    public static final String TAG_PERMISSION = "permission";
    /** Where its category is kept. */
    public static final String TAG_CATEGORY = "category";
    /** The people its owner added to a private warp, as comma-separated UUIDs. */
    public static final String TAG_MEMBERS = "members";

    public String name() {
        return poi.name();
    }

    public String world() {
        return poi.world();
    }

    /** What a menu shows: the label its creator gave it, or its name. */
    public String label() {
        return poi.label();
    }

    /** The permission needed to use it, or empty when anybody may. */
    public Optional<String> permission() {
        return poi.tag(TAG_PERMISSION);
    }

    /** What it is filed under, for a menu that groups them. */
    public Optional<String> category() {
        return poi.tag(TAG_CATEGORY);
    }

    /** Who it belongs to: whoever set it, or whoever staff gave it to. Empty for a warp nobody owns. */
    public Optional<UUID> owner() {
        return Optional.ofNullable(poi.owner());
    }

    /** The people its owner added. A name that is not a UUID is skipped rather than failing the warp. */
    public Set<UUID> members() {
        Set<UUID> members = new LinkedHashSet<>();
        for (String written : poi.tag(TAG_MEMBERS).orElse("").split(",")) {
            if (written.isBlank()) {
                continue;
            }
            try {
                members.add(UUID.fromString(written.trim()));
            } catch (IllegalArgumentException notAUuid) {
                // Hand-edited into nonsense. One bad entry must not take the warp's other people with it.
            }
        }
        return Set.copyOf(members);
    }

    /** Whether the world it is in is loaded right now. */
    public boolean isReachable() {
        return poi.isReachable();
    }

    public String coordinates() {
        return poi.coordinates();
    }
}
