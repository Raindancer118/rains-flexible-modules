package de.raindancer.modules.voicebridge.service;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.voicebridge.VoiceBridgeSettings;
import de.raindancer.modules.voicebridge.model.BridgeStatus;
import de.raindancer.modules.voicebridge.model.DiscordTarget;
import de.raindancer.modules.voicebridge.util.Pcm;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.CombinedAudio;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The Discord side: one bot, in one voice channel, hearing everybody there as one mixed stream and
 * speaking whatever the {@link SpeakerMixer} has for it.
 *
 * <p>{@link #connect} blocks while JDA logs in and must be called off the server thread.
 */
public final class DiscordLink implements IVoiceBridgeService {

    /** What the link reports back. Called on JDA's threads. */
    public interface Events {
        /** 20 ms of everybody in the channel, mixed, mono. */
        void heard(short[] mono);

        /** Nobody in the channel is talking any more. */
        void quiet();

        void joined(String name);

        void left(String name);

        void status(BridgeStatus status);
    }

    private final SpeakerMixer<UUID> fromGame;
    private final Events events;
    private final LogChannel log;

    private volatile VoiceBridgeSettings settings;
    private volatile JDA jda;
    private volatile String guildId = "";
    private volatile String channelId = "";
    private volatile BridgeStatus status = BridgeStatus.off("voicebridge.not-ready.off");

    public DiscordLink(SpeakerMixer<UUID> fromGame, Events events, LogChannel log, VoiceBridgeSettings settings) {
        this.fromGame = fromGame;
        this.events = events;
        this.log = log;
        this.settings = settings;
    }

    @Override
    public void settings(VoiceBridgeSettings settings) {
        this.settings = settings;
    }

    public BridgeStatus status() {
        return status;
    }

    /** Whether game voices should be queued at all — nobody listening means nothing to buffer. */
    public boolean isListening() {
        return status.isConnected();
    }

    public synchronized void connect(String token, DiscordTarget target) {
        disconnect();
        report(BridgeStatus.connecting());
        guildId = target.guildId();
        channelId = target.channelId();
        JDA client;
        try {
            client = JDABuilder.createLight(token, EnumSet.of(GatewayIntent.GUILD_VOICE_STATES))
                    .enableCache(CacheFlag.VOICE_STATE)
                    .setMemberCachePolicy(MemberCachePolicy.VOICE)
                    .setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory()))
                    .addEventListeners(new VoiceStates())
                    .build();
            jda = client;
            client.awaitReady();
        } catch (InvalidTokenException | IllegalArgumentException refused) {
            log.warn("Discord refused the bot token: {}", refused.getMessage());
            shutdown();
            report(BridgeStatus.failed("voicebridge.failed.token"));
            return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            shutdown();
            report(BridgeStatus.off("voicebridge.not-ready.off"));
            return;
        } catch (RuntimeException | LinkageError broken) {
            // LinkageError: JDAVE's native library could not be loaded on this platform.
            log.error("Could not start the Discord bot.", broken);
            shutdown();
            report(BridgeStatus.failed(broken instanceof LinkageError
                    ? "voicebridge.failed.dave" : "voicebridge.failed.discord"));
            return;
        }

        Guild guild = client.getGuildById(target.guildId());
        if (guild == null) {
            log.warn("The bot is not on the Discord server {} — invite it first.", target.guildId());
            report(BridgeStatus.failed("voicebridge.failed.no-server"));
            return;
        }
        AudioChannel channel = guild.getChannelById(AudioChannel.class, target.channelId());
        if (channel == null) {
            log.warn("There is no voice channel {} on {}.", target.channelId(), guild.getName());
            report(BridgeStatus.failed("voicebridge.failed.no-channel"));
            return;
        }
        if (!guild.getSelfMember().hasPermission(channel, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)) {
            log.warn("The bot may not connect and speak in {}.", channel.getName());
            report(BridgeStatus.failed("voicebridge.failed.no-permission"));
            return;
        }

        AudioManager audio = guild.getAudioManager();
        audio.setSendingHandler(new Speaker());
        audio.setReceivingHandler(new Listener());
        audio.setConnectionListener(new Watcher());
        audio.setSelfDeafened(false);
        audio.setSelfMuted(false);
        audio.setAutoReconnect(true);
        audio.openAudioConnection(channel);
    }

    public synchronized void disconnect() {
        shutdown();
        fromGame.clear();
        report(BridgeStatus.off("voicebridge.not-ready.off"));
    }

    /** For an explanation that is not a failure: switched off, no token yet. */
    public void idle(String why) {
        report(BridgeStatus.off(why));
    }

    private void shutdown() {
        JDA client = jda;
        jda = null;
        if (client == null) {
            return;
        }
        Guild guild = guildId.isEmpty() ? null : client.getGuildById(guildId);
        if (guild != null) {
            guild.getAudioManager().closeAudioConnection();
        }
        client.shutdown();
        try {
            if (!client.awaitShutdown(5, TimeUnit.SECONDS)) {
                client.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            client.shutdownNow();
        }
    }

    private void report(BridgeStatus next) {
        status = next;
        events.status(next);
    }

    private BridgeStatus connectedNow() {
        JDA client = jda;
        Guild guild = client == null ? null : client.getGuildById(guildId);
        AudioChannel channel = guild == null ? null : guild.getAudioManager().getConnectedChannel();
        if (channel == null) {
            return BridgeStatus.connecting();
        }
        List<String> names = channel.getMembers().stream()
                .filter(member -> !member.equals(guild.getSelfMember()))
                .map(Member::getEffectiveName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return new BridgeStatus(BridgeStatus.Phase.CONNECTED, "", channel.getName(), names);
    }

    private final class Speaker implements AudioSendHandler {

        @Override
        public boolean canProvide() {
            return fromGame.hasAudio();
        }

        @Override
        public ByteBuffer provide20MsAudio() {
            short[] mixed = fromGame.next();
            if (mixed == null) {
                mixed = new short[Pcm.FRAME_SAMPLES];
            }
            return ByteBuffer.wrap(Pcm.monoToStereoBigEndian(Pcm.gain(mixed, settings.gameVolumeClamped())));
        }
    }

    private final class Listener implements AudioReceiveHandler {

        @Override
        public boolean canReceiveCombined() {
            return true;
        }

        @Override
        public void handleCombinedAudio(@NotNull CombinedAudio combined) {
            if (combined.getUsers().isEmpty()) {
                events.quiet();
                return;
            }
            events.heard(Pcm.stereoBigEndianToMono(combined.getAudioData(1.0)));
        }
    }

    private final class Watcher implements ConnectionListener {

        @Override
        public void onStatusChange(@NotNull ConnectionStatus change) {
            switch (change) {
                case CONNECTED -> {
                    log.info("In the Discord voice channel.");
                    report(connectedNow());
                }
                case NOT_CONNECTED, SHUTTING_DOWN -> {
                    // Ours to report from disconnect(), not from the echo of it.
                }
                default -> {
                    if (change.name().startsWith("CONNECTING") || change == ConnectionStatus.AUDIO_REGION_CHANGE) {
                        report(BridgeStatus.connecting());
                    } else {
                        log.warn("Discord voice connection: {}", change);
                        report(BridgeStatus.failed(change.shouldReconnect()
                                ? "voicebridge.failed.reconnecting" : "voicebridge.failed.voice"));
                    }
                }
            }
        }
    }

    private final class VoiceStates extends ListenerAdapter {

        @Override
        public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
            if (!event.getGuild().getId().equals(guildId)) {
                return;
            }
            Member member = event.getMember();
            boolean self = member.equals(event.getGuild().getSelfMember());
            boolean joinedOurs = event.getChannelJoined() != null && event.getChannelJoined().getId().equals(currentChannel());
            boolean leftOurs = event.getChannelLeft() != null && event.getChannelLeft().getId().equals(currentChannel());
            if (!self && joinedOurs && !leftOurs) {
                events.joined(member.getEffectiveName());
            } else if (!self && leftOurs && !joinedOurs) {
                events.left(member.getEffectiveName());
            }
            if (status.isConnected() || self) {
                report(connectedNow());
            }
        }

        /** Whichever channel the bot is in now — an admin may have moved it, and that is their call. */
        private String currentChannel() {
            JDA client = jda;
            Guild guild = client == null ? null : client.getGuildById(guildId);
            AudioChannel connected = guild == null ? null : guild.getAudioManager().getConnectedChannel();
            return connected == null ? channelId : connected.getId();
        }
    }
}
