package de.raindancer.modules.chained.command;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.modules.chained.ChainedServices;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChainCommandNicknameTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final ChainedServices services = mock(ChainedServices.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
    private Database database;
    private Player lilly;
    private Player sam;
    private Player ghost;
    private Player admin;
    private ChainCommand command;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        Nicknames nicknames = new Nicknames(database);
        PlayerTargets.useNicknames(nicknames);
        lilly = player("lillyyxoxo");
        sam = player("Sam");
        ghost = player("Ghost");
        admin = player("Admin");
        doReturn(List.of(lilly, sam, ghost, admin)).when(server).getOnlinePlayers();
        nicknames.remember(lilly.getUniqueId(), "Lilly Pad");
        nicknames.remember(ghost.getUniqueId(), "Boo");
        when(services.server()).thenReturn(server);
        when(services.core().vanish().canSee(any(), any())).thenReturn(true);
        when(services.core().vanish().canSee(admin.getUniqueId(), ghost.getUniqueId())).thenReturn(false);
        when(admin.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        command = new ChainCommand(() -> services);
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
    @DisplayName("/chain pair takes nicknames")
    void pairsByNickname() {
        when(services.config().maxDistance()).thenReturn(10);

        command.execute(source(), new String[]{"pair", "Lilly_Pad", "Sam"});

        verify(services.chain()).pair(lilly.getUniqueId(), sam.getUniqueId(), 10);
    }

    @Test
    @DisplayName("/chain unpair takes a nickname")
    void unpairsByNickname() {
        command.execute(source(), new String[]{"unpair", "Lilly_Pad"});

        verify(services.chain()).unpair(lilly.getUniqueId());
    }

    @Test
    @DisplayName("an unknown name unpairs nothing")
    void unknown() {
        command.execute(source(), new String[]{"unpair", "nobody_here"});

        verify(services.chain(), never()).unpair(any());
    }

    @Test
    @DisplayName("completion offers nicknames, hides the vanished by name and nickname, and offers no selector")
    void completes() {
        assertThat(command.suggest(source(), new String[]{"pair", "Lilly_"})).containsExactly("Lilly_Pad");
        assertThat(command.suggest(source(), new String[]{"pair", "Boo"})).isEmpty();
        assertThat(command.suggest(source(), new String[]{"pair", "Gh"})).isEmpty();
        assertThat(command.suggest(source(), new String[]{"pair", ""})).noneMatch(name -> name.startsWith("@"));
    }
}
