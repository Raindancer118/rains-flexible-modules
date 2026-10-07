package de.raindancer.modules.voicebridge;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.voicebridge.listener.PresenceListener;
import de.raindancer.modules.voicebridge.listener.VoicechatCommandListener;
import de.raindancer.modules.voicebridge.rules.GroupJoinRule;
import de.raindancer.modules.voicebridge.rules.LinkCodeRule;
import de.raindancer.modules.voicebridge.screen.VoiceGroupsMenu;
import de.raindancer.modules.voicebridge.service.BotPool;
import de.raindancer.modules.voicebridge.service.GroupService;
import de.raindancer.modules.voicebridge.service.LinkService;
import de.raindancer.modules.voicebridge.service.LobbyService;
import de.raindancer.modules.voicebridge.service.SvcPasswords;
import de.raindancer.modules.voicebridge.store.LinkStore;
import de.raindancer.modules.voicebridge.store.Positions;
import de.raindancer.modules.voicebridge.util.Spatial;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Server;
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

import java.security.SecureRandom;
import java.time.Duration;
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

    private static final ModuleInfo INFO = ModuleInfo.of("voicebridge", "Voice Bridge", "0.2.0")
            .describedAs("A Discord voice channel joined to a Simple Voice Chat group.")
            .by("Raindancer118");

    /** A game speaker silent this long is dropped from the mixer. */
    private static final long SPEAKER_IDLE_MILLIS = 10_000;

    /** How often heads are re-read for placing voices: a tenth of a second is below what anybody hears. */
    private static final long POSITION_PERIOD_TICKS = 2L;

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
        Server server = context.plugin().getServer();
        SpeakerMixer<UUID> toDiscord = new SpeakerMixer<>(live.prebufferFrames(), live.bufferFramesClamped(),
                SPEAKER_IDLE_MILLIS, System::currentTimeMillis);
        bridge = new BridgeService(context.plugin(), server, context.core().messages(),
                log, tokens, new ConnectReadinessRule(), live);
        DiscordLink discord = new DiscordLink(toDiscord, bridge, log, live);
        gateway = new VoicechatGateway(new BridgedSpeakerRule(), toDiscord, discord::isListening,
                () -> onlinePlayers(server), bridge, log, live);

        LinkService links = new LinkService(new LinkStore(context.dataFolder().resolve("links.yml")),
                new LinkCodeRule(), new SecureRandom(), System::currentTimeMillis);
        Positions positions = new Positions();
        BotPool pool = new BotPool(log);
        LobbyService lobby = new LobbyService(context.plugin(), server, context.core().messages(), log, links, pool,
                gateway::api, positions, live);
        GroupService groups = new GroupService(gateway::api, links::discordOf, new GroupJoinRule(),
                SvcPasswords::passwordOf, () -> onlinePlayers(server),
                (inviter, target, group) -> sendInvite(context, inviter, target, group.getId(), group.getName()),
                System::currentTimeMillis, player -> allowed(server, player, GroupService.SVC_GROUPS_PERMISSION));

        bridge.wire(gateway, discord, pool, lobby);
        gateway.register(server);

        settings.onChange(toDiscord::settings);
        settings.onChange(discord::settings);
        settings.onChange(gateway::settings);
        settings.onChange(lobby::settings);
        settings.onChange(bridge::settings);

        services = new VoiceBridgeServices(context.plugin(), server, log, context.core().messages(),
                context.chat().brand(), context.core(), settings::current, tokens, bridge, gateway, links, groups,
                lobby, new LiveScreens());
        context.listener(new PresenceListener(services));
        context.listener(new VoicechatCommandListener(services));
        VoiceBridgeCommands.ready(services);

        var tracking = Scheduling.globalTimer(context.plugin(), POSITION_PERIOD_TICKS, POSITION_PERIOD_TICKS,
                task -> {
                    for (Player player : server.getOnlinePlayers()) {
                        Scheduling.entity(context.plugin(), player, () -> {
                            Location eye = player.getEyeLocation();
                            positions.put(player.getUniqueId(),
                                    new Spatial.Ear(eye.getX(), eye.getY(), eye.getZ(), eye.getYaw()));
                        });
                    }
                });
        if (tracking != null) {
            context.closeWith(tracking::cancel);
        }

        bridge.reconnect();
        log.info("Voice bridge is up; the group is '{}'. Token: {}.", live.groupNameOrDefault(),
                tokens.file());
    }

    private final class LiveScreens implements IVoiceBridgeScreensOpener {

        @Override
        public void root(Player viewer) {
            new VoiceBridgeRootMenu(services, viewer).open();
        }

        @Override
        public void groups(Player viewer) {
            new VoiceGroupsMenu(services, viewer, null).open();
        }
    }

    /** An invite with a button only the invited player can press, for five minutes. */
    private void sendInvite(ModuleContext context, UUID inviter, UUID target, UUID groupId, String groupName) {
        Player invited = context.plugin().getServer().getPlayer(target);
        Player from = context.plugin().getServer().getPlayer(inviter);
        if (invited == null) {
            return;
        }
        Messages messages = context.core().messages();
        Component button = context.core().buttons().label(messages.raw("voicebridge.groups.accept-button"))
                .tooltip(messages.raw("voicebridge.groups.accept-tooltip"))
                .forOnly(target)
                .expiringIn(Duration.ofMillis(GroupService.INVITE_LIFETIME_MILLIS))
                .does(clicker -> {
                    Player who = context.plugin().getServer().getPlayer(clicker);
                    if (who != null) {
                        String refusal = services.groups().accept(clicker, groupId);
                        messages.send(who, refusal.isEmpty() ? "voicebridge.groups.joined" : refusal);
                    }
                })
                .render();
        Component line = messages.prefixed("voicebridge.groups.invited",
                "player", from == null ? "?" : from.getName(), "group", groupName);
        Scheduling.entity(context.plugin(), invited, () -> invited.sendMessage(line.append(Component.space()).append(button)));
    }

    private static boolean allowed(Server server, UUID player, String node) {
        Player online = server.getPlayer(player);
        return online != null && online.hasPermission(node);
    }

    private static List<UUID> onlinePlayers(Server server) {
        List<UUID> online = new ArrayList<>();
        for (Player player : server.getOnlinePlayers()) {
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
