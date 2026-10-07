package de.raindancer.modules.invsnap.command;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.modules.invsnap.InvSnapServices;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvSnapCommandNicknameTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final InvSnapServices services = mock(InvSnapServices.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
    private Database database;
    private Player lilly;
    private Player admin;
    private InvSnapCommand command;

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
        command = new InvSnapCommand(() -> services);
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
    @DisplayName("/invsnap Lilly_Pad opens the history of the player with that nickname")
    void opensByNickname() {
        command.execute(source(), new String[]{"Lilly_Pad"});

        verify(services.screens()).history(admin, lilly.getUniqueId(), "lillyyxoxo");
    }

    @Test
    @DisplayName("an unknown name still says so instead of opening anything")
    void unknown() {
        command.execute(source(), new String[]{"nobody_here"});

        verify(services.screens(), never()).history(any(), any(), any());
    }

    @Test
    @DisplayName("tab completion offers the nickname")
    void suggestsNickname() {
        assertThat(command.suggest(source(), new String[]{"Lilly_"})).containsExactly("Lilly_Pad");
    }
}
