package de.raindancer.modules.voicebridge;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.voicebridge.service.BridgeService;
import de.raindancer.modules.voicebridge.service.VoicechatGateway;
import de.raindancer.modules.voicebridge.store.TokenFile;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** Everything this module has built, carried to the listeners, the screen and the command. */
public record VoiceBridgeServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Brand brand,
        RainsCore core,
        Supplier<VoiceBridgeSettings> settings,
        TokenFile tokens,
        BridgeService bridge,
        VoicechatGateway gateway,
        IVoiceBridgeScreensOpener screens) {

    public VoiceBridgeSettings config() {
        return settings.get();
    }

    public Effects effects() {
        return core.effects();
    }
}
