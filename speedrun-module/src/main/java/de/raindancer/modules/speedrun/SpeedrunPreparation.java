package de.raindancer.modules.speedrun;

import de.raindancer.core.moderation.players.PlayerAdmin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Puts every racer, and the map itself, back to a standard starting point the instant a run begins —
 * full hearts, a full hunger bar, morning, nothing hostile standing around and nothing lying on the
 * ground from whatever happened the last time the world was used.
 *
 * <h2>Why this runs on every start, not only after a regeneration</h2>
 * Core's {@code WorldRegenerator} already hands back a pristine world once a finished run's last participant
 * leaves — but a {@code PAUSED} run that gets resumed, or a lobby whose owner set the goal without a
 * run ever finishing first, never goes through that. A day/night cycle keeps advancing and mobs keep
 * spawning in a lobby world nobody has reset yet, so the only point every run can rely on is the one
 * this hooks: the moment {@link SpeedrunLobby#start} actually starts the clock.
 *
 * <h2>Why participants, not "everybody in the world"</h2>
 * The two per-player halves of the reset — health, hunger, effects, fire — only make sense for
 * somebody about to race. A spectator standing at the edge of the lobby world did not ask to be
 * healed or cured of an effect they walked in with; only {@link SpeedrunSession#participants()} agreed
 * to this.
 *
 * <h2>Why the world half is not scoped to participants</h2>
 * A zombie standing where the race starts, or a stack of somebody else's dropped junk on the finish
 * platform, is a hazard for whoever is racing regardless of who dropped it or who it is standing near
 * — narrowing "hostile mobs" or "items on the ground" to some radius would leave exactly the kind of
 * leftover clutter this exists to remove.
 */
final class SpeedrunPreparation {

    /** A normal morning — chosen over midnight ({@code 0}) so a run never starts in the dark. */
    static final long DAY_START = 1000L;

    /** Handed to {@link #prepare} instead of a time of day, for a host who wants the world's own
     *  clock left exactly where it was — see {@link SpeedrunSettings#timeAtStart()}. */
    static final long LEAVE_THE_TIME_ALONE = -1L;

    /** Full, per {@link org.bukkit.entity.HumanEntity#getSaturation}'s own default on spawn. */
    private static final float FULL_SATURATION = 20f;

    private final PlayerAdmin players;

    SpeedrunPreparation(PlayerAdmin players) {
        this.players = players;
    }

    /** The same, at {@link #DAY_START} — what every caller wanted before the time became a setting. */
    void prepare(World world, Set<UUID> participants) {
        prepare(world, participants, DAY_START);
    }

    /**
     * Resets every participant and clears {@code world} of mobs and dropped items, before the clock
     * starts — and sets the world to {@code timeOfDay}, unless that is
     * {@link #LEAVE_THE_TIME_ALONE}.
     *
     * <p>The time is the one half of this a host can switch off, because it is the one half that is
     * a choice rather than a repair: a run that begins at dusk on purpose is a different game, where
     * a run that begins with somebody on three hearts, or with the last run's zombies still standing
     * on the start line, is simply the last run leaking into this one.
     */
    void prepare(World world, Set<UUID> participants, long timeOfDay) {
        for (UUID id : participants) {
            resetPlayer(id);
        }
        if (world != null) {
            if (timeOfDay != LEAVE_THE_TIME_ALONE) {
                world.setTime(timeOfDay);
            }
            clearHostilesAndItems(world);
        }
    }

    private void resetPlayer(UUID id) {
        players.heal(id);
        players.feed(id);
        players.cure(id);
        players.extinguish(id);
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            online.setSaturation(FULL_SATURATION);
        }
    }

    private void clearHostilesAndItems(World world) {
        for (Entity entity : List.copyOf(world.getEntities())) {
            if (entity instanceof Monster || entity instanceof Item) {
                entity.remove();
            }
        }
    }
}
