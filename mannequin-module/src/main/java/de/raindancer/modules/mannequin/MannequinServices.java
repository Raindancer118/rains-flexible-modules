package de.raindancer.modules.mannequin;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.mannequin.service.MannequinCombatService;
import de.raindancer.modules.mannequin.service.MannequinEquipService;
import de.raindancer.modules.mannequin.service.MannequinPotionService;
import de.raindancer.modules.mannequin.service.MannequinRedstoneService;
import de.raindancer.modules.mannequin.service.MannequinService;
import de.raindancer.modules.mannequin.store.MannequinRegistry;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything this module has built, in one place, so a listener, a screen or a command can be
 * handed what it needs. See {@code RtpServices} for why this is a record rather than a god object.
 */
public record MannequinServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Brand brand,
        ActionBars actionBars,

        Supplier<MannequinSettings> settings,
        SettingsStore<MannequinSettings> store,

        MannequinRegistry registry,
        MannequinService mannequins,
        MannequinEquipService equip,
        MannequinRedstoneService redstone,
        MannequinPotionService potion,
        MannequinCombatService combat,
        IMannequinScreensOpener screens) {

    public MannequinSettings config() {
        return settings.get();
    }
}
