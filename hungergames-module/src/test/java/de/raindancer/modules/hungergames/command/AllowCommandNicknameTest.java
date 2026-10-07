package de.raindancer.modules.hungergames.command;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.modules.hungergames.HungerGamesServices;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import de.raindancer.modules.hungergames.service.AccountNames;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AllowCommandNicknameTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final HungerGamesServices services = mock(HungerGamesServices.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
    private Database database;
    private Nicknames nicknames;
    private Player lilly;
    private Player admin;
    private AllowCommand command;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        nicknames = new Nicknames(database);
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
        when(player.isOnline()).thenReturn(true);
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

    @Test
    @DisplayName("completion offers selectors to who may use them, and offline players always")
    void completesSelectorsAndOffline() {
        OfflinePlayer zed = mock(OfflinePlayer.class);
        when(zed.getName()).thenReturn("Zed");
        when(server.getOfflinePlayers()).thenReturn(new OfflinePlayer[]{zed});
        when(admin.hasPermission("minecraft.command.selector")).thenReturn(true);

        assertThat(command.suggest(source(), new String[]{""})).contains("@a", "Zed");
    }

    private void permitted() {
        when(admin.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(admin.isOp()).thenReturn(true);
    }

    private OfflinePlayer offline(String name) {
        OfflinePlayer away = mock(OfflinePlayer.class);
        when(away.getName()).thenReturn(name);
        when(away.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(("offline:" + name).getBytes()));
        when(server.getOfflinePlayerIfCached(name)).thenReturn(away);
        return away;
    }

    @Test
    @DisplayName("an offline player the server has seen goes on the list under their real UUID")
    void offlineByName() {
        permitted();
        OfflinePlayer zed = offline("Zed");

        command.execute(source(), new String[]{"Zed"});

        verify(services.session()).whitelistAdd(zed.getUniqueId(), "Zed");
    }

    @Test
    @DisplayName("an offline player's nickname adds that player under their real name, never the nickname")
    void offlineByNickname() {
        permitted();
        OfflinePlayer zed = offline("Zed");
        when(server.getOfflinePlayer(zed.getUniqueId())).thenReturn(zed);
        nicknames.remember(zed.getUniqueId(), "Zeddy");

        command.execute(source(), new String[]{"Zeddy"});

        verify(services.session()).whitelistAdd(zed.getUniqueId(), "Zed");
        verify(services.session(), never()).whitelistAdd(any(), eq("Zeddy"));
    }

    @Test
    @DisplayName("a real name wins over somebody else's nickname that reads the same")
    void realNameBeatsNickname() {
        permitted();
        OfflinePlayer zed = offline("Zed");
        nicknames.remember(lilly.getUniqueId(), "Zed");

        command.execute(source(), new String[]{"Zed"});

        verify(services.session()).whitelistAdd(zed.getUniqueId(), "Zed");
        verify(services.session(), never()).whitelistAdd(eq(lilly.getUniqueId()), any());
    }

    @Test
    @DisplayName("a name nobody has seen is kept as given, with the stable derived id")
    void unknownName() {
        permitted();

        command.execute(source(), new String[]{"Newcomer"});

        verify(services.session()).whitelistAdd(AccountNames.derivedId("Newcomer"), "Newcomer");
    }

    @Test
    @DisplayName("a selector adds everybody it matches; one matching nobody adds nothing, not a player called @a")
    void selectors() {
        permitted();
        when(server.selectEntities(admin, "@a")).thenReturn(List.of(lilly, admin));
        when(server.selectEntities(admin, "@r")).thenReturn(List.of());

        command.execute(source(), new String[]{"@a", "@r"});

        verify(services.session()).whitelistAdd(lilly.getUniqueId(), "lillyyxoxo");
        verify(services.session()).whitelistAdd(admin.getUniqueId(), "Admin");
        verify(services.session(), never()).whitelistAdd(any(), eq("@r"));
        verify(services.messages()).send(eq(admin), eq("hungergames.allow-selector-empty"), any(Object[].class));
    }

    @Test
    @DisplayName("a selector the sender may not use adds nobody")
    void selectorRefused() {
        permitted();
        when(admin.hasPermission("minecraft.command.selector")).thenReturn(false);

        command.execute(source(), new String[]{"@a"});

        verify(services.session(), never()).whitelistAdd(any(), any());
        verify(services.messages()).send(eq(admin), eq("hungergames.allow-selector-refused"), any(Object[].class));
    }
}
