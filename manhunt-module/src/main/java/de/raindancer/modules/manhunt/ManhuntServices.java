package de.raindancer.modules.manhunt;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.mode.ManhuntMode;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import de.raindancer.modules.manhunt.service.ManhuntWhitelistService;
import org.bukkit.entity.Player;

/**
 * What every command in this module needs, built once {@code ManhuntModule.enable} has the real
 * things — see {@code SpeedrunAdminServices} for the same shape one module over.
 */
public record ManhuntServices(Messages messages, Brand brand, SettingsStore<ManhuntSettings> settings,
                              ManhuntTeams teams, ManhuntMode mode, ManhuntWhitelistService whitelist,
                              Screens screens) {

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
        void confirm(Player viewer, String question, java.util.List<String> consequences,
                     Runnable onYes);
    }

    public ManhuntSettings config() {
        return settings.current();
    }
}
