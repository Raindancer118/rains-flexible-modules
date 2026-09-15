package de.raindancer.modules.manhunt.command;

import de.raindancer.core.ui.messages.Messages;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code open}/{@code close} are this module's own words; everything else passes straight through to
 * vanilla's {@code minecraft:whitelist} via {@link Bukkit#dispatchCommand}. A word that is neither —
 * {@code enable}, say, which is nobody's word: not Manhunt's (only open/close) and not vanilla's
 * (on/off) — used to reach the server console as an unhandled {@link CommandException} with a full
 * stack trace, because {@link Bukkit#dispatchCommand} does not catch what a directly-typed command's
 * own top-level dispatcher normally would. This is that path, sealed.
 */
class WhitelistCommandTest {

    @TempDir
    Path directory;

    private FakeServices fake;
    private Messages messages;
    private WhitelistCommand command;
    private CommandSourceStack source;
    private CommandSender sender;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        fake = new FakeServices(directory);
        messages = fake.messages;
        command = new WhitelistCommand(() -> fake.services);
        sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
    }

    @AfterEach
    void closeStatic() {
        if (bukkit != null) {
            bukkit.close();
        }
    }

    @Test
    @DisplayName("a word that is neither Manhunt's nor vanilla's is caught, not thrown at the console")
    void unknownWordIsCaughtCleanly() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.dispatchCommand(sender, "minecraft:whitelist enable"))
                .thenThrow(new CommandException("Incorrect argument for command"));

        command.execute(source, new String[]{"enable"});

        verify(messages).send(eq(sender), anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("a real vanilla word still passes straight through, unchanged")
    void knownVanillaWordStillPassesThrough() {
        bukkit = mockStatic(Bukkit.class);

        command.execute(source, new String[]{"on"});

        bukkit.verify(() -> Bukkit.dispatchCommand(sender, "minecraft:whitelist on"), times(1));
        verify(messages, never()).send(any(CommandSender.class), anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("open and close are this module's own words, never handed to vanilla")
    void openAndCloseAreHandledLocally() {
        bukkit = mockStatic(Bukkit.class);

        command.execute(source, new String[]{"open"});
        command.execute(source, new String[]{"close"});

        bukkit.verify(() -> Bukkit.dispatchCommand(any(CommandSender.class), anyString()), never());
        verify(fake.whitelist).open();
        verify(fake.whitelist).close();
    }

    @Test
    @DisplayName("without this module's own node, open and close do nothing at all")
    void needsThePermission() {
        when(sender.hasPermission(anyString())).thenReturn(false);

        command.execute(source, new String[]{"close"});

        verify(fake.whitelist, never()).close();
        verify(messages).send(eq(sender), eq("manhunt.not-yours"), any(Object[].class));
    }

    /**
     * {@code clear} and {@code vip} — this module's other two words, beside {@code open}/{@code close}.
     * Vanilla has neither: its own {@code /whitelist remove} takes one name at a time, and it has no
     * notion at all of somebody a clear must spare.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("clear and vip")
    class ClearAndVips {

        private static final java.util.UUID ANNA =
                java.util.UUID.nameUUIDFromBytes("anna".getBytes());

        private de.raindancer.modules.manhunt.service.WhitelistVips vipList;

        @BeforeEach
        void stubTheVipList() {
            vipList = mock(de.raindancer.modules.manhunt.service.WhitelistVips.class);
            when(fake.whitelist.vips()).thenReturn(vipList);
        }

        private org.bukkit.entity.Player online(String name) {
            org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
            when(player.getUniqueId()).thenReturn(ANNA);
            when(player.getName()).thenReturn(name);
            return player;
        }

        @Test
        @DisplayName("clear empties the whitelist and says how many went")
        void clearReportsWhatItRemoved() {
            when(fake.whitelist.clear()).thenReturn(3);
            when(vipList.size()).thenReturn(1);

            command.execute(source, new String[]{"clear"});

            verify(fake.whitelist).clear();
            verify(messages).send(sender, "manhunt.whitelist.cleared", "removed", "3", "vips", "1");
        }

        @Test
        @DisplayName("clear is refused to somebody without the permission, and clears nothing")
        void clearNeedsThePermission() {
            when(sender.hasPermission(anyString())).thenReturn(false);

            command.execute(source, new String[]{"clear"});

            verify(fake.whitelist, never()).clear();
            verify(messages).send(sender, "manhunt.not-yours");
        }

        @Test
        @DisplayName("vip add names an online player and makes them one")
        void vipAddUsesTheOnlinePlayer() {
            org.bukkit.entity.Player anna = online("Anna");
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(() -> Bukkit.getPlayerExact("Anna")).thenReturn(anna);
            when(fake.whitelist.addVip(ANNA, "Anna")).thenReturn(true);

            command.execute(source, new String[]{"vip", "add", "Anna"});

            verify(fake.whitelist).addVip(ANNA, "Anna");
            verify(messages).send(sender, "manhunt.whitelist.vip.added", "player", "Anna");
        }

        @Test
        @DisplayName("adding somebody who is already a VIP says so instead of pretending")
        void vipAddSaysWhenAlreadyOne() {
            org.bukkit.entity.Player anna = online("Anna");
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(() -> Bukkit.getPlayerExact("Anna")).thenReturn(anna);
            when(fake.whitelist.addVip(ANNA, "Anna")).thenReturn(false);

            command.execute(source, new String[]{"vip", "add", "Anna"});

            verify(messages).send(sender, "manhunt.whitelist.vip.already", "player", "Anna");
        }

        @Test
        @DisplayName("a name the server has never heard of is refused, not guessed at")
        void vipAddRefusesAnUnknownName() {
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(() -> Bukkit.getPlayerExact("Ghost")).thenReturn(null);
            bukkit.when(() -> Bukkit.getOfflinePlayerIfCached("Ghost")).thenReturn(null);
            when(vipList.byName("Ghost")).thenReturn(java.util.Optional.empty());

            command.execute(source, new String[]{"vip", "add", "Ghost"});

            verify(fake.whitelist, never()).addVip(any(), anyString());
            verify(messages).send(sender, "manhunt.no-such-player", "player", "Ghost");
        }

        @Test
        @DisplayName("vip remove works off this module's own list, for somebody long gone")
        void vipRemoveFindsThemOnTheVipListItself() {
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(() -> Bukkit.getPlayerExact("Anna")).thenReturn(null);
            bukkit.when(() -> Bukkit.getOfflinePlayerIfCached("Anna")).thenReturn(null);
            when(vipList.byName("Anna")).thenReturn(java.util.Optional.of(ANNA));
            when(fake.whitelist.removeVip(ANNA)).thenReturn(true);

            command.execute(source, new String[]{"vip", "remove", "Anna"});

            verify(fake.whitelist).removeVip(ANNA);
            verify(messages).send(sender, "manhunt.whitelist.vip.removed", "player", "Anna");
        }

        @Test
        @DisplayName("removing somebody who is not a VIP says that, rather than claiming success")
        void vipRemoveSaysWhenTheyWereNotOne() {
            org.bukkit.entity.Player anna = online("Anna");
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(() -> Bukkit.getPlayerExact("Anna")).thenReturn(anna);
            when(vipList.byName("Anna")).thenReturn(java.util.Optional.empty());
            when(fake.whitelist.removeVip(ANNA)).thenReturn(false);

            command.execute(source, new String[]{"vip", "remove", "Anna"});

            verify(messages).send(sender, "manhunt.whitelist.vip.not-one", "player", "Anna");
        }

        @Test
        @DisplayName("vip list names everybody on it")
        void vipListNamesThem() {
            when(vipList.names()).thenReturn(java.util.List.of("Anna", "Ben"));

            command.execute(source, new String[]{"vip", "list"});

            verify(messages).send(sender, "manhunt.whitelist.vip.list", "players", "Anna, Ben");
        }

        @Test
        @DisplayName("an empty vip list says nobody rather than an empty line")
        void vipListWhenEmpty() {
            when(vipList.names()).thenReturn(java.util.List.of());

            command.execute(source, new String[]{"vip", "list"});

            verify(messages).send(sender, "manhunt.whitelist.vip.none");
        }

        @Test
        @DisplayName("vip on its own shows what it can do, and never reaches vanilla")
        void vipAloneShowsUsage() {
            command.execute(source, new String[]{"vip"});

            verify(messages).send(sender, "manhunt.whitelist.vip.usage");
        }

        @Test
        @DisplayName("vip is refused to somebody without the permission")
        void vipNeedsThePermission() {
            when(sender.hasPermission(anyString())).thenReturn(false);

            command.execute(source, new String[]{"vip", "list"});

            verify(messages).send(sender, "manhunt.not-yours");
        }
    }
}
