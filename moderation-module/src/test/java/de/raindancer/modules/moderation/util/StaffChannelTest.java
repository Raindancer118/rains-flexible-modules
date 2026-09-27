package de.raindancer.modules.moderation.util;

import de.raindancer.core.ui.chat.ChatChannels;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.service.StaffChatService;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Staff chat is one of Core's chat channels, so /staffchat, /chat staff and RainsChat's channel picker
 * are three ways into the same room rather than two rooms with the same name.
 */
class StaffChannelTest {

    private final Server server = mock(Server.class);
    private final List<String> said = new ArrayList<>();
    private final StaffChannel channel = new StaffChannel(server, (who, what) -> said.add(who + ": " + what));
    private final StaffChatService staffChat = new StaffChatService();

    private final Player mod = player("Mod", true);
    private final Player admin = player("Admin", true);
    private final Player guest = player("Guest", false);

    private Player player(String name, boolean staff) {
        Player who = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(who.getUniqueId()).thenReturn(id);
        when(who.getName()).thenReturn(name);
        when(who.hasPermission(ModerationPermission.STAFF_CHAT.node())).thenReturn(staff);
        when(server.getPlayer(id)).thenReturn(who);
        return who;
    }

    @BeforeEach
    void online() {
        doReturn(List.of(mod, admin, guest)).when(server).getOnlinePlayers();
        ChatChannels.register(channel);
    }

    @AfterEach
    void clean() {
        ChatChannels.unregister(channel);
        for (Player who : List.of(mod, admin, guest)) {
            ChatChannels.forget(who.getUniqueId());
        }
    }

    @Test
    @DisplayName("it is /chat staff")
    void idAndLabel() {
        assertThat(channel.id()).isEqualTo("staff");
        assertThat(ChatChannels.byId("staff")).contains(channel);
    }

    @Test
    @DisplayName("staff hear every other member of staff online, and nobody else")
    void audienceIsTheStaff() {
        assertThat(channel.audienceFor(mod.getUniqueId()))
                .hasValueSatisfying(heard -> assertThat(heard)
                        .containsExactlyInAnyOrder(mod.getUniqueId(), admin.getUniqueId()));
    }

    @Test
    @DisplayName("somebody without the staff chat node has no part in it — not even to talk into it")
    void guestsAreNotStaff() {
        assertThat(channel.audienceFor(guest.getUniqueId())).isEmpty();
        assertThat(ChatChannels.availableTo(guest.getUniqueId())).doesNotContain(channel);
    }

    @Test
    @DisplayName("a line is said by moderation itself — its format, its console record")
    void deliversItself() {
        assertThat(channel.deliver(mod, "hello")).isTrue();

        assertThat(said).containsExactly("Mod: hello");
    }

    @Test
    @DisplayName("/staffchat and /chat staff are the same switch")
    void oneSwitch() {
        assertThat(staffChat.toggle(mod.getUniqueId())).isTrue();
        assertThat(ChatChannels.selected(mod.getUniqueId())).isEqualTo("staff");

        ChatChannels.select(mod.getUniqueId(), ChatChannels.ALL);
        assertThat(staffChat.isTalking(mod.getUniqueId())).isFalse();

        ChatChannels.select(admin.getUniqueId(), "staff");
        assertThat(staffChat.isTalking(admin.getUniqueId())).isTrue();
        assertThat(staffChat.toggle(admin.getUniqueId())).isFalse();
        assertThat(ChatChannels.selected(admin.getUniqueId())).isEqualTo(ChatChannels.ALL);
    }

    @Test
    @DisplayName("turning staff chat on leaves team chat; turning it off goes to everybody, not back to team")
    void staffReplacesAnotherChannel() {
        ChatChannels.select(mod.getUniqueId(), "staff");
        staffChat.stop(mod.getUniqueId());

        assertThat(ChatChannels.selected(mod.getUniqueId())).isEqualTo(ChatChannels.ALL);
        assertThat(staffChat.stop(mod.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("forgetting somebody who left clears staff chat, and leaves any other channel alone")
    void forgetOnlyStaff() {
        ChatChannels.select(mod.getUniqueId(), "staff");
        staffChat.forget(mod.getUniqueId());
        assertThat(staffChat.isTalking(mod.getUniqueId())).isFalse();
    }
}
