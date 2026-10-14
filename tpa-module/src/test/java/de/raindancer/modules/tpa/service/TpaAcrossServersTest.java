package de.raindancer.modules.tpa.service;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishSink;
import de.raindancer.core.platform.network.Arrivals;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.tpa.TpaSettings;
import de.raindancer.modules.tpa.model.TpaKind;
import de.raindancer.modules.tpa.model.TpaPrefs;
import de.raindancer.modules.tpa.rules.TpaAskingRule;
import de.raindancer.modules.tpa.store.TpaNetwork;
import de.raindancer.modules.tpa.store.TpaRequests;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Two servers over one bus: a request crosses, is answered by the server of the one asked, and moves somebody. */
class TpaAcrossServersTest {

    /** Who is where, as presence would say. */
    private final Map<UUID, Presence.Here> presence = new ConcurrentHashMap<>();

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<NetworkCalls> calls = new ArrayList<>();

    private final class Side {
        final String id;
        final Server server = mock(Server.class);
        final Plugin plugin = mock(Plugin.class);
        final TpaRequests requests = new TpaRequests();
        final TpaPrefsService prefs = mock(TpaPrefsService.class);
        final Travel travel = mock(Travel.class);
        final Messages messages = mock(Messages.class);
        final ChatButtons buttons = mock(ChatButtons.class);
        final List<String> sentTo = new ArrayList<>();
        final List<Arrivals.Destination> landed = new ArrayList<>();
        final TpaRequestService service;
        volatile CompletableFuture<Boolean> hopped = CompletableFuture.completedFuture(true);

        Side(String id) {
            this.id = id;
            when(plugin.getServer()).thenReturn(server);
            when(prefs.of(any())).thenReturn(TpaPrefs.untouched());
            when(prefs.nameOf(any(UUID.class))).thenAnswer(ask ->
                    Optional.ofNullable(presence.get(ask.<UUID>getArgument(0))).map(Presence.Here::name).orElse("somebody"));
            when(buttons.ask(any(), any(Duration.class), any(), any())).thenReturn(Component.text("[Accept] [Deny]"));
            when(messages.prefixed(any(), any(Object[].class))).thenReturn(Component.text("asked"));
            service = new TpaRequestService(plugin, requests, prefs, new TpaAskingRule(), travel, messages, buttons,
                    new Vanish(mock(VanishSink.class)), TpaSettings.DEFAULTS.withWarmupSeconds(0));
            NetworkCalls networkCalls = new NetworkCalls(hub.bus(id));
            calls.add(networkCalls);
            service.connectNetwork(new TpaNetwork(directory(id, true), networkCalls), (player, target, where) -> {
                sentTo.add(target);
                landed.add(where);
                return hopped;
            });
        }

        Player join(String name, double x) {
            Player who = mock(Player.class);
            UUID uuid = UUID.nameUUIDFromBytes(name.getBytes());
            World world = mock(World.class);
            when(world.getName()).thenReturn("world-" + id);
            when(who.getUniqueId()).thenReturn(uuid);
            when(who.getName()).thenReturn(name);
            when(who.isOnline()).thenReturn(true);
            when(who.getWorld()).thenReturn(world);
            when(who.getServer()).thenReturn(server);
            when(who.getLocation()).thenReturn(new Location(world, x, 70, 5, 90, 0));
            when(server.getPlayer(uuid)).thenReturn(who);
            presence.put(uuid, new Presence.Here(uuid, name, id, false, 0));
            return who;
        }
    }

    private TpaNetwork.Directory directory(String self, boolean unused) {
        return new TpaNetwork.Directory() {
            @Override
            public Optional<Presence.Here> where(UUID player, boolean withVanished) {
                return Optional.ofNullable(presence.get(player)).filter(here -> withVanished || !here.vanished());
            }

            @Override
            public Optional<Presence.Here> named(String name, boolean withVanished) {
                return presence.values().stream().filter(here -> here.name().equalsIgnoreCase(name))
                        .filter(here -> withVanished || !here.vanished()).findFirst();
            }

            @Override
            public List<Presence.Here> everyone(boolean withVanished) {
                return presence.values().stream().filter(here -> withVanished || !here.vanished()).toList();
            }

            @Override
            public String self() {
                return self;
            }
        };
    }

    private Side smp;
    private Side arena;
    private Player steve;
    private Player alex;

    @BeforeEach
    void servers() {
        smp = new Side("smp");
        arena = new Side("arena");
        steve = smp.join("Steve", 10);
        alex = arena.join("Alex", 500);
    }

