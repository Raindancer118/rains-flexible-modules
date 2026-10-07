package de.raindancer.modules.claims;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.modules.claims.command.ClaimAdminCommand;
import de.raindancer.modules.claims.command.ClaimCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Naming somebody by nickname in /claim and /claimadmin, and being offered it while typing. */
class PlayerNicknamesInCommandsTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final ClaimServices claims = mock(ClaimServices.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
    private Database database;
    private Player lilly;
    private Player ghost;
    private Player admin;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        Nicknames nicknames = new Nicknames(database);
        PlayerTargets.useNicknames(nicknames);
        lilly = player("lillyyxoxo");
        ghost = player("Ghost");
        admin = player("Admin");
        doReturn(List.of(lilly, ghost, admin)).when(server).getOnlinePlayers();
        nicknames.remember(lilly.getUniqueId(), "Lilly Pad");
        nicknames.remember(ghost.getUniqueId(), "Boo");
        when(claims.server()).thenReturn(server);
        when(claims.core().vanish().canSee(any(), any())).thenReturn(true);
        when(claims.core().vanish().canSee(admin.getUniqueId(), ghost.getUniqueId())).thenReturn(false);
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
        when(player.getUniqueId()).thenReturn(id);
        when(player.getServer()).thenReturn(server);
        when(server.getPlayerExact(name)).thenReturn(player);
        when(server.getPlayer(id)).thenReturn(player);
        return player;
    }

    private CommandSourceStack from(Player sender) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return source;
    }

    @Test
    @DisplayName("/claim ban and /claim trust complete a nickname, and never a selector")
    void claimCompletesNicknames() {
        ClaimCommand command = new ClaimCommand(() -> claims);

        assertThat(command.suggest(from(admin), new String[]{"trust", "Lilly_"})).containsExactly("Lilly_Pad");
        assertThat(command.suggest(from(admin), new String[]{"ban", ""})).noneMatch(name -> name.startsWith("@"));
    }

    @Test
    @DisplayName("a vanished player is completed neither by name nor by nickname for somebody who cannot see them")
    void claimHidesTheVanished() {
        ClaimCommand command = new ClaimCommand(() -> claims);

        assertThat(command.suggest(from(admin), new String[]{"kick", "Boo"})).isEmpty();
        assertThat(command.suggest(from(admin), new String[]{"kick", "Gh"})).isEmpty();
    }

    @Test
    @DisplayName("/claimadmin stick hands the stick to the player with that nickname")
    void adminStickByNickname() {
        when(claims.rights().isServerAdmin(admin)).thenReturn(true);
        ClaimAdminCommand command = new ClaimAdminCommand(() -> claims);

        command.execute(from(admin), new String[]{"stick", "Lilly_Pad"});

        verify(claims.stick()).give(org.mockito.ArgumentMatchers.eq(lilly), any(), any());
    }

    @Test
    @DisplayName("/claimadmin completes nicknames for stick and transfer")
    void adminCompletes() {
        ClaimAdminCommand command = new ClaimAdminCommand(() -> claims);

        assertThat(command.suggest(from(admin), new String[]{"stick", "Lilly_"})).containsExactly("Lilly_Pad");
    }
}
