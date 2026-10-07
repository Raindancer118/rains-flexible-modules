package de.raindancer.modules.hungergames.service;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.identity.Nicknames;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Gamemaster is granted by account name: a nickname must never stand in for somebody. */
class AccountNamesTest {

    private static final UUID ZED = UUID.nameUUIDFromBytes("zed".getBytes());

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private Database database;
    private Nicknames nicknames;
    private OfflinePlayer zed;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        nicknames = new Nicknames(database);
        PlayerTargets.useNicknames(nicknames);
        zed = mock(OfflinePlayer.class);
        when(zed.getName()).thenReturn("Zed");
        when(zed.getUniqueId()).thenReturn(ZED);
        when(server.getOfflinePlayerIfCached("Zed")).thenReturn(zed);
        when(server.getOfflinePlayer(ZED)).thenReturn(zed);
    }

    @AfterEach
    void tearDown() {
        PlayerTargets.useNicknames(null);
        database.close();
    }

    @Test
    @DisplayName("an account the server has seen, online or not, is that account")
    void realAccount() {
        assertThat(AccountNames.of(server, "Zed").id()).isEqualTo(ZED);
        assertThat(AccountNames.of(server, "Zed").refusal()).isNull();
    }

    @Test
    @DisplayName("a nickname is refused, naming the account to use instead")
    void nicknameRefused() {
        nicknames.remember(ZED, "Zeddy");

        AccountNames who = AccountNames.of(server, "Zeddy");

        assertThat(who.id()).isNull();
        assertThat(who.refusal()).contains("nickname").contains("Zed");
    }

    @Test
    @DisplayName("a real name is not redirected by somebody else's nickname that reads the same")
    void realNameWins() {
        nicknames.remember(UUID.randomUUID(), "Zed");

        assertThat(AccountNames.of(server, "Zed").id()).isEqualTo(ZED);
    }

    @Test
    @DisplayName("a selector is refused, and a name nobody has seen gets the derived id")
    void selectorAndStranger() {
        assertThat(AccountNames.of(server, "@a").refusal()).isNotNull();

        AccountNames stranger = AccountNames.of(server, "Newcomer");
        assertThat(stranger.refusal()).isNull();
        assertThat(stranger.idOrDerived("Newcomer")).isEqualTo(AccountNames.derivedId("Newcomer"))
                .isEqualTo(AccountNames.derivedId("newcomer"));
    }
}
