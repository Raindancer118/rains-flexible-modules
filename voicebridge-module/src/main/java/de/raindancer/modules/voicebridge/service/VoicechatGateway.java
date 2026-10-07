package de.raindancer.modules.voicebridge.service;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.voicebridge.VoiceBridgeSettings;
import de.raindancer.modules.voicebridge.rules.BridgedSpeakerRule;
import org.bukkit.Server;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The Simple Voice Chat side: owns the bridge group, hears its members' microphones, and plays
 * Discord to them through one static channel aimed at every member.
 *
 * <p>Every SVC type is confined to this class. The module class is linked during Paper's bootstrap
 * phase, before Simple Voice Chat is on the classpath, so a SVC type in one of its signatures would
 * fail to link right there.
 */
public final class VoicechatGateway implements VoicechatPlugin, IVoiceBridgeService {

    /** What happens to people as they come and go, so the module can tell them. */
    public interface Members {
        void joined(UUID player);

        void left(UUID player);
    }

    public enum JoinResult { JOINED, ALREADY_IN, NOT_IN, LEFT, NO_VOICECHAT, NOT_READY }

    /** At most 16 characters of a-z and _, or Simple Voice Chat refuses it. */
    static final String VOLUME_CATEGORY = "discord_bridge";

    private final BridgedSpeakerRule rule;
    private final SpeakerMixer<UUID> toDiscord;
    private final BooleanSupplier discordListening;
    private final Supplier<Iterable<UUID>> online;
    private final Members members;
    private final LogChannel log;

    private final Map<UUID, VoicechatConnection> inGroup = new ConcurrentHashMap<>();
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();

    private volatile VoiceBridgeSettings settings;
    private volatile VoicechatServerApi api;
    private volatile Group group;
    private volatile StaticAudioChannel channel;
    private volatile OpusEncoder encoder;
    private volatile boolean closed;
    private boolean talking;

    public VoicechatGateway(BridgedSpeakerRule rule, SpeakerMixer<UUID> toDiscord,
                            BooleanSupplier discordListening, Supplier<Iterable<UUID>> online,
                            Members members, LogChannel log, VoiceBridgeSettings settings) {
        this.rule = rule;
        this.toDiscord = toDiscord;
        this.discordListening = discordListening;
        this.online = online;
        this.members = members;
        this.log = log;
        this.settings = settings;
    }

