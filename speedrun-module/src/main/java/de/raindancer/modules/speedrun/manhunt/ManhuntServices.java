package de.raindancer.modules.speedrun.manhunt;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.manhunt.mode.ManhuntMode;
import de.raindancer.modules.speedrun.manhunt.model.ManhuntTeams;
import de.raindancer.modules.speedrun.manhunt.service.ManhuntWhitelistService;
import de.raindancer.modules.speedrun.manhunt.service.PositionShare;
import de.raindancer.modules.speedrun.manhunt.setup.HuntDesk;
import de.raindancer.modules.speedrun.manhunt.stats.HuntChronicle;
import de.raindancer.modules.speedrun.manhunt.stats.StatsStore;
import de.raindancer.modules.speedrun.SpeedrunLobby;
import de.raindancer.modules.speedrun.manhunt.tracker.CompassHandout;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What every command in this module needs, built once {@code ManhuntModule.enable} has the real
 * things — see {@code SpeedrunAdminServices} for the same shape one module over.
 */
public record ManhuntServices(Messages messages, Brand brand, SettingsStore<ManhuntSettings> settings,
                              ManhuntTeams teams, ManhuntMode mode, ManhuntWhitelistService whitelist,
                              Screens screens,
                              PositionShare share,
                              Compasses compasses, HuntDesk desk, HuntChronicle chronicle, StatsStore stats,
                              Supplier<SpeedrunLobby> lobby) {

    /** Handing somebody a compass they lost — see {@code CompassHandout}. */
    public interface Compasses {

        /** @param kind one compass, or empty for every one {@code target} is owed and missing */
        void give(CommandSender sender, Player target, Optional<CompassHandout.Kind> kind);

        /** {@code /manhunt give all}: everybody in the hunt, and only what was handed out is said. */
        void giveEverybody(CommandSender sender, Optional<CompassHandout.Kind> kind);

        /** Every compass of this module off {@code player} — they have left the hunt. */
        void takeAll(Player player);
    }

    /**
     * The pages a command can open. {@code HUB} and {@code SIDES} are Manhunt's own; the rest are the
     * lobby's one page for every game — the pre-flight check, the goal, the players' leaderboard, the
     * history of hunts, the setup assistant.
     */
    public enum Page { HUB, SIDES, PREFLIGHT, GOAL, LEADERBOARD, HISTORY, SETUP }

    /** Opening this module's screens. An interface so the commands never import a menu class. */
    public interface Screens {

        void open(Player viewer, Page page);

        /** One player's stats page. */
        void stats(Player viewer, UUID whose);

        /** One past run's summary page, by its number in the history. */
        void summary(Player viewer, int number);

        /** Asks for the time a hunt had reached in chat, then resumes it there — {@code /manhunt resume}. */
        void askResumeTime(Player viewer);

        /**
         * Core's own "are you sure?" page, for the one thing in this module worth asking about: an
         * admin moving somebody between sides in the middle of a hunt.
         *
         * @param consequences what saying yes actually does, a line each, as MiniMessage
         * @param onYes        run on the confirming click
         */
        void confirm(Player viewer, String question, List<String> consequences, Runnable onYes);
    }

    public ManhuntSettings config() {
        return settings.current();
    }
}
