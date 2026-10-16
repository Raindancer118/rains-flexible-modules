package de.raindancer.modules.voicebridge.service;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("voice groups a game asks for")
class TeamVoiceTest {

    private final UUID alex = UUID.randomUUID();
    private final UUID sam = UUID.randomUUID();
    private VoicechatServerApi api;
    private Group.Builder builder;
    private Group built;
    private TeamVoice voice;
    private boolean ready = true;

    @BeforeEach
    void setUp() {
        api = mock(VoicechatServerApi.class);
        builder = mock(Group.Builder.class, Answers.RETURNS_SELF);
        built = mock(Group.class);
        when(built.getId()).thenReturn(UUID.randomUUID());
        when(builder.build()).thenReturn(built);
        when(api.groupBuilder()).thenReturn(builder);
        voice = new TeamVoice(() -> ready ? Optional.of(api) : Optional.empty());
    }

    private VoicechatConnection connection(UUID player, Group in) {
        VoicechatConnection connection = mock(VoicechatConnection.class);
        when(connection.getGroup()).thenReturn(in);
        when(connection.isInstalled()).thenReturn(true);
        when(api.getConnectionOf(player)).thenReturn(connection);
        return connection;
    }

    @Test
    @DisplayName("everybody asked for is put into one hidden group of that name")
    void places() {
        VoicechatConnection a = connection(alex, null);
        VoicechatConnection s = connection(sam, null);

        voice.place("ctf:red", "Red team", List.of(alex, sam));

        verify(builder).setName("Red team");
        verify(builder).setHidden(true);
        verify(builder, times(1)).build();
        verify(a).setGroup(built);
        verify(s).setGroup(built);
    }

    @Test
    @DisplayName("asked again with the same key, the same group is used, not a second one")
    void sameKey() {
        connection(alex, null);
        voice.place("ctf:red", "Red team", List.of(alex));
        when(api.getGroups()).thenReturn(List.of(built));
        VoicechatConnection s = connection(sam, null);

        voice.place("ctf:red", "Red team", List.of(sam));

        verify(builder, times(1)).build();
        verify(s).setGroup(built);
    }

    @Test
    @DisplayName("a player without the voice client is left out")
    void noClient() {
        voice.place("ctf:red", "Red team", List.of(alex));
        verify(builder, never()).build();
    }

    @Test
    @DisplayName("disbanding lets out only those still in that group")
    void disband() {
        VoicechatConnection a = connection(alex, null);
        voice.place("ctf:red", "Red team", List.of(alex));
        when(a.getGroup()).thenReturn(built);
        Group other = mock(Group.class);
        when(other.getId()).thenReturn(UUID.randomUUID());
        VoicechatConnection s = connection(sam, other);

        voice.disband("ctf:red");

        verify(a).setGroup(null);
        verify(s, never()).setGroup(any());
    }

    @Test
    @DisplayName("without the voice plugin nothing happens")
    void notReady() {
        ready = false;
        voice.place("ctf:red", "Red team", List.of(alex));
        voice.disband("ctf:red");
        verify(builder, never()).setName(anyString());
    }
}
