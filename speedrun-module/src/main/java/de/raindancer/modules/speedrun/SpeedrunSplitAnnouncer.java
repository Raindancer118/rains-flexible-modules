package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.profile.PlayerSwitch;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Says every split out loud, the moment it happens: in chat with how it stands against each
 * viewer's personal best and the record ({@code split-announcements}), and — for a gold split, the
 * fastest anybody ever reached that milestone — with a title and a sound
 * ({@code gold-split-celebration}).
 */
public final class SpeedrunSplitAnnouncer {

    private final SpeedrunToolkit kit;
    private final Supplier<SpeedrunSettings> settings;
    private final SpeedrunSession session;
    private final SpeedrunSplitTracker splits;
    private final Supplier<Collection<UUID>> onlookers;
    private final boolean everySplitATitle;

    /**
     * A player's own "no split titles for me" — kept under Manhunt's old name, so whoever switched
     * Manhunt's milestone titles off before the merge still has them off.
     */
    public static final PlayerSwitch TITLES = new PlayerSwitch("manhunt", "announcements", true);

    SpeedrunSplitAnnouncer(SpeedrunToolkit kit, Supplier<SpeedrunSettings> settings, SpeedrunSession session,
                           SpeedrunSplitTracker splits, Supplier<Collection<UUID>> onlookers,
                           boolean everySplitATitle) {
        this.kit = kit;
        this.settings = settings;
        this.session = session;
        this.splits = splits;
        this.onlookers = onlookers;
        this.everySplitATitle = everySplitATitle;
    }

    void announce(SpeedrunSplitTracker.Split split) {
        SpeedrunSettings current = settings.get();
        if (SpeedrunMilestones.FINISH.id().equals(split.milestone().id())) {
            return;   // the finish has its own announcement, with the result in it
        }
        Set<UUID> viewers = new HashSet<>(session.participants());
        viewers.addAll(onlookers.get());
        String who = nameOf(split.who());
        for (UUID id : viewers) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null) {
                continue;
            }
            SpeedrunComparison comparison = splits.compare(split, id);
            if (current.splitAnnouncements() && kit.messages() != null) {
                Component line = kit.messages().prefixed(who.isEmpty() ? "speedrun.split.reached"
                                : "speedrun.split.reached-by",
                        "milestone", split.milestone().label(),
                        "time", SpeedrunTimerDisplay.plain(split.at()), "player", who);
                Component deltas = comparison.describe();
                viewer.sendMessage(deltas.equals(Component.empty()) ? line : line.append(Component.text("  ")).append(deltas));
            }
            boolean gold = comparison.gold() && current.goldSplitCelebration();
            if ((gold || everySplitATitle) && kit.messages() != null && TITLES.isOn(viewer)) {
                viewer.showTitle(Title.title(
                        kit.messages().get(gold ? "speedrun.split.gold-title" : "speedrun.split.title",
                                "milestone", split.milestone().label()),
                        kit.messages().get(gold ? "speedrun.split.gold-subtitle" : "speedrun.split.subtitle",
                                "milestone", split.milestone().label(), "player", who.isEmpty() ? "Somebody" : who,
                                "time", SpeedrunTimerDisplay.plain(split.at()))));
                if (kit.effects() != null) {
                    kit.effects().play(id, gold ? Cues.EARNED : Cues.NOTIFY);
                }
            }
        }
    }

    private static String nameOf(UUID id) {
        if (id == null) {
            return "";
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(id);
        return player.getName() == null ? "" : player.getName();
    }
}
