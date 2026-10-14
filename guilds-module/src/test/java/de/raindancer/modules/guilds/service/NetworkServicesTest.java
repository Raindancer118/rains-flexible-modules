package de.raindancer.modules.guilds.service;

import de.raindancer.core.platform.network.Arrivals;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.platform.network.NetworkTravel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Fixtures;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.HomeSpot;
import de.raindancer.modules.guilds.store.GuildBook;
import de.raindancer.modules.guilds.store.GuildsDatabase;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static de.raindancer.modules.guilds.model.Fixtures.LEADER;
import static de.raindancer.modules.guilds.model.Fixtures.MEMBER;
import static de.raindancer.modules.guilds.model.Fixtures.OFFICER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Telling members, chatting and travelling to a home when the other member or the home is on another server. */
@Tag("database")
class NetworkServicesTest {

    @TempDir
    Path folder;

    private Database database;
    private GuildBook book;
    private Guild elves;
    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final NetworkPlayers elsewhere = mock(NetworkPlayers.class);

    @BeforeEach
    void start() {
        database = TestDatabases.open(folder.resolve("guilds.db"), GuildsDatabase.SCHEMA);
        book = new GuildBook(database, problem -> { });
        book.load();
        elves = Fixtures.guild("Elves");
        assertThat(book.create(elves)).isEqualTo(GuildOutcome.SUCCESS);
        when(messages.get(anyString(), any(Object[].class)))
                .thenAnswer(call -> Component.text(call.getArgument(0) + ":" + java.util.Arrays.toString(
                        java.util.Arrays.copyOfRange(call.getArguments(), 1, call.getArguments().length))));
    }

    @AfterEach
    void stop() {
        database.close();
    }

    private Player online(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(server.getPlayer(id)).thenReturn(player);
        return player;
    }

    @Test
    @DisplayName("a member on another server is told through the network, with the placeholders as plain text")
    void remoteMember() {
        GuildNotifier notify = new GuildNotifier(server, messages);
        notify.network(LocalBus.alone("smp"), () -> Optional.of(elsewhere));
        Player here = online(LEADER);

        notify.members(elves, "guilds.joined", "player", "Bo", "guild", GuildNotifier.title(elves));

        verify(messages).send(here, "guilds.joined", "player", "Bo", "guild", GuildNotifier.title(elves));
        verify(elsewhere).tell(OFFICER, "guilds.joined", "player", "Bo", "guild", "Elves");
        verify(elsewhere).tell(MEMBER, "guilds.joined", "player", "Bo", "guild", "Elves");
        verify(elsewhere, never()).tell(eq(LEADER), anyString(), any(String[].class));
    }

    @Test
    @DisplayName("an announcement is said on every server of the network")
    void announcement() {
        LocalBus.Hub hub = new LocalBus.Hub();
        Player onSmp = mock(Player.class);
        Player onArena = mock(Player.class);
        Server arenaServer = mock(Server.class);
        when(server.getOnlinePlayers()).thenAnswer(call -> List.of(onSmp));
        when(arenaServer.getOnlinePlayers()).thenAnswer(call -> List.of(onArena));
        Messages arenaMessages = mock(Messages.class);
        GuildNotifier smp = new GuildNotifier(server, messages);
        GuildNotifier arena = new GuildNotifier(arenaServer, arenaMessages);
        smp.network(hub.bus("smp"), Optional::empty);
        arena.network(hub.bus("arena"), Optional::empty);

        smp.everybody("guilds.announce.founded", "guild", GuildNotifier.title(elves), "player", "Ana");
        hub.drain();

        verify(messages).send(onSmp, "guilds.announce.founded", "guild", GuildNotifier.title(elves), "player", "Ana");
        verify(arenaMessages).send(onArena, "guilds.announce.founded", "guild", "Elves", "player", "Ana");
    }

