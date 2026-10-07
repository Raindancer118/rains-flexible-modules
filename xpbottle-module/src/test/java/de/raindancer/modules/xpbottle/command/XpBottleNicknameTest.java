package de.raindancer.modules.xpbottle.command;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.modules.xpbottle.XpBottleServices;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class XpBottleNicknameTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final XpBottleServices services = mock(XpBottleServices.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
    private Database database;
    private Player lilly;
    private Player admin;
    private XpBottleCommand command;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        Nicknames nicknames = new Nicknames(database);
        PlayerTargets.useNicknames(nicknames);
        lilly = player("lillyyxoxo");
        admin = player("Admin");
        doReturn(List.of(lilly, admin)).when(server).getOnlinePlayers();
        nicknames.remember(lilly.getUniqueId(), "Lilly Pad");
        when(services.server()).thenReturn(server);
        when(services.config().highestTierClamped()).thenReturn(3);
        when(admin.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        command = new XpBottleCommand(() -> services);
    }

    @AfterEach
    void tearDown() {
        PlayerTargets.useNicknames(null);
        database.close();
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
        when(server.getPlayerExact(name)).thenReturn(player);
        when(server.getPlayer(id)).thenReturn(player);
        return player;
    }

    private CommandSourceStack source() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(admin);
        return source;
    }

    @Test
    @DisplayName("/xpbottle give Lilly_Pad forges a bottle for the player with that nickname")
    void givesByNickname() {
        command.execute(source(), new String[]{"give", "Lilly_Pad"});

        verify(services.forge()).siphon(1);
        verify(lilly.getInventory()).addItem(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("nobody by that name forges nothing")
    void unknown() {
        command.execute(source(), new String[]{"give", "nobody_here"});

        verify(services.forge(), never()).siphon(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("tab completion offers the nickname, and selectors to somebody who may use them")
    void completes() {
        assertThat(command.suggest(source(), new String[]{"give", "Lilly_"})).containsExactly("Lilly_Pad");
        assertThat(command.suggest(source(), new String[]{"give", "@"})).contains("@a");
        when(admin.hasPermission("minecraft.command.selector")).thenReturn(false);
        assertThat(command.suggest(source(), new String[]{"give", ""})).noneMatch(name -> name.startsWith("@"));
    }

    @Test
    @DisplayName("@a gives every player a bottle and says how many")
    void givesToEverybody() {
        doReturn(List.<org.bukkit.entity.Entity>of(lilly, admin)).when(server).selectEntities(admin, "@a");

        command.execute(source(), new String[]{"give", "@a", "2"});

        verify(lilly.getInventory()).addItem(org.mockito.ArgumentMatchers.any());
        verify(admin.getInventory()).addItem(org.mockito.ArgumentMatchers.any());
        verify(services.messages()).send(admin, "xpbottle.give.given-many", "tier", "II", "count", "2");
    }

    @Test
    @DisplayName("a selector the sender may not use hands out nothing")
    void selectorRefused() {
        when(admin.hasPermission("minecraft.command.selector")).thenReturn(false);

        command.execute(source(), new String[]{"give", "@a"});

        verify(services.messages()).send(admin, "xpbottle.give.selector-refused", "selector", "@a");
        verify(services.forge(), never()).siphon(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("somebody offline is told apart from a typo, by name and by nickname")
    void offline() {
        org.bukkit.OfflinePlayer sleepy = mock(org.bukkit.OfflinePlayer.class);
        when(sleepy.getName()).thenReturn("Sleepy");
        when(sleepy.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes("Sleepy".getBytes()));
        when(server.getOfflinePlayerIfCached("Sleepy")).thenReturn(sleepy);

        command.execute(source(), new String[]{"give", "Sleepy"});

        verify(services.messages()).send(admin, "xpbottle.give.not-online", "player", "Sleepy");
        verify(services.forge(), never()).siphon(org.mockito.ArgumentMatchers.anyInt());
    }
}
