package de.raindancer.modules.mannequin;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.mannequin.claims.ClaimLink;
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
        RainsCore core,

        Supplier<MannequinSettings> settings,
        SettingsStore<MannequinSettings> store,

        MannequinRegistry registry,
        MannequinService mannequins,
        MannequinEquipService equip,
        MannequinRedstoneService redstone,
        MannequinPotionService potion,
        MannequinCombatService combat,
        IMannequinScreensOpener screens,
        /**
         * What this module knows about claims — {@link ClaimLink#NONE} on a server with no claims
         * plugin installed, a real link when there is one. See {@code
         * de.raindancer.modules.mannequin.claims.ClaimIntegration} for why a screen reaches this
         * rather than a {@code ClaimServices} field directly.
         */
        ClaimLink claimLink) {

    public MannequinSettings config() {
        return settings.get();
    }
}
