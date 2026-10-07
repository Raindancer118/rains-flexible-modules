package de.raindancer.modules.essentials.command;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.command.PlayerLookup;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Who the admin form of /nick aims at: never somebody reached only because of a nickname. */
class NickCommandTargetTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final EssentialsServices live = mock(EssentialsServices.class);
    private final CommandSender admin = mock(CommandSender.class);
    private Database database;
    private Player bobNick;
    private Player steve;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        Nicknames nicknames = new Nicknames(database);
        PlayerTargets.useNicknames(nicknames);
        bobNick = online("Alex");
        steve = online("Steve");
        nicknames.remember(bobNick.getUniqueId(), "Bob");
        when(live.server()).thenReturn(server);
        when(admin.hasPermission(PermissionNodes.NICK_OTHERS)).thenReturn(true);
        when(admin.hasPermission(PlayerTargets.SELECTOR_NODE)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        PlayerTargets.useNicknames(null);
        database.close();
    }

    private Player online(String name) {
        Player player = mock(Player.class);
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(server.getPlayerExact(name)).thenReturn(player);
        when(server.getPlayer(id)).thenReturn(player);
        return player;
    }

    @Test
    @DisplayName("an admin wanting the nickname 'Bob Builder' does not rename whoever is nicknamed Bob")
    void nicknameOnlyNeedsExplicitSubcommand() {
        assertThat(NickCommand.otherPlayer(live, admin, new String[]{"Bob", "Builder"})).isEmpty();

        Optional<PlayerLookup> explicit = NickCommand.otherPlayer(live, admin, new String[]{"Bob", "set", "Builder"});
        assertThat(explicit).isPresent();
        assertThat(explicit.get().matches()).containsExactly(bobNick);
    }

    @Test
    @DisplayName("a real name keeps the short form, and a selector works as the target")
    void realNameAndSelector() {
        assertThat(NickCommand.otherPlayer(live, admin, new String[]{"Steve", "Builder"})).isPresent();

        doReturn(List.<Entity>of(steve)).when(server).selectEntities(any(), anyString());
        assertThat(NickCommand.otherPlayer(live, admin, new String[]{"@p", "Builder"}).orElseThrow().matches())
                .containsExactly(steve);
    }

    @Test
    @DisplayName("without the others node it is never the admin form")
    void needsPermission() {
        when(admin.hasPermission(PermissionNodes.NICK_OTHERS)).thenReturn(false);

        assertThat(NickCommand.otherPlayer(live, admin, new String[]{"Steve", "Builder"})).isEmpty();
    }
}