    @AfterEach
    void close() {
        calls.forEach(NetworkCalls::close);
        Economies.clear();
    }

    private static final class Bank implements Economy {
        final Map<UUID, Money> balances = new ConcurrentHashMap<>();

        public String name() {
            return "bank";
        }

        public Currency currency() {
            return Currency.DEFAULT;
        }

        public boolean hasAccount(UUID player) {
            return true;
        }

        public Money balance(UUID player) {
            return balances.getOrDefault(player, Money.ZERO);
        }

        public EconomyResult deposit(UUID player, Money amount, String reason) {
            balances.merge(player, amount, Money::plus);
            return EconomyResult.done(amount, balance(player));
        }

        public EconomyResult withdraw(UUID player, Money amount, String reason) {
            if (!balance(player).isAtLeast(amount)) {
                return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, balance(player));
            }
            balances.merge(player, amount.negate(), Money::plus);
            return EconomyResult.done(amount, balance(player));
        }

        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            throw new UnsupportedOperationException();
        }
    }

    /** Steve on smp, with a price on trips to another server, asks Alex and is accepted; the hop ends as given. */
    private Bank acceptedForAFee(Boolean hopEnds) throws Exception {
        Bank bank = new Bank();
        bank.balances.put(steve.getUniqueId(), Money.of(2000));
        Economies.provide(mock(Plugin.class), bank);
        smp.service.settings(TpaSettings.DEFAULTS.withWarmupSeconds(0).withCrossWorldPrice("5"));
        smp.hopped = hopEnds == null ? new CompletableFuture<>() : CompletableFuture.completedFuture(hopEnds);
        smp.service.askElsewhere(steve, smp.service.findElsewhere("Alex").orElseThrow(), TpaKind.TO);
        eventually(() -> arena.requests.has(steve.getUniqueId(), alex.getUniqueId()));
        arena.service.accept(alex, steve.getUniqueId());
        eventually(() -> !smp.requests.has(steve.getUniqueId(), alex.getUniqueId()));
        arrive(smp.travel, steve);
        return bank;
    }

    @Test
    @DisplayName("a hop that did not move the traveller gives the fee back and says so")
    void hopFailedRefunds() throws Exception {
        Bank bank = acceptedForAFee(false);

        assertThat(bank.balance(steve.getUniqueId())).isEqualTo(Money.of(2000));
        verify(smp.messages).send(eq(steve), eq("tpa.refunded"), any(Object[].class));
        verify(smp.messages, never()).send(eq(steve), eq("tpa.paid"), any(Object[].class));
    }

    @Test
    @DisplayName("a hop that moved the traveller keeps the fee")
    void hopSucceededKeepsFee() throws Exception {
        Bank bank = acceptedForAFee(true);

        assertThat(bank.balance(steve.getUniqueId())).isLessThan(Money.of(2000));
        verify(smp.messages).send(eq(steve), eq("tpa.paid"), any(Object[].class));
        verify(smp.messages, never()).send(eq(steve), eq("tpa.refunded"), any(Object[].class));
    }

    @Test
    @DisplayName("the fee stays held until the hop completes, and a second trip is refused meanwhile")
    void feeHeldWhileHopping() throws Exception {
        Bank bank = acceptedForAFee(null);

        assertThat(bank.balance(steve.getUniqueId())).isLessThan(Money.of(2000));
        verify(smp.messages, never()).send(eq(steve), eq("tpa.refunded"), any(Object[].class));
        smp.hopped.complete(false);
        assertThat(bank.balance(steve.getUniqueId())).isEqualTo(Money.of(2000));
    }

    @Test
    @DisplayName("leaving this server for the hop does not hand the fee back: the hop decides")
    void quittingForTheHopKeepsTheFee() throws Exception {
        Bank bank = acceptedForAFee(null);

        smp.service.leaves(steve.getUniqueId());
        smp.hopped.complete(true);

        assertThat(bank.balance(steve.getUniqueId())).isLessThan(Money.of(2000));
        verify(smp.messages).send(eq(steve), eq("tpa.paid"), any(Object[].class));
        verify(smp.messages, never()).send(eq(steve), eq("tpa.refunded"), any(Object[].class));
    }

    @Test
    @DisplayName("while a hop is under way no second trip is started, even a free one")
    void noSecondTripDuringTheHop() throws Exception {
        smp.hopped = new CompletableFuture<>();
        smp.service.askElsewhere(steve, smp.service.findElsewhere("Alex").orElseThrow(), TpaKind.TO);
        eventually(() -> arena.requests.has(steve.getUniqueId(), alex.getUniqueId()));
        arena.service.accept(alex, steve.getUniqueId());
        eventually(() -> !smp.requests.has(steve.getUniqueId(), alex.getUniqueId()));
        arrive(smp.travel, steve);
        arena.service.askElsewhere(alex, arena.service.findElsewhere("Steve").orElseThrow(), TpaKind.HERE);
        eventually(() -> smp.requests.has(alex.getUniqueId(), steve.getUniqueId()));

        smp.service.accept(steve, alex.getUniqueId());

        verify(smp.messages).send(eq(steve), eq("tpa.already-travelling"), any(Object[].class));
        verify(smp.travel, times(1)).goElsewhere(any(), any(), any(), any());
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).as("it happened within five seconds").isTrue();
    }

    private static void arrive(Travel travel, Player traveller) throws InterruptedException {
        eventually(() -> {
            try {
                verify(travel).goElsewhere(eq(traveller), any(Trip.class), any(TravelWatcher.class), any());
                return true;
            } catch (AssertionError notYet) {
                return false;
            }
        });
        ArgumentCaptor<TravelWatcher> watcher = ArgumentCaptor.forClass(TravelWatcher.class);
        verify(travel).goElsewhere(eq(traveller), any(Trip.class), watcher.capture(), any());
        verify(travel, never()).go(any(), any(), any(), any());
        watcher.getValue().arrived(traveller, traveller.getLocation(), Trip.to("x"));
    }

    @Test
    @DisplayName("somebody on another server is found by name, and a vanished one is not")
    void foundByName() {
        assertThat(smp.service.findElsewhere("Alex")).get().extracting(Presence.Here::server).isEqualTo("arena");
        assertThat(smp.service.findElsewhere("Steve")).as("not on another server").isEmpty();
        presence.put(alex.getUniqueId(), new Presence.Here(alex.getUniqueId(), "Alex", "arena", true, 0));
        assertThat(smp.service.findElsewhere("Alex")).isEmpty();
    }

    @Test
    @DisplayName("/tpa to somebody on another server: they are asked there, and on accept the asker is sent to them")
    void askingToGoToSomebodyElsewhere() throws Exception {
        assertThat(smp.service.askElsewhere(steve, smp.service.findElsewhere("Alex").orElseThrow(), TpaKind.TO)).isTrue();

        eventually(() -> arena.requests.has(steve.getUniqueId(), alex.getUniqueId()));
        assertThat(smp.requests.has(steve.getUniqueId(), alex.getUniqueId())).as("the asker keeps a copy").isTrue();

        assertThat(arena.service.accept(alex, steve.getUniqueId())).isTrue();
        eventually(() -> !smp.requests.has(steve.getUniqueId(), alex.getUniqueId()));
        arrive(smp.travel, steve);

        assertThat(smp.sentTo).containsExactly("arena");
        assertThat(smp.landed).singleElement().satisfies(where -> {
            assertThat(where.world()).isEqualTo("world-arena");
            assertThat(where.x()).isEqualTo(500);
        });
        assertThat(arena.sentTo).isEmpty();
    }

    @Test
    @DisplayName("/tpahere to somebody on another server: on accept the asked player is sent to where the asker stands")
    void askingSomebodyElsewhereToCome() throws Exception {
        assertThat(smp.service.askElsewhere(steve, smp.service.findElsewhere("Alex").orElseThrow(), TpaKind.HERE)).isTrue();
        eventually(() -> arena.requests.has(steve.getUniqueId(), alex.getUniqueId()));

        assertThat(arena.service.accept(alex, steve.getUniqueId())).isTrue();
        eventually(() -> !smp.requests.has(steve.getUniqueId(), alex.getUniqueId()));
        arrive(arena.travel, alex);

        assertThat(arena.sentTo).containsExactly("smp");
        assertThat(arena.landed).singleElement().satisfies(where -> {
            assertThat(where.world()).isEqualTo("world-smp");
            assertThat(where.x()).isEqualTo(10);
        });
        assertThat(smp.sentTo).isEmpty();
    }

    @Test
    @DisplayName("a refusal on the server of the one asked ends the asker's copy and tells them")
    void refusalTravelsBack() throws Exception {
        smp.service.askElsewhere(steve, smp.service.findElsewhere("Alex").orElseThrow(), TpaKind.TO);
        eventually(() -> arena.requests.has(steve.getUniqueId(), alex.getUniqueId()));

        arena.service.deny(alex, steve.getUniqueId());

        eventually(() -> !smp.requests.has(steve.getUniqueId(), alex.getUniqueId()));
        eventually(() -> {
            try {
                verify(smp.messages).send(eq(steve), eq("tpa.they-refused"), any(Object[].class));
                return true;
            } catch (AssertionError notYet) {
                return false;
            }
        });
    }

    @Test
    @DisplayName("somebody who is not accepting is refused across servers, and nothing is kept")
    void notAcceptingIsRefused() throws Exception {
        when(smp.prefs.of(alex.getUniqueId())).thenReturn(TpaPrefs.untouched().refusingEverybody());

        assertThat(smp.service.askElsewhere(steve, smp.service.findElsewhere("Alex").orElseThrow(), TpaKind.TO)).isFalse();

        assertThat(smp.requests.has(steve.getUniqueId(), alex.getUniqueId())).isFalse();
        assertThat(arena.requests.has(steve.getUniqueId(), alex.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("a server that does not host the one asked cannot accept for them")
    void onlyTheirServerMayAccept() throws Exception {
        smp.service.askElsewhere(steve, smp.service.findElsewhere("Alex").orElseThrow(), TpaKind.TO);
        eventually(() -> smp.requests.has(steve.getUniqueId(), alex.getUniqueId()));
        eventually(() -> arena.requests.has(steve.getUniqueId(), alex.getUniqueId()));

        NetworkCalls forger = new NetworkCalls(hub.bus("elsewhere"));
        calls.add(forger);
        Optional<Map<String, String>> answer = forger.ask("smp", TpaNetwork.ACCEPT, Map.of(
                "asker", steve.getUniqueId().toString(), "target", alex.getUniqueId().toString(),
                "targetName", "Alex", "world", "w", "x", "0", "y", "0", "z", "0", "yaw", "0", "pitch", "0"),
                Duration.ofSeconds(5)).get();

        assertThat(answer).get().satisfies(reply -> assertThat(reply.get("result")).isEqualTo("gone"));
        assertThat(smp.requests.has(steve.getUniqueId(), alex.getUniqueId())).isTrue();
        assertThat(smp.sentTo).isEmpty();
    }

    @Test
    @DisplayName("asking on the same server is unchanged: no copy anywhere else, no hop")
    void sameServerStaysLocal() {
        Player bob = smp.join("Bob", 20);

        assertThat(smp.service.ask(steve, bob, TpaKind.TO)).isTrue();

        assertThat(smp.requests.has(steve.getUniqueId(), bob.getUniqueId())).isTrue();
        assertThat(arena.requests.count()).isZero();
        assertThat(smp.sentTo).isEmpty();
    }

    @Test
    @DisplayName("a block list holds against somebody on another server, whatever their server says about bypassing it")
    void bypassDoesNotCrossServers() throws Exception {
        when(arena.prefs.of(alex.getUniqueId())).thenReturn(TpaPrefs.untouched().refusingEverybody());
        NetworkCalls smpCalls = calls.get(0);
        Optional<Map<String, String>> answer = smpCalls.ask("arena", TpaNetwork.ASK, Map.of(
                "from", steve.getUniqueId().toString(), "to", alex.getUniqueId().toString(), "kind", "TO",
                "fromName", "Steve", "bypass", "true"), Duration.ofSeconds(5)).get();

        assertThat(answer).get().satisfies(reply -> assertThat(reply.get("result")).isEqualTo("not-accepting"));
        assertThat(arena.requests.has(steve.getUniqueId(), alex.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("only the server a player was on may say they left: a request is not ended by anybody else")
    void leftOnlyFromTheirServer() throws Exception {
        smp.service.askElsewhere(steve, smp.service.findElsewhere("Alex").orElseThrow(), TpaKind.TO);
        eventually(() -> arena.requests.has(steve.getUniqueId(), alex.getUniqueId()));

        NetworkCalls forger = new NetworkCalls(hub.bus("elsewhere"));
        calls.add(forger);
        forger.ask("arena", TpaNetwork.END, Map.of("asker", steve.getUniqueId().toString(),
                "asked", alex.getUniqueId().toString(), "how", "left", "name", "Steve"), Duration.ofSeconds(5)).get();

        assertThat(arena.requests.has(steve.getUniqueId(), alex.getUniqueId())).isTrue();
    }
}