    /** Hands this to Simple Voice Chat; it calls {@link #initialize} straight back if it is running. */
    public void register(Server server) {
        BukkitVoicechatService service = server.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            throw new IllegalStateException("Simple Voice Chat is not installed or not enabled");
        }
        service.registerPlugin(this);
    }

    @Override
    public String getPluginId() {
        return "rainsvoicebridge";
    }

    @Override
    public void initialize(VoicechatApi voicechat) {
        if (voicechat instanceof VoicechatServerApi server) {
            start(server);
        }
    }

    @Override
    public void registerEvents(EventRegistration events) {
        events.registerEvent(VoicechatServerStartedEvent.class, event -> start(event.getVoicechat()));
        events.registerEvent(VoicechatServerStoppedEvent.class, event -> stopVoicechat());
        events.registerEvent(MicrophonePacketEvent.class, this::heard);
        events.registerEvent(JoinGroupEvent.class, this::joining);
        events.registerEvent(LeaveGroupEvent.class, this::leaving);
        events.registerEvent(PlayerDisconnectedEvent.class, event -> gone(event.getPlayerUuid()));
    }

    @Override
    public void settings(VoiceBridgeSettings settings) {
        String before = this.settings.groupNameOrDefault();
        this.settings = settings;
        VoicechatServerApi live = api;
        if (live != null && !closed && !before.equals(settings.groupNameOrDefault())) {
            bindGroup(live);
        }
    }

    /**
     * Sets up once the voice server is actually running. Simple Voice Chat hands its API over in
     * {@link #initialize} before that, when it cannot make a channel yet — so a {@code null} channel
     * means "not yet", and {@link VoicechatServerStartedEvent} brings this back a moment later.
     */
    private synchronized void start(VoicechatServerApi voicechat) {
        if (closed || voicechat == null || api == voicechat) {
            return;
        }
        StaticAudioChannel speaker = voicechat.createStaticAudioChannel(UUID.randomUUID());
        if (speaker == null) {
            return;
        }
        VolumeCategory category = voicechat.volumeCategoryBuilder()
                .setId(VOLUME_CATEGORY)
                .setName("Discord")
                .setDescription("Everybody talking in the Discord voice channel")
                .build();
        voicechat.registerVolumeCategory(category);
        speaker.setCategory(VOLUME_CATEGORY);
        // Targets are exactly the group's members; an isolated group must still hear its own bridge.
        speaker.setBypassGroupIsolation(true);
        channel = speaker;
        encoder = voicechat.createEncoder(OpusEncoderMode.VOIP);
        api = voicechat;
        bindGroup(voicechat);
    }

    /**
     * The bridge's own group: found by an id derived from its name, never by the name alone. A
     * player's group that merely shares the name — possibly behind a password — is somebody's
     * private conversation, and must not be taken over and sent to Discord.
     */
    static UUID groupIdFor(String name) {
        return UUID.nameUUIDFromBytes(("rainsvoicebridge:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void bindGroup(VoicechatServerApi voicechat) {
        String name = settings.groupNameOrDefault();
        UUID id = groupIdFor(name);
        Group found = voicechat.getGroup(id);
        if (found == null) {
            found = voicechat.groupBuilder()
                    .setId(id)
                    .setName(name)
                    .setPersistent(true)
                    .setType(Group.Type.OPEN)
                    .build();
            log.info("Made the voice chat group '{}' for the Discord bridge.", name);
        }
        group = found;

        StaticAudioChannel speaker = channel;
        for (Map.Entry<UUID, VoicechatConnection> was : inGroup.entrySet()) {
            if (speaker != null) {
                speaker.removeTarget(was.getValue());
            }
        }
        inGroup.clear();
        for (UUID player : online.get()) {
            VoicechatConnection connection = voicechat.getConnectionOf(player);
            Group theirs = connection == null ? null : connection.getGroup();
            if (theirs != null && theirs.getId().equals(found.getId())) {
                admit(player, connection);
            }
        }
    }

    private void heard(MicrophonePacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        Group bridge = group;
        if (closed || sender == null || bridge == null) {
            return;
        }
        Group theirs = sender.getGroup();
        if (!rule.bridges(bridge.getId(), theirs == null ? null : theirs.getId())) {
            return;
        }
        UUID player = sender.getPlayer().getUuid();
        byte[] opus = event.getPacket().getOpusEncodedData();
        if (opus.length == 0) {
            // The client's end-of-talking packet; the next word starts from a clean decoder.
            OpusDecoder decoder = decoders.get(player);
            if (decoder != null) {
                decoder.resetState();
            }
            return;
        }
        if (!discordListening.getAsBoolean()) {
            return;
        }
        VoicechatServerApi voicechat = api;
        OpusDecoder decoder = decoders.computeIfAbsent(player, ignored -> voicechat.createDecoder());
        toDiscord.offer(player, decoder.decode(opus));
    }

    private void joining(JoinGroupEvent event) {
        Group bridge = group;
        VoicechatConnection connection = event.getConnection();
        if (closed || bridge == null || connection == null) {
            return;
        }
        UUID player = connection.getPlayer().getUuid();
        if (event.getGroup() != null && event.getGroup().getId().equals(bridge.getId())) {
            if (admit(player, connection)) {
                members.joined(player);
            }
        } else if (dismiss(player)) {
            // Straight from the bridge group into another one: no LeaveGroupEvent for the first.
            members.left(player);
        }
    }

    private void leaving(LeaveGroupEvent event) {
        VoicechatConnection connection = event.getConnection();
        if (closed || connection == null) {
            return;
        }
        UUID player = connection.getPlayer().getUuid();
        if (dismiss(player)) {
            members.left(player);
        }
    }

    private void gone(UUID player) {
        dismiss(player);
    }

    private boolean admit(UUID player, VoicechatConnection connection) {
        StaticAudioChannel speaker = channel;
        if (speaker != null) {
            speaker.addTarget(connection);
        }
        return inGroup.put(player, connection) == null;
    }

    private boolean dismiss(UUID player) {
        VoicechatConnection connection = inGroup.remove(player);
        StaticAudioChannel speaker = channel;
        if (connection != null && speaker != null) {
            speaker.removeTarget(connection);
        }
        forget(player);
        return connection != null;
    }

    /** Drops what is kept about somebody's voice; their membership is SVC's business. */
    public void forget(UUID player) {
        OpusDecoder decoder = decoders.remove(player);
        if (decoder != null) {
            decoder.close();
        }
        toDiscord.forget(player);
    }

    /** Discord, 20 ms of it, to everybody in the group. Called on Discord's receive thread only. */
    public void play(short[] mono) {
        StaticAudioChannel speaker = channel;
        OpusEncoder opus = encoder;
        if (closed || speaker == null || opus == null || inGroup.isEmpty()) {
            return;
        }
        speaker.send(opus.encode(mono));
        talking = true;
    }

    /** Discord went quiet: tell the clients the sentence ended, rather than letting them time out. */
    public void quiet() {
        StaticAudioChannel speaker = channel;
        OpusEncoder opus = encoder;
        if (!talking || speaker == null || opus == null) {
            return;
        }
        talking = false;
        speaker.flush();
        opus.resetState();
    }

    public JoinResult join(UUID player) {
        VoicechatServerApi voicechat = api;
        Group bridge = group;
        if (closed || voicechat == null || bridge == null) {
            return JoinResult.NOT_READY;
        }
        VoicechatConnection connection = voicechat.getConnectionOf(player);
        if (connection == null || !connection.isInstalled()) {
            return JoinResult.NO_VOICECHAT;
        }
        Group theirs = connection.getGroup();
        if (theirs != null && theirs.getId().equals(bridge.getId())) {
            return JoinResult.ALREADY_IN;
        }
        connection.setGroup(bridge);
        return JoinResult.JOINED;
    }

    public JoinResult leave(UUID player) {
        VoicechatServerApi voicechat = api;
        Group bridge = group;
        if (closed || voicechat == null || bridge == null) {
            return JoinResult.NOT_READY;
        }
        VoicechatConnection connection = voicechat.getConnectionOf(player);
        Group theirs = connection == null ? null : connection.getGroup();
        if (theirs == null || !theirs.getId().equals(bridge.getId())) {
            return JoinResult.NOT_IN;
        }
        connection.setGroup(null);
        return JoinResult.LEFT;
    }

    /** Whether this player has Simple Voice Chat running, for greying a button. */
    public boolean hasVoicechat(UUID player) {
        VoicechatServerApi voicechat = api;
        VoicechatConnection connection = voicechat == null ? null : voicechat.getConnectionOf(player);
        return connection != null && connection.isInstalled();
    }

    public boolean isMember(UUID player) {
        return inGroup.containsKey(player);
    }

    public Set<UUID> members() {
        return Set.copyOf(inGroup.keySet());
    }

    public boolean isReady() {
        return !closed && api != null && group != null;
    }

    public String groupName() {
        return settings.groupNameOrDefault();
    }

    private synchronized void stopVoicechat() {
        api = null;
        group = null;
        channel = null;
        encoder = null;
        inGroup.clear();
        decoders.values().forEach(OpusDecoder::close);
        decoders.clear();
    }

    /**
     * Stops listening. Simple Voice Chat has no way to unregister a plugin, so this one stays
     * registered and goes deaf — a module restarted in the same server registers a fresh one.
     */
    public synchronized void close() {
        closed = true;
        StaticAudioChannel speaker = channel;
        if (speaker != null) {
            speaker.clearTargets();
        }
        OpusEncoder opus = encoder;
        if (opus != null) {
            opus.close();
        }
        VoicechatServerApi voicechat = api;
        if (voicechat != null) {
            voicechat.unregisterVolumeCategory(VOLUME_CATEGORY);
        }
        stopVoicechat();
    }
}
