package de.raindancer.modules.xpbottle;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.xpbottle.service.BottleForge;
import de.raindancer.modules.xpbottle.service.BottlingService;
import de.raindancer.modules.xpbottle.service.SiphonService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything this module has built, in one place, so a listener, a screen or a command can be handed
 * what it needs — a record rather than a god object: it owns nothing, it only carries.
 */
public record XpBottleServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Brand brand,
        RainsCore core,

        Supplier<XpBottleSettings> settings,
        SettingsStore<XpBottleSettings> store,

        BottleForge forge,
        BottlingService bottling,
        SiphonService siphon,
        IXpBottleScreensOpener screens) {

    public XpBottleSettings config() {
        return settings.get();
    }

    public Effects effects() {
        return core.effects();
    }
}
