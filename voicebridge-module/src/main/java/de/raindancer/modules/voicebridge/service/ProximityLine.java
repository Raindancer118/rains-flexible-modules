package de.raindancer.modules.voicebridge.service;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiolistener.PlayerAudioListener;
import de.maxhenkel.voicechat.api.audiosender.AudioSender;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;
import de.maxhenkel.voicechat.api.packets.LocationalSoundPacket;
import de.maxhenkel.voicechat.api.packets.SoundPacket;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.voicebridge.VoiceBridgeSettings;
import de.raindancer.modules.voicebridge.model.Placement;
import de.raindancer.modules.voicebridge.store.Positions;
import de.raindancer.modules.voicebridge.util.Pcm;
import de.raindancer.modules.voicebridge.util.Spatial;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One Discord user, standing in the world as their player: what they say goes out through Simple
 * Voice Chat as if their client had the mod, and what that player would hear comes back to them as
 * their own stereo mix through their own bot.
 */
public final class ProximityLine {

    /** Silence this long after the last word ends a sentence for SVC. */
    private static final long END_OF_TALKING_MILLIS = 120;

    private final UUID player;
    private final long discordUser;
    private final BotPool.LineBot bot;
    private final VoicechatServerApi api;
    private final Positions positions;
    private final LogChannel log;
    private final PersonalMix mix;
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();

    private volatile VoiceBridgeSettings settings;
    private volatile AudioSender sender;
    private volatile PlayerAudioListener listener;
    private volatile OpusEncoder encoder;
    private volatile String guildId = "";
    private volatile String channelId = "";
    private volatile long lastSpoke;
    private volatile boolean talking;
    private volatile boolean closed;

    public ProximityLine(UUID player, long discordUser, BotPool.LineBot bot, VoicechatServerApi api,
                         Positions positions, LogChannel log, VoiceBridgeSettings settings) {
        this.player = player;
        this.discordUser = discordUser;
        this.bot = bot;
        this.api = api;
        this.positions = positions;
        this.log = log;
        this.settings = settings;
        this.mix = new PersonalMix(settings.prebufferFrames(), settings.bufferFramesClamped(), 10_000,
                System::currentTimeMillis, positions::of);
        this.mix.settings(settings);
    }

    /** Joins the bot to the user's private channel and stands the user up in SVC. */
    public synchronized boolean open(AudioChannel channel) {
        VoicechatConnection connection = api.getConnectionOf(player);
        if (connection == null || connection.isInstalled()) {
            return false;
        }
        // A client without the mod reads as "disconnected" to SVC; this one has a voice now.
        connection.setConnected(true);
        encoder = api.createEncoder(OpusEncoderMode.VOIP);
        sender = api.createAudioSender(connection);
        if (!api.registerAudioSender(sender)) {
            log.warn("Simple Voice Chat refused to let {} speak through Discord.", player);
            close();
            return false;
        }
        listener = api.playerAudioListenerBuilder().setPlayer(player).setPacketListener(this::heard).build();
        api.registerAudioListener(listener);

        guildId = channel.getGuild().getId();
        channelId = channel.getId();
        AudioManager audio = channel.getGuild().getAudioManager();
        audio.setSendingHandler(new ToDiscord());
        audio.setReceivingHandler(new FromDiscord());
        audio.setSelfDeafened(false);
        audio.setSelfMuted(false);
        audio.openAudioConnection(channel);
        return true;
    }

    public void settings(VoiceBridgeSettings next) {
        settings = next;
        mix.settings(next);
    }

    /** Something this player would hear, from Simple Voice Chat's thread. */
    private void heard(SoundPacket packet) {
        if (closed) {
            return;
        }
        UUID source = packet.getChannelId();
        byte[] opus = packet.getOpusEncodedData();
        if (opus.length == 0) {
            OpusDecoder decoder = decoders.get(source);
            if (decoder != null) {
                decoder.resetState();
            }
            return;
        }
        OpusDecoder decoder = decoders.computeIfAbsent(source, ignored -> api.createDecoder());
        mix.offer(source, decoder.decode(opus), placementOf(packet));
    }

    static Placement placementOf(SoundPacket packet) {
        if (packet instanceof EntitySoundPacket entity) {
            return new Placement.Following(entity.getEntityUuid(), entity.getDistance());
        }
        if (packet instanceof LocationalSoundPacket at) {
            return new Placement.At(at.getPosition().getX(), at.getPosition().getY(), at.getPosition().getZ(),
                    at.getDistance());
        }
        return new Placement.Static();
    }

    public UUID player() {
        return player;
    }

    public long discordUser() {
        return discordUser;
    }

    public BotPool.LineBot bot() {
        return bot;
    }

    public String channelId() {
        return channelId;
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (listener != null) {
            api.unregisterAudioListener(listener);
        }
        if (sender != null) {
            api.unregisterAudioSender(sender);
        }
        VoicechatConnection connection = api.getConnectionOf(player);
        if (connection != null && !connection.isInstalled()) {
            connection.setConnected(false);
        }
        if (encoder != null) {
            encoder.close();
        }
        decoders.values().forEach(OpusDecoder::close);
        decoders.clear();
        mix.clear();
        var guild = guildId.isEmpty() ? null : bot.jda().getGuildById(guildId);
        if (guild != null) {
            guild.getAudioManager().setSendingHandler(null);
            guild.getAudioManager().setReceivingHandler(null);
            guild.getAudioManager().closeAudioConnection();
        }
    }

    /** The user's voice, 20 ms at a time, out through SVC as their player. */
    private final class FromDiscord implements AudioReceiveHandler {

        @Override
        public boolean canReceiveUser() {
            return true;
        }

        @Override
        public void handleUserAudio(@NotNull UserAudio audio) {
            AudioSender out = sender;
            OpusEncoder opus = encoder;
            if (closed || out == null || opus == null || audio.getUser().getIdLong() != discordUser) {
                return;
            }
            short[] mono = Pcm.gain(Pcm.stereoBigEndianToMono(audio.getAudioData(1.0)), settings.discordVolumeClamped());
            out.send(opus.encode(mono));
            lastSpoke = System.currentTimeMillis();
            talking = true;
        }
    }

    /** What the player hears, placed around their head, back to the user. */
    private final class ToDiscord implements AudioSendHandler {

        @Override
        public boolean canProvide() {
            // Polled every 20 ms whatever happens, which makes it the clock for "they stopped talking".
            if (talking && System.currentTimeMillis() - lastSpoke > END_OF_TALKING_MILLIS) {
                talking = false;
                AudioSender out = sender;
                OpusEncoder opus = encoder;
                if (out != null) {
                    out.reset();
                }
                if (opus != null) {
                    opus.resetState();
                }
            }
            return !closed && mix.hasAudio();
        }

        @Override
        public ByteBuffer provide20MsAudio() {
            Spatial.Ear ear = positions.of(player).orElse(new Spatial.Ear(0, 0, 0, 0f));
            byte[] stereo = mix.next(ear);
            if (stereo == null) {
                stereo = new byte[Pcm.FRAME_SAMPLES * 4];
            }
            return ByteBuffer.wrap(stereo);
        }
    }
}
