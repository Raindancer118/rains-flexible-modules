package de.raindancer.modules.manhunt;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.mode.ManhuntMode;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import de.raindancer.modules.manhunt.service.ManhuntWhitelistService;
import de.raindancer.modules.manhunt.service.PositionShare;
import de.raindancer.modules.manhunt.tracker.CompassHandout;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * What every command in this module needs, built once {@code ManhuntModule.enable} has the real
 * things — see {@code SpeedrunAdminServices} for the same shape one module over.
 */
public record ManhuntServices(Messages messages, Brand brand, SettingsStore<ManhuntSettings> settings,
                              ManhuntTeams teams, ManhuntMode mode, ManhuntWhitelistService whitelist,
                              Screens screens,
                              PositionShare share,
                              Compasses compasses) {

    /** Handing somebody a compass they lost — see {@code CompassHandout}. */
    public interface Compasses {

        /** @param kind one compass, or empty for every one {@code target} is owed and missing */
        void give(CommandSender sender, Player target, Optional<CompassHandout.Kind> kind);

        /** {@code /manhunt give all}: everybody in the hunt, and only what was handed out is said. */
        void giveEverybody(CommandSender sender, Optional<CompassHandout.Kind> kind);

        /** Every compass of this module off {@code player} — they have left the hunt. */
        void takeAll(Player player);
    }

    /** Opening this module's screens. An interface so the commands never import a menu class. */
    public interface Screens {

        void sides(Player viewer);

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
