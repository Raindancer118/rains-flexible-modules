package de.raindancer.modules.voicebridge.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.voicebridge.VoiceBridgeSettings;
import de.raindancer.modules.voicebridge.model.BridgeStatus;
import de.raindancer.modules.voicebridge.rules.ConnectReadinessRule;
import de.raindancer.modules.voicebridge.store.TokenFile;
import de.raindancer.modules.voicebridge.util.Pcm;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Joins the two sides: Discord's audio to the group, the group's to Discord, and the chat lines
 * people see as others come and go. Also decides when the bot (re)connects.
 */
public final class BridgeService implements IVoiceBridgeService, DiscordLink.Events, VoicechatGateway.Members {

    private final Plugin plugin;
    private final Server server;
    private final Messages messages;
    private final LogChannel log;
    private final TokenFile tokens;
    private final ConnectReadinessRule readiness;

    private volatile VoiceBridgeSettings settings;
    private volatile VoicechatGateway gateway;
    private volatile DiscordLink discord;
    private volatile boolean stopped;

    public BridgeService(Plugin plugin, Server server, Messages messages, LogChannel log, TokenFile tokens,
                         ConnectReadinessRule readiness, VoiceBridgeSettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.messages = messages;
        this.log = log;
        this.tokens = tokens;
        this.readiness = readiness;
        this.settings = settings;
    }

    /** The two sides need this as their listener, and this needs them; wired once, after both exist. */
    public void wire(VoicechatGateway gateway, DiscordLink discord) {
        this.gateway = gateway;
        this.discord = discord;
    }

    @Override
    public void settings(VoiceBridgeSettings next) {
        VoiceBridgeSettings before = settings;
        settings = next;
        if (before.enabled() != next.enabled() || !Objects.equals(before.target(), next.target())) {
            reconnect();
        }
    }

    /** Re-reads the token and tries Discord again, off the server thread. */
    public void reconnect() {
        if (stopped) {
            return;
        }
        Scheduling.async(plugin, this::connectNow);
    }

    private synchronized void connectNow() {
        if (stopped) {
            return;
        }
        VoiceBridgeSettings live = settings;
        String token;
        try {
            token = tokens.read();
        } catch (RuntimeException unreadable) {
            log.error("Could not read the Discord token file " + tokens.file() + ".", unreadable);
            discord.disconnect();
            discord.idle("voicebridge.not-ready.no-token");
            return;
        }
        Optional<String> refusal = readiness.refusal(live.enabled(), token, live.target());
        if (refusal.isPresent()) {
            discord.disconnect();
            discord.idle(refusal.get());
            if (!live.enabled()) {
                return;
            }
            log.info("The Discord bridge is waiting: {}",
                    PlainTextComponentSerializer.plainText().serialize(messages.get(refusal.get())));
            return;
        }
        discord.connect(token, live.target());
    }

    /** Puts somebody in the bridge group; the answer is the message key that tells them how it went. */
    public String join(UUID player) {
        return switch (gateway.join(player)) {
            case JOINED -> "";
            case ALREADY_IN -> "voicebridge.join.already";
            case NO_VOICECHAT -> "voicebridge.join.no-voicechat";
            default -> "voicebridge.join.not-ready";
        };
    }

    public String leave(UUID player) {
        return switch (gateway.leave(player)) {
            case LEFT -> "";
            case NOT_IN -> "voicebridge.leave.not-in";
            default -> "voicebridge.join.not-ready";
        };
    }

    public BridgeStatus status() {
        DiscordLink link = discord;
        return link == null ? BridgeStatus.off("voicebridge.not-ready.off") : link.status();
    }

    @Override
    public void heard(short[] mono) {
        VoicechatGateway voice = gateway;
        if (voice != null) {
            voice.play(Pcm.gain(mono, settings.discordVolumeClamped()));
        }
    }

    @Override
    public void quiet() {
        VoicechatGateway voice = gateway;
        if (voice != null) {
            voice.quiet();
        }
    }

    @Override
    public void joined(String name) {
        if (settings.announceDiscordJoins()) {
            tellGroup("voicebridge.discord.joined", "name", name);
        }
    }

    @Override
    public void left(String name) {
        if (settings.announceDiscordJoins()) {
            tellGroup("voicebridge.discord.left", "name", name);
        }
    }

    @Override
    public void status(BridgeStatus status) {
        // Read on demand by the page and /voicebridge status; nothing is pushed to players.
    }

    /** Somebody walked into the group: they are told, every time, that Discord can hear them now. */
    @Override
    public void joined(UUID player) {
        BridgeStatus now = status();
        if (now.isConnected()) {
            tell(player, "voicebridge.group.joined", "channel", now.channelName(),
                    "count", String.valueOf(now.discordMembers().size()));
        } else {
            tell(player, "voicebridge.group.joined-offline");
        }
    }

    @Override
    public void left(UUID player) {
        tell(player, "voicebridge.group.left");
    }

    private void tellGroup(String key, Object... values) {
        VoicechatGateway voice = gateway;
        if (voice == null) {
            return;
        }
        for (UUID member : voice.members()) {
            tell(member, key, values);
        }
    }

    private void tell(UUID player, String key, Object... values) {
        Player online = server.getPlayer(player);
        if (online != null) {
            Scheduling.entity(plugin, online, () -> messages.send(online, key, values));
        }
    }

    public void stop() {
        stopped = true;
        DiscordLink link = discord;
        if (link != null) {
            link.disconnect();
        }
    }
}
