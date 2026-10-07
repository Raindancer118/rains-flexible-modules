package de.raindancer.modules.tpa.command;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.modules.tpa.TpaServices;
import de.raindancer.modules.tpa.model.TpaKind;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TpaNicknameTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final TpaServices services = mock(TpaServices.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
    private Database database;
    private Player lilly;
    private Player ghost;
    private Player me;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        Nicknames nicknames = new Nicknames(database);
        PlayerTargets.useNicknames(nicknames);
        lilly = player("lillyyxoxo");
        ghost = player("Ghost");
        me = player("Me");
        doReturn(List.of(lilly, ghost, me)).when(server).getOnlinePlayers();
        nicknames.remember(lilly.getUniqueId(), "Lilly Pad");
        nicknames.remember(ghost.getUniqueId(), "Boo");
        nicknames.remember(me.getUniqueId(), "Myself");
        when(services.server()).thenReturn(server);
        when(services.core().vanish().canSee(any(), any())).thenReturn(true);
        when(services.core().vanish().canSee(me.getUniqueId(), ghost.getUniqueId())).thenReturn(false);
        when(lilly.isOnline()).thenReturn(true);
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
        when(source.getSender()).thenReturn(me);
        return source;
    }

    @Test
    @DisplayName("/tpa Lilly_Pad asks the player who goes by Lilly Pad")
    void asksByNickname() {
        new AskCommand(() -> services, TpaKind.TO).execute(source(), new String[]{"Lilly_Pad"});

        verify(services.asking()).ask(me, lilly, TpaKind.TO);
    }

    @Test
    @DisplayName("/tpa completes nicknames, never your own, never a vanished player's, never a selector")
    void completesForAsking() {
        AskCommand command = new AskCommand(() -> services, TpaKind.TO);

        assertThat(command.suggest(source(), new String[]{"Lilly_"})).containsExactly("Lilly_Pad");
        assertThat(command.suggest(source(), new String[]{"Myself"})).isEmpty();
        assertThat(command.suggest(source(), new String[]{"Boo"})).isEmpty();
        assertThat(command.suggest(source(), new String[]{""})).noneMatch(name -> name.startsWith("@"));
    }

    @Test
    @DisplayName("/tpablock Lilly_Pad blocks the right player")
    void blocksByNickname() {
        new TpaToolsCommand(() -> services, TpaToolsCommand.What.BLOCK)
                .execute(source(), new String[]{"Lilly_Pad"});

        verify(services.prefs()).block(me, lilly);
    }

    @Test
    @DisplayName("/tpaunblock finds somebody on the list by nickname")
    void unblocksByNickname() {
        UUID mine = me.getUniqueId();
        UUID hers = lilly.getUniqueId();
        var blocked = new de.raindancer.modules.tpa.model.TpaPrefs(true, Set.of(hers));
        when(services.prefs().of(mine)).thenReturn(blocked);
        when(services.prefs().nameOf(hers)).thenReturn("lillyyxoxo");
        when(server.getOfflinePlayer(hers)).thenReturn(lilly);

        new TpaToolsCommand(() -> services, TpaToolsCommand.What.UNBLOCK)
                .execute(source(), new String[]{"Lilly_Pad"});

        verify(services.prefs()).unblock(me, lilly);
        verify(services.prefs(), never()).block(any(), any());
    }

    @Test
    @DisplayName("/tpablock completes nicknames")
    void completesBlock() {
        TpaToolsCommand command = new TpaToolsCommand(() -> services, TpaToolsCommand.What.BLOCK);

        assertThat(command.suggest(source(), new String[]{"Lilly_"})).containsExactly("Lilly_Pad");
    }
}
