package de.raindancer.modules.hungergames.command;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.modules.hungergames.HungerGamesServices;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AllowCommandNicknameTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final HungerGamesServices services = mock(HungerGamesServices.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
    private Database database;
    private Player lilly;
    private Player admin;
    private AllowCommand command;

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
        command = new AllowCommand(() -> services);
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
    @DisplayName("/allow Lilly_Pad whitelists the real player, under their real name")
    void allowsByNickname() {
        when(admin.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(admin.isOp()).thenReturn(true);

        command.execute(source(), new String[]{"Lilly_Pad"});

        verify(services.session()).whitelistAdd(lilly.getUniqueId(), "lillyyxoxo");
    }

    @Test
    @DisplayName("completion offers nicknames of people not yet on the list, and no selector")
    void completes() {
        when(services.session().isWhitelisted(admin.getUniqueId())).thenReturn(true);

        assertThat(command.suggest(source(), new String[]{"Lilly_"})).containsExactly("Lilly_Pad");
        assertThat(command.suggest(source(), new String[]{""})).noneMatch(name -> name.startsWith("@"))
                .doesNotContain("Admin");
    }
}