    @Test
    @DisplayName("what a member says in guild chat is heard by the members on the other server, rendered there")
    void chat() {
        LocalBus.Hub hub = new LocalBus.Hub();
        GuildNotifier smpNotify = new GuildNotifier(server, messages);
        GuildChatService smp = new GuildChatService(book, smpNotify, GuildsSettings.DEFAULTS);
        smp.network(hub.bus("smp"));

        Server arenaServer = mock(Server.class);
        Messages arenaMessages = mock(Messages.class);
        when(arenaMessages.get(anyString(), any(Object[].class))).thenReturn(Component.text("line"));
        GuildBook arenaBook = new GuildBook(database, problem -> { });
        arenaBook.load();
        GuildChatService arena = new GuildChatService(arenaBook, new GuildNotifier(arenaServer, arenaMessages),
                GuildsSettings.DEFAULTS);
        arena.network(hub.bus("arena"));
        Player listener = mock(Player.class);
        when(arenaServer.getPlayer(MEMBER)).thenReturn(listener);

        Player speaker = online(LEADER);
        org.bukkit.OfflinePlayer known = mock(org.bukkit.OfflinePlayer.class);
        when(known.getUniqueId()).thenReturn(LEADER);
        when(known.getName()).thenReturn("Ana");
        when(server.getOfflinePlayer(LEADER)).thenReturn(known);
        assertThat(smp.say(GuildChatService.Kind.GUILD, speaker, "hello from smp")).isTrue();
        hub.drain();

        verify(listener).sendMessage(Component.text("line"));
        verify(arenaMessages).get(eq("guilds.chat.guild"), any(), any(), eq("player"), any(), eq("message"), eq("hello from smp"));
    }

    @Test
    @DisplayName("a guild home on another server sends the player there to land at it; one here is gone to as ever")
    void homeOnAnotherServer() {
        Guild homed = book.guild(elves.id()).orElseThrow();
        book.update(homed.id(), guild -> guild.withXp(1_000_000)
                .withHome(Optional.of(new HomeSpot("arena_world", 10, 64, -5, 90f, 10f, "arena"))));
        NetworkTravel travel = mock(NetworkTravel.class);
        HomeService homes = new HomeService(book, new GuildNotifier(server, messages), server, mock(Travel.class),
                System::currentTimeMillis, GuildsSettings.DEFAULTS);
        homes.network(() -> "smp", () -> Optional.of(travel));
        Player player = online(LEADER);

        assertThat(homes.go(player)).isEqualTo(GuildOutcome.SUCCESS);
        verify(travel).send(player, "arena", Optional.of(new Arrivals.Destination("arena_world", 10, 64, -5, 90f, 10f)));

        HomeService onArena = new HomeService(book, new GuildNotifier(server, messages), server, mock(Travel.class),
                System::currentTimeMillis, GuildsSettings.DEFAULTS);
        onArena.network(() -> "arena", () -> Optional.of(travel));
        assertThat(onArena.go(player)).as("its own world is not loaded here: no home to go to").isEqualTo(GuildOutcome.NO_HOME);
    }

    @Test
    @DisplayName("a home set here carries this server's id; on a server of its own it carries none")
    void homeCarriesTheServer() {
        book.update(elves.id(), guild -> guild.withXp(1_000_000));
        HomeService homes = new HomeService(book, new GuildNotifier(server, messages), server, mock(Travel.class),
                System::currentTimeMillis, GuildsSettings.DEFAULTS);
        Player player = online(LEADER);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(player.getLocation()).thenReturn(new Location(world, 1, 2, 3));

        homes.network(() -> "smp", Optional::empty);
        assertThat(homes.set(player)).isEqualTo(GuildOutcome.SUCCESS);
        assertThat(book.guild(elves.id()).orElseThrow().home()).map(HomeSpot::server).contains("smp");

        homes.network(() -> "", Optional::empty);
        assertThat(homes.set(player)).isEqualTo(GuildOutcome.SUCCESS);
        assertThat(book.guild(elves.id()).orElseThrow().home()).map(HomeSpot::server).contains("");
    }
}
