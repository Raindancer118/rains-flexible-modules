package de.raindancer.modules.chat.service;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishSink;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.chat.ChatSettings;
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
import static org.mockito.Mockito.when;

/** An @mention, and its tab completion, also answer to a nickname in its typed form. */
class MentionNicknameTest {

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final Vanish vanish = new Vanish(mock(VanishSink.class));
    private final MentionService service =
            new MentionService(server, vanish, mock(Messages.class), ChatSettings.DEFAULTS);
    private Database database;
    private Nicknames nicknames;
    private Player tom;
    private Player lilly;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        nicknames = new Nicknames(database);
        PlayerTargets.useNicknames(nicknames);
        tom = player("Tom");
        lilly = player("lillyyxoxo");
        doReturn(List.of(tom, lilly)).when(server).getOnlinePlayers();
        nicknames.remember(lilly.getUniqueId(), "Lilly Pad");
    }

    @AfterEach
    void tearDown() {
        PlayerTargets.useNicknames(null);
        database.close();
    }

    private Player player(String name) {
        Player who = mock(Player.class);
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        when(who.getUniqueId()).thenReturn(id);
        when(who.getName()).thenReturn(name);
        when(server.getPlayerExact(name)).thenReturn(who);
        when(server.getPlayer(id)).thenReturn(who);
        return who;
    }

    @Test
    @DisplayName("@Lilly_Pad pings the player who goes by Lilly Pad")
    void mentionByNickname() {
        assertThat(service.mentionsIn(tom, "hey @Lilly_Pad, look")).containsExactly(lilly);
        assertThat(service.mentionsIn(tom, "hey @lilly_pad")).containsExactly(lilly);
    }

    @Test
    @DisplayName("naming somebody by real name and by nickname in one line pings them once")
    void onlyOnce() {
        assertThat(service.mentionsIn(tom, "@lillyyxoxo and @Lilly_Pad")).containsExactly(lilly);
    }

    @Test
    @DisplayName("a vanished player's nickname is no mention, just as their name is none")
    void vanishedNicknameIsNoMention() {
        vanish.vanish(lilly.getUniqueId());

        assertThat(service.mentionsIn(tom, "@Lilly_Pad")).isEmpty();
        assertThat(service.candidatesFor(tom, "Lilly")).isEmpty();
    }

    @Test
    @DisplayName("typing @Lil offers the nickname beside the real name")
    void completesNickname() {
        assertThat(service.candidatesFor(tom, "Lilly_")).containsExactly("@Lilly_Pad");
        assertThat(service.candidatesFor(tom, "lillyy")).containsExactly("@lillyyxoxo");
    }

    @Test
    @DisplayName("a name asked for visibly resolves by nickname too")
    void visibleNamedByNickname() {
        assertThat(service.visibleNamed(tom, "Lilly_Pad")).contains(lilly);
    }
}
