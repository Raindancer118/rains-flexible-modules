package de.raindancer.modules.moderation.util;

import de.raindancer.core.world.time.Times;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What Minecraft already knows about a player, read rather than tracked a second time.
 *
 * <h2>Why this reads vanilla's own statistics instead of keeping its own</h2>
 * Playtime, deaths and the rest are counted by the server for every player who has ever joined,
 * forever, survive this module being removed and reinstalled, and answer for somebody who is offline
 * exactly as well as somebody standing in front of you. A second copy kept here would be a second
 * place for the two to disagree, for no gain over asking the one that is already right.
 *
 * <h2>Why {@code PLAY_ONE_MINUTE} despite the name</h2>
 * It is vanilla's own tally of ticks played, counted continuously rather than once a minute — the
 * name is a leftover from what the statistic used to measure, not what it measures now. Bukkit ships
 * it under that name and this reads it under that name, for the same reason it does not try to guess a
 * better one: a plugin renaming a server's own statistic is a plugin inventing a fact.
 */
public final class PlayerStats {

    private PlayerStats() {
    }

    /**
     * A handful of lines for a moderator's page — not everything Minecraft counts, only what is
     * worth a glance before deciding anything about somebody.
     */
    public static List<String> summarize(OfflinePlayer player) {
        List<String> lore = new ArrayList<>();
        if (player == null || !player.hasPlayedBefore()) {
            lore.add("<dark_gray>Has never actually joined.");
            return lore;
        }

        lore.addAll(playtime(player));

        Instant firstJoined = Instant.ofEpochMilli(player.getFirstPlayed());
        lore.add("<gray>First joined <white>" + Times.describe(Duration.between(firstJoined, now()))
                + "</white> ago.");

        if (!player.isOnline()) {
            Instant lastSeen = Instant.ofEpochMilli(player.getLastLogin());
            lore.add("<gray>Last seen <white>" + Times.describe(Duration.between(lastSeen, now()))
                    + "</white> ago.");
        }

        lore.add("<dark_gray>" + player.getStatistic(Statistic.DEATHS) + " death(s), "
                + player.getStatistic(Statistic.MOB_KILLS) + " mob kill(s), "
                + player.getStatistic(Statistic.PLAYER_KILLS) + " player kill(s).");
        return lore;
    }

    /**
     * How long they have played — Core's own count when it has one, which also knows how much of it they were
     * not away; else the game's statistic. What a moderator weighs "full diamond after ten hours?" against.
     */
    public static List<String> playtime(OfflinePlayer player) {
        if (de.raindancer.core.RainsCore.isAvailable()
                && de.raindancer.core.RainsCore.get().playtime().isKnown(player.getUniqueId())) {
            de.raindancer.core.social.presence.Playtime counted = de.raindancer.core.RainsCore.get().playtime();
            return List.of("<gray>Played for <white>" + Times.describe(counted.played(player.getUniqueId())) + "</white>.",
                    "<gray>Not away for <white>" + Times.describe(counted.playedActively(player.getUniqueId()))
                            + "</white> of it.");
        }
        Duration playtime = Duration.ofSeconds(player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L);
        return List.of("<gray>Played for <white>" + Times.describe(playtime) + "</white>.");
    }

    private static Instant now() {
        return Instant.now();
    }
}
