package de.raindancer.modules.voicebridge.service;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.raindancer.modules.voicebridge.rules.GroupJoinRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupServiceTest {

    private final UUID alex = UUID.randomUUID();
    private final UUID sam = UUID.randomUUID();
    private final List<UUID> online = new ArrayList<>();
    private final Map<UUID, Long> linked = new HashMap<>();
    private final Map<UUID, String> passwords = new HashMap<>();
    private final List<String> invitesSent = new ArrayList<>();

    private VoicechatServerApi api;
    private Group open;
    private Group locked;
    private GroupService groups;
    private long now = 0;

    @BeforeEach
    void setUp() {
        api = mock(VoicechatServerApi.class);
        open = group("Builders", false, Group.Type.NORMAL);
        locked = group("Secret", true, Group.Type.ISOLATED);
        passwords.put(locked.getId(), "hunter2");
        when(api.getGroups()).thenReturn(List.of(open, locked));

        groups = new GroupService(() -> Optional.of(api), player -> Optional.ofNullable(linked.get(player)),
                new GroupJoinRule(), group -> passwords.get(group.getId()), () -> online,
                (inviter, target, group) -> invitesSent.add(inviter + ">" + target + ":" + group.getName()),
                () -> now);
    }

    private static Group group(String name, boolean password, Group.Type type) {
        Group group = mock(Group.class);
        UUID id = UUID.randomUUID();
        when(group.getId()).thenReturn(id);
        when(group.getName()).thenReturn(name);
        when(group.hasPassword()).thenReturn(password);
        when(group.getType()).thenReturn(type);
        return group;
    }

    private VoicechatConnection connection(UUID player, Group in, boolean mod) {
        VoicechatConnection connection = mock(VoicechatConnection.class);
        when(connection.getGroup()).thenReturn(in);
        when(connection.isInstalled()).thenReturn(mod);
        when(api.getConnectionOf(player)).thenReturn(connection);
        return connection;
    }

    @Test
    @DisplayName("bridged means linked to Discord and playing without the mod")
    void bridged() {
        connection(alex, null, false);
        connection(sam, null, true);
        linked.put(alex, 1L);
        linked.put(sam, 2L);

        assertThat(groups.isBridged(alex)).isTrue();
        assertThat(groups.isBridged(sam)).as("has the mod: SVC's own commands are for them").isFalse();
        assertThat(groups.isBridged(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("the list shows every visible group with who is in it, hidden ones left out")
    void lists() {
        Group hidden = group("Staff", false, Group.Type.NORMAL);
        when(hidden.isHidden()).thenReturn(true);
        when(api.getGroups()).thenReturn(List.of(open, locked, hidden));
        online.add(alex);
        connection(alex, open, true);

        List<GroupService.GroupView> views = groups.groups();

        assertThat(views).extracting(GroupService.GroupView::name).containsExactly("Builders", "Secret");
        assertThat(views.getFirst().members()).containsExactly(alex);
        assertThat(views.get(1).locked()).isTrue();
    }

    @Test
    @DisplayName("never asks SVC's getGroup(id), which answers an unknown id with a hollow group")
    void neverTrustsGetGroup() {
        connection(alex, null, false);

        assertThat(groups.join(alex, UUID.randomUUID().toString(), null)).isEqualTo("voicebridge.groups.no-such-group");
        verify(api, never()).getGroup(any());
    }

    @Test
    @DisplayName("an open group is joined by name")
    void joinsOpen() {
        VoicechatConnection alexes = connection(alex, null, false);

        assertThat(groups.join(alex, "builders", null)).isEmpty();
        verify(alexes).setGroup(open);
    }

    @Test
    @DisplayName("a locked group wants the right password, as in SVC")
    void joinsLocked() {
        VoicechatConnection alexes = connection(alex, null, false);

        assertThat(groups.join(alex, "Secret", null)).isEqualTo("voicebridge.groups.needs-password");
        assertThat(groups.join(alex, "Secret", "wrong")).isEqualTo("voicebridge.groups.wrong-password");
        verify(alexes, never()).setGroup(any());
        assertThat(groups.join(alex, locked.getId().toString(), "\"hunter2\"")).as("by id, quotes as SVC's invite writes them").isEmpty();
        verify(alexes).setGroup(locked);
    }

    @Test
    @DisplayName("an unreadable password keeps a locked group shut rather than open")
    void unreadablePassword() {
        passwords.clear();
        connection(alex, null, false);

        assertThat(groups.join(alex, "Secret", "hunter2")).isEqualTo("voicebridge.groups.cannot-check");
    }

    @Test
    @DisplayName("an unknown group, or a name two groups share, is said so")
    void unknownOrAmbiguous() {
        connection(alex, null, false);
        assertThat(groups.join(alex, "Nope", null)).isEqualTo("voicebridge.groups.no-such-group");

        Group twin = group("Builders", false, Group.Type.NORMAL);
        when(api.getGroups()).thenReturn(List.of(open, twin));
        assertThat(groups.join(alex, "Builders", null)).isEqualTo("voicebridge.groups.ambiguous");
    }

    @Test
    @DisplayName("leaving needs a group to leave")
    void leaves() {
        VoicechatConnection alexes = connection(alex, open, false);
        assertThat(groups.leave(alex)).isEmpty();
        verify(alexes).setGroup(null);

        connection(sam, null, false);
        assertThat(groups.leave(sam)).isEqualTo("voicebridge.groups.not-in-group");
    }

    @Test
    @DisplayName("creating a group puts its creator in it")
    void creates() {
        VoicechatConnection alexes = connection(alex, null, false);
        Group.Builder builder = mock(Group.Builder.class, Answers.RETURNS_SELF);
        Group made = group("Cave", true, Group.Type.NORMAL);
        when(builder.build()).thenReturn(made);
        when(api.groupBuilder()).thenReturn(builder);

        assertThat(groups.create(alex, "Cave", "pw", "normal")).isEmpty();
        verify(builder).setName("Cave");
        verify(builder).setPassword("pw");
        verify(builder).setType(Group.Type.NORMAL);
        verify(alexes).setGroup(made);
    }

    @Test
    @DisplayName("a group name has to be something SVC will show: 1 to 24 characters, no line breaks")
    void namesAreChecked() {
        connection(alex, null, false);

        assertThat(groups.create(alex, "", null, "normal")).isEqualTo("voicebridge.groups.bad-name");
        assertThat(groups.create(alex, "x".repeat(25), null, "normal")).isEqualTo("voicebridge.groups.bad-name");
        assertThat(groups.create(alex, "a\nb", null, "normal")).isEqualTo("voicebridge.groups.bad-name");
        assertThat(groups.create(alex, "Cave", null, "sideways")).isEqualTo("voicebridge.groups.bad-type");
    }

    @Test
    @DisplayName("an invite needs the inviter in a group, and is sent to the target")
    void invites() {
        connection(alex, null, false);
        assertThat(groups.invite(alex, sam)).isEqualTo("voicebridge.groups.not-in-group");

        connection(alex, locked, false);
        assertThat(groups.invite(alex, sam)).isEmpty();
        assertThat(invitesSent).containsExactly(alex + ">" + sam + ":Secret");
    }

    @Test
    @DisplayName("accepting an invite opens a locked group without its password — once")
    void acceptsInvite() {
        connection(alex, locked, false);
        VoicechatConnection sams = connection(sam, null, false);
        groups.invite(alex, sam);

        assertThat(groups.accept(sam, locked.getId())).isEmpty();
        verify(sams).setGroup(locked);
        assertThat(groups.accept(sam, locked.getId())).as("an invite is spent").isEqualTo("voicebridge.groups.no-invite");
    }

    @Test
    @DisplayName("without an invite, accepting is refused — it is not a back door round the password")
    void noInviteNoEntry() {
        VoicechatConnection sams = connection(sam, null, false);

        assertThat(groups.accept(sam, locked.getId())).isEqualTo("voicebridge.groups.no-invite");
        verify(sams, never()).setGroup(any());
    }

    @Test
    @DisplayName("an invite is for the player it was sent to and runs out")
    void invitesAreTargetedAndExpire() {
        connection(alex, locked, false);
        VoicechatConnection bos = connection(UUID.randomUUID(), null, false);
        groups.invite(alex, sam);

        UUID stranger = UUID.randomUUID();
        connection(stranger, null, false);
        assertThat(groups.accept(stranger, locked.getId())).isEqualTo("voicebridge.groups.no-invite");

        now += GroupService.INVITE_LIFETIME_MILLIS;
        connection(sam, null, false);
        assertThat(groups.accept(sam, locked.getId())).isEqualTo("voicebridge.groups.no-invite");
    }

    @Test
    @DisplayName("an invite to a group that has since gone is said so")
    void inviteToAGoneGroup() {
        connection(alex, locked, false);
        connection(sam, null, false);
        groups.invite(alex, sam);
        when(api.getGroups()).thenReturn(List.of(open));

        assertThat(groups.accept(sam, locked.getId())).isEqualTo("voicebridge.groups.no-such-group");
    }
}
