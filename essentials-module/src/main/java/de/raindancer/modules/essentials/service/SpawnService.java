package de.raindancer.modules.essentials.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.poi.Poi;
import de.raindancer.core.world.poi.PoiStore;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.essentials.EssentialsSettings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * The one place everybody can always get to.
 *
 * <h2>Why this is a {@link Poi} of kind {@code "spawn"} rather than a location in a config file</h2>
 * Because a place is already a solved problem — a world that is not loaded, a coordinate that has to
 * survive a rename, an id that outlives both — and Core's {@link PoiStore} already solves it for
 * homes and warps. A second, simpler store here would be a second copy of "what happens when the
 * world is gone" with its own bugs.
 *
 * <p>Owned by whoever last set it rather than left ownerless: {@link PoiStore#ofKind} does not care
 * who owns a place, so nothing about looking it up depends on that, and knowing who set it is worth
 * keeping for free.
 */
public final class SpawnService implements IEssentialsService {

    public static final String KIND = "spawn";
    private static final String NAME = "spawn";

    private final PoiStore places;
    private final Travel travel;
    private final Messages messages;

    private volatile EssentialsSettings settings;

    public SpawnService(PoiStore places, Travel travel, Messages messages,
                        EssentialsSettings settings) {
        this.places = places;
        this.travel = travel;
        this.messages = messages;
        settings(settings);
    }

    @Override
    public void settings(EssentialsSettings fresh) {
        this.settings = fresh;
    }

    /** Where it is, if anybody has set one yet. */
    public Optional<Poi> spawn() {
        return places.ofKind(KIND).stream().findFirst();
    }

    /**
     * Sets it here, replacing whatever it was.
     *
     * <p>Replaces rather than adding a second: two places of kind {@code spawn} is a lookup that
     * silently answers whichever the store happens to return first.
     */
    public void set(Location where, UUID by) {
        spawn().ifPresent(existing -> places.delete(existing.id()));
        places.save(Poi.Builder.at(NAME, where)
                .kind(KIND)
                .owner(by)
                .icon(Material.RED_BED)
                .build());
        places.flush();
    }

    /** Sends them there, or says why not. */
    public void go(Player who) {
        Optional<Poi> where = spawn();
        if (where.isEmpty()) {
            messages.send(who, "essentials.spawn.not-set");
            return;
        }
        Location destination = where.get().location().orElse(null);
        if (destination == null) {
            messages.send(who, "essentials.spawn.world-gone");
            return;
        }
        travel.go(who, destination, Trip.to("spawn").after(settings.spawnWarmup()), new Arriving());
    }

    private final class Arriving implements TravelWatcher {

        @Override
        public void counting(Player traveller, int secondsLeft, Trip trip) {
            messages.send(traveller, "essentials.spawn.warming-up", "seconds", secondsLeft);
        }

        @Override
        public void arrived(Player traveller, Location destination, Trip trip) {
            messages.send(traveller, "essentials.spawn.arrived");
        }

        @Override
        public void cancelled(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why));
        }

        @Override
        public void refused(Player traveller, TravelReason why, Trip trip) {
            messages.send(traveller, keyFor(why));
        }
    }

    private static String keyFor(TravelReason why) {
        return switch (why) {
            case MOVED -> "essentials.spawn.cancelled.moved";
            case HURT -> "essentials.spawn.cancelled.hurt";
            case ALREADY_TRAVELLING -> "essentials.already-travelling";
            case WORLD_MISSING -> "essentials.spawn.world-gone";
            case NOWHERE_SAFE -> "essentials.spawn.nowhere-safe";
            case COULD_NOT_CHECK -> "essentials.could-not-check";
            case TELEPORT_REFUSED -> "essentials.teleport-refused";
            case CANNOT_SCHEDULE -> "essentials.cannot-schedule";
        };
    }

    @Override
    public String describe() {
        return "the one place everybody can always get to";
    }
}
