package de.raindancer.modules.voicebridge;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.voicebridge.listener.QuitListener;
import de.raindancer.modules.voicebridge.rules.BridgedSpeakerRule;
import de.raindancer.modules.voicebridge.rules.ConnectReadinessRule;
import de.raindancer.modules.voicebridge.screen.VoiceBridgeRootMenu;
import de.raindancer.modules.voicebridge.service.BridgeService;
import de.raindancer.modules.voicebridge.service.DiscordLink;
import de.raindancer.modules.voicebridge.service.SpeakerMixer;
import de.raindancer.modules.voicebridge.service.VoicechatGateway;
import de.raindancer.modules.voicebridge.store.TokenFile;
import de.raindancer.modules.voicebridge.util.PermissionNodes;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A Discord voice channel and a Simple Voice Chat group, joined into one call.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsVoiceBridge}. One bot sits in one
 * Discord voice channel; everybody in the bridge group hears that channel mixed into one voice, and
 * the channel hears the group mixed the same way. Nobody outside the group is ever sent to Discord —
 * joining the group is the consent, and everybody joining is told so.
 */
public final class VoiceBridgeModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("voicebridge", "Voice Bridge", "0.1.0")
            .describedAs("A Discord voice channel joined to a Simple Voice Chat group.")
            .by("Raindancer118");

    /** A game speaker silent this long is dropped from the mixer. */
    private static final long SPEAKER_IDLE_MILLIS = 10_000;

    private LogChannel log;
    private VoiceBridgeServices services;
    private VoicechatGateway gateway;
    private BridgeService bridge;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        SettingsStore<VoiceBridgeSettings> settings =
                context.settings(VoiceBridgeSettings.class, VoiceBridgeSettings.DEFAULTS);
        VoiceBridgeSettings live = settings.current();

        context.core().messages().defineFrom(
                VoiceBridgeModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        PermissionNodes.register(context.plugin().getServer());

        TokenFile tokens = TokenFile.in(context.dataFolder());
        SpeakerMixer<UUID> toDiscord = new SpeakerMixer<>(live.prebufferFrames(), live.bufferFramesClamped(),
                SPEAKER_IDLE_MILLIS, System::currentTimeMillis);
        bridge = new BridgeService(context.plugin(), context.plugin().getServer(), context.core().messages(),
                log, tokens, new ConnectReadinessRule(), live);
        DiscordLink discord = new DiscordLink(toDiscord, bridge, log, live);
        gateway = new VoicechatGateway(new BridgedSpeakerRule(), toDiscord, discord::isListening,
                () -> onlinePlayers(context), bridge, log, live);
        bridge.wire(gateway, discord);
        gateway.register(context.plugin().getServer());

        settings.onChange(toDiscord::settings);
        settings.onChange(discord::settings);
        settings.onChange(gateway::settings);
        settings.onChange(bridge::settings);

        services = new VoiceBridgeServices(context.plugin(), context.plugin().getServer(),
                log, context.core().messages(), context.chat().brand(), context.core(), settings::current,
                tokens, bridge, gateway, new LiveScreens());
        context.listener(new QuitListener(services));
        VoiceBridgeCommands.ready(services);

        bridge.reconnect();
        log.info("Voice bridge is up; the group is '{}'. Token: {}.", live.groupNameOrDefault(),
                tokens.file());
    }

    private final class LiveScreens implements IVoiceBridgeScreensOpener {

        @Override
        public void root(Player viewer) {
            new VoiceBridgeRootMenu(services, viewer).open();
        }
    }

    private static List<UUID> onlinePlayers(ModuleContext context) {
        List<UUID> online = new ArrayList<>();
        for (Player player : context.plugin().getServer().getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }
        return online;
    }

    @Override
    public List<ModuleCommand> commands() {
        return VoiceBridgeCommands.declared();
    }

    @Override
    public void disable() {
        VoiceBridgeCommands.stopped();
        if (bridge != null) {
            bridge.stop();
        }
        if (gateway != null) {
            gateway.close();
        }
    }
}
