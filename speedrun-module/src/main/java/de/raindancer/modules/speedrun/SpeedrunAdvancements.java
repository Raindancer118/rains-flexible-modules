package de.raindancer.modules.speedrun;

import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/**
 * Takes advancements back off a racer — one of them, or the whole book as a run starts.
 *
 * <h2>Why a run clears them at all</h2>
 * An advancement belongs to the player, not to the world, and no world reset touches it. Somebody who
 * has ever earned {@code nether/root} or {@code end/kill_dragon} on this server — in an earlier run,
 * months ago — is simply never granted it again, so
 * {@link org.bukkit.event.player.PlayerAdvancementDoneEvent} never fires for them a second time. That
 * is a broken end condition (see {@code GoalAdvancement}, which is why only the goal used to be
 * cleared) but it is also a different game: a repeat racer starts with the advancement screen already
 * filled in, no toasts, no progress to chase, while the first-timer beside them plays the real thing.
 * Clearing the book is what makes every run the first run.
 *
 * <p>Switchable — {@code SpeedrunSettings.clearAdvancementsOnStart} — because this is a racer's own
 * saved progress being thrown away, which a server that is not a dedicated event server may well not
 * want. On by default, since a lobby whose whole point is repeated runs is the shape this module is
 * installed in.
 *
 * <h2>Folia</h2>
 * A run starts on whatever thread the countdown owned, not on each racer's own, and an advancement is
 * written through the player — so every revoke hops onto that player's own scheduler first, the same
 * as {@code GoalAdvancement}.
 */
public final class SpeedrunAdvancements {

    private SpeedrunAdvancements() {
    }

    /** Clears every advancement on the server off every one of {@code participants} who is online. */
    public static void clearFor(Plugin plugin, Collection<UUID> participants) {
        if (plugin == null || participants == null) {
            return;
        }
        for (UUID id : participants) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;   // offline: nothing of theirs is loaded to write to
            }
            Scheduling.entity(plugin, player, () -> {
                Iterator<Advancement> all = Bukkit.advancementIterator();
                while (all.hasNext()) {
                    revoke(player, all.next());
                }
            });
        }
    }

    /**
     * Takes one advancement back, criterion by criterion — the only way there is; Bukkit has no
     * "un-award this" of its own. An advancement with nothing awarded is not written to at all, which
     * is what keeps clearing the whole book off a fresh racer nearly free.
     */
    public static void revoke(Player player, Advancement advancement) {
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        Set<String> awarded = Set.copyOf(progress.getAwardedCriteria());
        for (String criterion : awarded) {
            progress.revokeCriteria(criterion);
        }
    }
}
