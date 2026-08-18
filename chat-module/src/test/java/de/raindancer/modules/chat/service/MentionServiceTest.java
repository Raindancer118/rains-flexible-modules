package de.raindancer.modules.chat.service;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishSink;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.chat.ChatSettings;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MentionServiceTest {

    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final Vanish vanish = new Vanish(mock(VanishSink.class));
    private final MentionService service =
            new MentionService(server, vanish, messages, ChatSettings.DEFAULTS);

    private Player player(String name) {
        Player who = mock(Player.class);
        when(who.getUniqueId()).thenReturn(UUID.randomUUID());
        when(who.getName()).thenReturn(name);
        when(server.getPlayerExact(name)).thenReturn(who);
        return who;
    }

    @Nested
    @DisplayName("finding mentions")
    class FindingMentions {

        @Test
        @DisplayName("matches an online player named after the @")
        void matchesOnlinePlayer() {
            Player sender = player("Tom");
            Player mentioned = player("Alex");

            List<Player> found = service.mentionsIn(sender, "hey @Alex, look at this");

            assertThat(found).containsExactly(mentioned);
        }

        @Test
        @DisplayName("ignores a name nobody online answers to")
        void ignoresUnknownName() {
            Player sender = player("Tom");

            List<Player> found = service.mentionsIn(sender, "hey @Nobody");

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("does not mention the sender's own name")
        void skipsSelf() {
            Player sender = player("Tom");

            List<Player> found = service.mentionsIn(sender, "@Tom talking to myself");

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("the same name twice is only mentioned once")
        void deduplicates() {
            Player sender = player("Tom");
            Player mentioned = player("Alex");

            List<Player> found = service.mentionsIn(sender, "@Alex @Alex are you there, @Alex?");

            assertThat(found).containsExactly(mentioned);
        }

        @Test
        @DisplayName("several different names are all found, in order")
        void findsSeveral() {
            Player sender = player("Tom");
            Player first = player("Alex");
            Player second = player("Bo");

            List<Player> found = service.mentionsIn(sender, "@Alex and @Bo, both of you");

            assertThat(found).containsExactly(first, second);
        }

        @Test
        @DisplayName("a vanished player is never matched — mentioning them would give them away")
        void skipsVanished() {
            Player sender = player("Tom");
            Player hidden = player("Mod");
            vanish.vanish(hidden.getUniqueId());

            List<Player> found = service.mentionsIn(sender, "@Mod are you there?");

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("staff who may see a vanished player can still mention them")
        void staffCanMentionVanished() {
            Player sender = player("Mod");
            Player hidden = player("Alex");
            vanish.vanish(hidden.getUniqueId());
            vanish.maySeeVanished(sender.getUniqueId(), true);

            List<Player> found = service.mentionsIn(sender, "@Alex are you there?");

            assertThat(found).containsExactly(hidden);
        }

        @Test
        @DisplayName("nothing is found once the feature is switched off")
        void nothingWhenDisabled() {
            ChatSettings off = new ChatSettings("<name>: <message>", true, true, NamedTextColor.WHITE, NamedTextColor.WHITE, false, false, true, 70, 8,
                    true, 0, 0, true, 200, true);
            MentionService disabled = new MentionService(server, vanish, messages, off);
            player("Alex");

            List<Player> found = disabled.mentionsIn(player("Tom"), "@Alex");

            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("tab-completing")
    class TabCompleting {

        @Test
        @DisplayName("suggests online players whose name starts with the partial, as @Name")
        void suggestsMatchingPrefix() {
            Player sender = player("Tom");
            Player alex = player("Alex");
            Player alice = player("Alice");
            Player bo = player("Bo");
            doReturn(Set.of(sender, alex, alice, bo)).when(server).getOnlinePlayers();

            List<String> found = service.candidatesFor(sender, "Al");

            assertThat(found).containsExactlyInAnyOrder("@Alex", "@Alice");
        }

        @Test
        @DisplayName("matching is case-insensitive")
        void caseInsensitive() {
            Player sender = player("Tom");
            Player alex = player("Alex");
            doReturn(Set.of(sender, alex)).when(server).getOnlinePlayers();

            List<String> found = service.candidatesFor(sender, "al");

            assertThat(found).containsExactly("@Alex");
        }

        @Test
        @DisplayName("never suggests the sender's own name")
        void skipsSelf() {
            Player sender = player("Tom");
            doReturn(Set.of(sender)).when(server).getOnlinePlayers();

            List<String> found = service.candidatesFor(sender, "T");

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("never suggests a vanished player staff can't see")
        void skipsVanished() {
            Player sender = player("Tom");
            Player hidden = player("Mod");
            vanish.vanish(hidden.getUniqueId());
            doReturn(Set.of(sender, hidden)).when(server).getOnlinePlayers();

            List<String> found = service.candidatesFor(sender, "M");

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("no suggestions once the feature is switched off")
        void nothingWhenDisabled() {
            ChatSettings off = new ChatSettings("<name>: <message>", true, true, NamedTextColor.WHITE, NamedTextColor.WHITE, false, false, true, 70, 8,
                    true, 0, 0, true, 200, true);
            MentionService disabled = new MentionService(server, vanish, messages, off);
            Player sender = player("Tom");
            player("Alex");
            doReturn(Set.of(sender)).when(server).getOnlinePlayers();

            List<String> found = disabled.candidatesFor(sender, "Al");

            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("notifying")
    class Notifying {

        @Test
        @DisplayName("pings every mentioned player with a sound and a message")
        void pingsMentioned() {
            Player sender = player("Tom");
            Player mentioned = player("Alex");

            service.notifyMentioned(sender, "hey @Alex", List.of(mentioned));

            verify(mentioned).playSound(any(net.kyori.adventure.sound.Sound.class));
            verify(messages).send(mentioned, "chat.mention.pinged", "player", "Tom", "text",
                    "hey @Alex");
        }

        @Test
        @DisplayName("mentioning two people pings both, once each")
        void pingsEveryoneOnce() {
            Player sender = player("Tom");
            Player first = player("Alex");
            Player second = player("Bo");

            service.notifyMentioned(sender, "@Alex @Bo", List.of(first, second));

            verify(first, times(1)).playSound(any(net.kyori.adventure.sound.Sound.class));
            verify(second, times(1)).playSound(any(net.kyori.adventure.sound.Sound.class));
        }
    }
}
