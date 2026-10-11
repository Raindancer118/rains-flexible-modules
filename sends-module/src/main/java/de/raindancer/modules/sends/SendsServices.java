package de.raindancer.modules.sends;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.sends.service.SendService;
import de.raindancer.modules.sends.store.SendBook;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** Everything this module has built, in one place, so a listener, screen or command can be handed what it needs. */
public record SendsServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Brand brand,
        RainsCore core,

        Supplier<SendsSettings> settings,
        SettingsStore<SendsSettings> store,

        SendBook book,
        SendService sends,
        ISendsScreensOpener screens) {

    public SendsSettings config() {
        return settings.get();
    }

    public Effects effects() {
        return core.effects();
    }
}
