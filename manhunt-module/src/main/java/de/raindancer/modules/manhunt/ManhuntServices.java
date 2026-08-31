package de.raindancer.modules.manhunt;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.service.ChaosService;
import de.raindancer.modules.manhunt.service.ManhuntAchievements;
import de.raindancer.modules.manhunt.service.ManhuntLobbyListener;
import de.raindancer.modules.manhunt.service.ManhuntService;
import de.raindancer.modules.manhunt.service.ManhuntDeathListener;
import de.raindancer.modules.manhunt.service.ManhuntSpectators;
import de.raindancer.modules.manhunt.service.ManhuntWhitelistService;
import de.raindancer.modules.manhunt.service.TrackerCompassService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything this module has built, in one place — the same "data, not a god object" shape
 * {@code ChainedServices} already documents for itself.
 */
public record ManhuntServices(
        Plugin plugin,
        Server server,
        RainsCore core,
        LogChannel log,
        Messages messages,
        Chat chat,
        Brand brand,

        Supplier<ManhuntSettings> settings,
        SettingsStore<ManhuntSettings> store,

        ManhuntService manhunt,
        ChaosService chaos,
        ManhuntWhitelistService whitelist,
        ManhuntAchievements achievements,
        ManhuntLobbyListener lobbyListener,
        TrackerCompassService tracker,
        ManhuntDeathListener deaths,
        ManhuntSpectators spectators,

        IManhuntScreensOpener screens) {

    public ManhuntSettings config() {
        return settings.get();
    }
}
