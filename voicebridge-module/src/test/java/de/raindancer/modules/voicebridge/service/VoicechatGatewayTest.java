package de.raindancer.modules.voicebridge.service;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.Event;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.voicebridge.VoiceBridgeSettings;
import de.raindancer.modules.voicebridge.rules.BridgedSpeakerRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoicechatGatewayTest {

    private final UUID alex = UUID.randomUUID();
    private final UUID sam = UUID.randomUUID();
    private final List<UUID> online = new ArrayList<>();
    private final List<String> told = new ArrayList<>();
    private final Map<Class<?>, Consumer<Event>> handlers = new HashMap<>();
    private final AtomicBoolean discordListening = new AtomicBoolean(true);

    private VoicechatServerApi api;
    private StaticAudioChannel channel;
    private OpusEncoder encoder;
    private OpusDecoder decoder;
    private Group bridgeGroup;
    private Group otherGroup;
    private SpeakerMixer<UUID> mixer;
    private VoicechatGateway gateway;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        api = mock(VoicechatServerApi.class);
        channel = mock(StaticAudioChannel.class);
        encoder = mock(OpusEncoder.class);
        decoder = mock(OpusDecoder.class);
        bridgeGroup = group("Discord", VoicechatGateway.groupIdFor("Discord"));
        otherGroup = group("Friends");

        VolumeCategory.Builder category = mock(VolumeCategory.Builder.class, Answers.RETURNS_SELF);
        when(category.build()).thenReturn(mock(VolumeCategory.class));
        when(api.volumeCategoryBuilder()).thenReturn(category);
        when(api.createEncoder(OpusEncoderMode.VOIP)).thenReturn(encoder);
        when(api.createDecoder()).thenReturn(decoder);
        when(api.createStaticAudioChannel(any(UUID.class))).thenReturn(channel);
        when(api.getGroups()).thenReturn(List.of(otherGroup, bridgeGroup));
        when(encoder.encode(any())).thenReturn(new byte[]{9});
        when(decoder.decode(any())).thenReturn(new short[]{1, 2, 3});

        mixer = new SpeakerMixer<>(1, 5, 60_000, () -> 0L);
        gateway = new VoicechatGateway(new BridgedSpeakerRule(), mixer, discordListening::get,
                () -> online, new VoicechatGateway.Members() {
                    @Override
                    public void joined(UUID player) {
                        told.add("joined " + player);
                    }

                    @Override
                    public void left(UUID player) {
                        told.add("left " + player);
                    }
                }, mock(LogChannel.class), VoiceBridgeSettings.DEFAULTS);

        gateway.registerEvents(new EventRegistration() {
            @Override
            public <T extends Event> void registerEvent(Class<T> type, Consumer<T> handler, int priority) {
                handlers.put(type, event -> handler.accept((T) event));
            }
        });
    }

    private static Group group(String name) {
        return group(name, UUID.randomUUID());
    }

    private static Group group(String name, UUID id) {
        Group group = mock(Group.class);
        when(group.getId()).thenReturn(id);
        when(group.getName()).thenReturn(name);
        return group;
    }

    private VoicechatConnection connection(UUID player, Group in) {
        VoicechatConnection connection = mock(VoicechatConnection.class);
        ServerPlayer who = mock(ServerPlayer.class);
        when(who.getUuid()).thenReturn(player);
        when(connection.getPlayer()).thenReturn(who);
        when(connection.getGroup()).thenReturn(in);
        when(connection.isInstalled()).thenReturn(true);
        when(api.getConnectionOf(player)).thenReturn(connection);
        return connection;
    }

    private void speak(VoicechatConnection sender, byte[] opus) {
        MicrophonePacket packet = mock(MicrophonePacket.class);
        when(packet.getOpusEncodedData()).thenReturn(opus);
        MicrophonePacketEvent event = mock(MicrophonePacketEvent.class);
        when(event.getSenderConnection()).thenReturn(sender);
        when(event.getPacket()).thenReturn(packet);
        handlers.get(MicrophonePacketEvent.class).accept(event);
    }

    private void joinEvent(VoicechatConnection connection, Group into) {
        JoinGroupEvent event = mock(JoinGroupEvent.class);
        when(event.getConnection()).thenReturn(connection);
        when(event.getGroup()).thenReturn(into);
        handlers.get(JoinGroupEvent.class).accept(event);
    }

    private void leaveEvent(VoicechatConnection connection) {
        LeaveGroupEvent event = mock(LeaveGroupEvent.class);
        when(event.getConnection()).thenReturn(connection);
        handlers.get(LeaveGroupEvent.class).accept(event);
    }

    @Test
    @DisplayName("handed the API before its voice server runs, it waits for the server-started event")
    void waitsForTheVoiceServer() {
        when(api.createStaticAudioChannel(any(UUID.class))).thenReturn(null);
        gateway.initialize(api);
        assertThat(gateway.isReady()).as("no channel yet, so not ready").isFalse();

        when(api.createStaticAudioChannel(any(UUID.class))).thenReturn(channel);
        VoicechatServerStartedEvent started = mock(VoicechatServerStartedEvent.class);
        when(started.getVoicechat()).thenReturn(api);
        handlers.get(VoicechatServerStartedEvent.class).accept(started);

        assertThat(gateway.isReady()).isTrue();
    }

    @Test
    @DisplayName("a player's own group that happens to share the name is never taken over")
    void neverHijacksAPlayersGroup() {
        Group playersOwn = group("Discord");
        when(playersOwn.hasPassword()).thenReturn(true);
        when(api.getGroups()).thenReturn(List.of(playersOwn));
        Group.Builder builder = mock(Group.Builder.class, Answers.RETURNS_SELF);
        Group made = group("Discord", VoicechatGateway.groupIdFor("Discord"));
        when(builder.build()).thenReturn(made);
        when(api.groupBuilder()).thenReturn(builder);
        online.add(alex);
        connection(alex, playersOwn);

        gateway.initialize(api);

        verify(builder).setId(VoicechatGateway.groupIdFor("Discord"));
        assertThat(gateway.members()).as("the private group's members are not bridged").isEmpty();
        speak(connection(alex, playersOwn), new byte[]{1});
        assertThat(mixer.speakers()).isZero();
        assertThat(gateway.join(sam)).isNotEqualTo(VoicechatGateway.JoinResult.JOINED);
    }

    @Test
    @DisplayName("never asks SVC's getGroup(id), which answers an unknown id with a hollow group, not null")
    void neverTrustsGetGroup() {
        when(api.getGroups()).thenReturn(List.of(otherGroup));
        Group.Builder builder = mock(Group.Builder.class, Answers.RETURNS_SELF);
        when(builder.build()).thenReturn(bridgeGroup);
        when(api.groupBuilder()).thenReturn(builder);

        gateway.initialize(api);

        verify(api, never()).getGroup(any());
        verify(builder).build();
    }

    @Test
    @DisplayName("an existing group of that name is reused rather than a second one made")
    void reusesTheGroup() {
        gateway.initialize(api);

        verify(api, never()).groupBuilder();
        assertThat(gateway.isReady()).isTrue();
    }

    @Test
    @DisplayName("whoever is already in the group at start is a member and hears Discord")
    void picksUpMembersAtStart() {
        online.add(alex);
        online.add(sam);
        VoicechatConnection alexes = connection(alex, bridgeGroup);
        connection(sam, otherGroup);

        gateway.initialize(api);

        assertThat(gateway.members()).containsExactly(alex);
        verify(channel).addTarget(alexes);
    }

    @Test
    @DisplayName("a member's voice goes to Discord")
    void memberIsBridged() {
        gateway.initialize(api);
        VoicechatConnection alexes = connection(alex, bridgeGroup);

        speak(alexes, new byte[]{1});

        assertThat(mixer.buffered(alex)).isEqualTo(1);
    }

    @Test
    @DisplayName("nobody outside the group is ever sent to Discord — not in another group, not in none")
    void othersAreNever() {
        gateway.initialize(api);

        speak(connection(alex, otherGroup), new byte[]{1});
        speak(connection(sam, null), new byte[]{1});

        assertThat(mixer.speakers()).isZero();
        verify(decoder, never()).decode(any());
    }

    @Test
    @DisplayName("with Discord not listening, game voices are not even decoded")
    void nothingBufferedWhileDiscordIsAway() {
        gateway.initialize(api);
        discordListening.set(false);

        speak(connection(alex, bridgeGroup), new byte[]{1});

        assertThat(mixer.speakers()).isZero();
    }

    @Test
    @DisplayName("the client's empty end-of-talking packet resets the decoder and queues nothing")
    void endOfTalking() {
        gateway.initialize(api);
        VoicechatConnection alexes = connection(alex, bridgeGroup);
        speak(alexes, new byte[]{1});

        speak(alexes, new byte[0]);

        verify(decoder).resetState();
        assertThat(mixer.buffered(alex)).isEqualTo(1);
    }

    @Test
    @DisplayName("joining the group makes somebody a target and tells them; leaving undoes both")
    void joinAndLeave() {
        gateway.initialize(api);
        VoicechatConnection alexes = connection(alex, null);

        joinEvent(alexes, bridgeGroup);
        assertThat(gateway.isMember(alex)).isTrue();
        verify(channel).addTarget(alexes);

        leaveEvent(alexes);
        assertThat(gateway.isMember(alex)).isFalse();
        verify(channel).removeTarget(alexes);
        assertThat(told).containsExactly("joined " + alex, "left " + alex);
    }

    @Test
    @DisplayName("going straight from the bridge group into another one counts as leaving it")
    void switchingGroups() {
        gateway.initialize(api);
        VoicechatConnection alexes = connection(alex, null);
        joinEvent(alexes, bridgeGroup);

        joinEvent(alexes, otherGroup);

        assertThat(gateway.isMember(alex)).isFalse();
        assertThat(told).containsExactly("joined " + alex, "left " + alex);
    }

    @Test
    @DisplayName("joining some other group is none of this module's business")
    void otherGroupsAreIgnored() {
        gateway.initialize(api);

        joinEvent(connection(alex, null), otherGroup);

        assertThat(told).isEmpty();
        verify(channel, never()).addTarget(any());
    }

    @Test
    @DisplayName("Discord audio is encoded and sent only while somebody is in the group")
    void playsOnlyToSomebody() {
        gateway.initialize(api);
        gateway.play(new short[960]);
        verify(channel, never()).send(any(byte[].class));

        joinEvent(connection(alex, null), bridgeGroup);
        gateway.play(new short[960]);
        verify(channel).send(new byte[]{9});
    }

    @Test
    @DisplayName("Discord falling quiet flushes the channel once, not on every silent tick")
    void quietFlushesOnce() {
        gateway.initialize(api);
        joinEvent(connection(alex, null), bridgeGroup);
        gateway.play(new short[960]);

        gateway.quiet();
        gateway.quiet();

        verify(channel).flush();
        verify(encoder).resetState();
    }

    @Test
    @DisplayName("join: no mod, already in, joined — each answered")
    void joinAnswers() {
        assertThat(gateway.join(alex)).isEqualTo(VoicechatGateway.JoinResult.NOT_READY);
        gateway.initialize(api);

        assertThat(gateway.join(alex)).isEqualTo(VoicechatGateway.JoinResult.NO_VOICECHAT);

        VoicechatConnection alexes = connection(alex, null);
        assertThat(gateway.join(alex)).isEqualTo(VoicechatGateway.JoinResult.JOINED);
        verify(alexes).setGroup(bridgeGroup);

        connection(sam, bridgeGroup);
        assertThat(gateway.join(sam)).isEqualTo(VoicechatGateway.JoinResult.ALREADY_IN);
    }

    @Test
    @DisplayName("leave only takes somebody out of the bridge group, never out of their own")
    void leaveAnswers() {
        gateway.initialize(api);
        VoicechatConnection friends = connection(alex, otherGroup);

        assertThat(gateway.leave(alex)).isEqualTo(VoicechatGateway.JoinResult.NOT_IN);
        verify(friends, never()).setGroup(any());

        VoicechatConnection bridged = connection(sam, bridgeGroup);
        assertThat(gateway.leave(sam)).isEqualTo(VoicechatGateway.JoinResult.LEFT);
        verify(bridged).setGroup(null);
    }

    @Test
    @DisplayName("once closed it is deaf: SVC cannot unregister it, so it must ignore what still arrives")
    void closedIsDeaf() {
        gateway.initialize(api);
        VoicechatConnection alexes = connection(alex, bridgeGroup);
        gateway.close();

        speak(alexes, new byte[]{1});
        joinEvent(connection(sam, null), bridgeGroup);

        assertThat(mixer.speakers()).isZero();
        assertThat(told).isEmpty();
        verify(api).unregisterVolumeCategory(VoicechatGateway.VOLUME_CATEGORY);
    }
}
