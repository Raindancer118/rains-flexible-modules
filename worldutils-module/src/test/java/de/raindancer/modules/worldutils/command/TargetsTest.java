package de.raindancer.modules.worldutils.command;

import de.raindancer.modules.worldutils.WorldUtilsServices;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TargetsTest {

    private final Server server = mock(Server.class);
    private final WorldUtilsServices live = mock(WorldUtilsServices.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
    private final Player admin = player("Admin");
    private final Player lilly = player("Lilly");

    TargetsTest() {
        when(live.server()).thenReturn(server);
        when(admin.hasPermission("others")).thenReturn(true);
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(server.getPlayerExact(name)).thenReturn(player);
        return player;
    }

    @Test
    @DisplayName("@a with the selector node sends everybody it matches")
    void selector() {
        when(admin.hasPermission("minecraft.command.selector")).thenReturn(true);
        doReturn(List.<Entity>of(admin, lilly)).when(server).selectEntities(admin, "@a");

        assertThat(Targets.of(live, admin, new String[]{"x", "@a"}, 1, "others")).hasValue(List.of(admin, lilly));
    }

    @Test
    @DisplayName("a selector without the node is refused out loud, not read as nobody")
    void selectorRefused() {
        assertThat(Targets.of(live, admin, new String[]{"x", "@a"}, 1, "others")).isEmpty();

        verify(live.messages()).send(admin, "worldutils.selector-refused", "value", "@a");
    }

    @Test
    @DisplayName("somebody offline is told apart from a typo")
    void offline() {
        OfflinePlayer sleepy = mock(OfflinePlayer.class);
        when(sleepy.getName()).thenReturn("Sleepy");
        when(sleepy.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes("Sleepy".getBytes()));
        when(server.getOfflinePlayerIfCached("Sleepy")).thenReturn(sleepy);

        assertThat(Targets.of(live, admin, new String[]{"x", "Sleepy"}, 1, "others")).isEmpty();

        verify(live.messages()).send(admin, "worldutils.is-offline", "player", "Sleepy");
    }

    @Test
    @DisplayName("a name nobody has is still nobody")
    void typo() {
        assertThat(Targets.of(live, admin, new String[]{"x", "Nope"}, 1, "others")).isEmpty();

        verify(live.messages()).send(admin, "worldutils.nobody-matched", "value", "Nope");
    }
}
