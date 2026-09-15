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

    /** Opening this module's one screen. An interface so the commands never import a menu class. */
    @FunctionalInterface
    public interface Screens {

        void sides(Player viewer);
    }

    public ManhuntSettings config() {
        return settings.current();
    }
}
