package de.raindancer.modules.arenagate.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.arena.api.ArenaProtocol;
import de.raindancer.modules.arena.api.wire.NetworkWire;
import de.raindancer.modules.arena.api.wire.Wire;
import de.raindancer.modules.arenagate.GateSettings;
import de.raindancer.modules.arenagate.store.EscrowDatabase;
import de.raindancer.modules.arenagate.store.EscrowStore;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Two survival servers, each with its own escrow database, one bank between them and one arena asking both — what a
 * fight between players of two servers does to the money.
 */
@Tag("database")
class GateNetworkTest {

    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CY = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    /** The network's bank: balances in one map, shared by both servers. */
    private static final class Bank implements Economy {
        final Map<UUID, Long> balances = new HashMap<>();

        @Override
        public String name() {
            return "bank";
        }

        @Override
        public Currency currency() {
            return mock(Currency.class);
        }

        @Override
        public boolean hasAccount(UUID player) {
            return true;
        }

        @Override
        public synchronized Money balance(UUID player) {
            return Money.of(balances.getOrDefault(player, 0L));
        }

        @Override
        public synchronized EconomyResult deposit(UUID player, Money amount, String reason) {
            balances.merge(player, amount.minor(), Long::sum);
            return EconomyResult.done(amount, balance(player));
        }

        @Override
        public synchronized EconomyResult withdraw(UUID player, Money amount, String reason) {
            if (balances.getOrDefault(player, 0L) < amount.minor()) {
                return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, balance(player));
            }
            balances.merge(player, -amount.minor(), Long::sum);
            return EconomyResult.done(amount, balance(player));
        }

        @Override
        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            throw new UnsupportedOperationException();
        }

        synchronized long total() {
            return balances.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<AutoCloseable> open = new ArrayList<>();
    private final Bank bank = new Bank();
    private final boolean[] bankUp = {true};

    private NetworkWire wire(String id) {
        NetworkCalls calls = new NetworkCalls(hub.bus(id));
        NetworkWire.Directory directory = new NetworkWire.Directory() {
            @Override
            public Set<String> servers() {
                return Set.of("arena", "smp-1", "smp-2", "hub");
            }

            @Override
            public Optional<Wire.Found> named(String name) {
                return Optional.empty();
            }

            @Override
            public Set<String> names() {
                return Set.of();
            }
        };
        NetworkWire wire = new NetworkWire(calls, id, directory, (player, server) -> { });
        open.add(wire::close);
        open.add(calls);
        return wire;
    }

    /** A survival server's gate: its own escrow database, the shared bank, its own consents. */
    private GateConsents gate(String id) {
        Database database = TestDatabases.open(folder.resolve(id + ".db"), EscrowDatabase.SCHEMA);
        open.add(database::close);
        EscrowService escrow = new EscrowService(() -> Optional.of(bank), new EscrowStore(database),
                System::currentTimeMillis, UUID::toString, GateSettings.DEFAULTS);
        escrow.bank(() -> bankUp[0]);
        GateConsents consents = new GateConsents(System::currentTimeMillis);
        escrow.consents(consents::takeStake);
        new GateNetwork(mock(Plugin.class), mock(Server.class), mock(Messages.class), wire(id), () -> "arena", escrow,
                mock(GearService.class), new GateInvites(System::currentTimeMillis), mock(GateService.class), consents,
                player -> false).listen();
        return consents;
    }

    @AfterEach
    void close() throws Exception {
        for (int index = open.size() - 1; index >= 0; index--) {
            open.get(index).close();
        }
    }

    private Map<String, String> ask(NetworkWire arena, String server, String topic, Map<String, String> data) throws Exception {
        return arena.ask(server, topic, data, Duration.ofSeconds(10)).get(15, TimeUnit.SECONDS);
    }

    private Map<String, String> hold(NetworkWire arena, String server, String fight, String players) throws Exception {
        return ask(arena, server, ArenaProtocol.STAKE_HOLD, Map.of("fight", fight, "players", players, "amount", "100"));
    }

    private Map<String, String> pay(NetworkWire arena, String server, String fight, Map<UUID, Long> payouts) throws Exception {
        StringBuilder text = new StringBuilder();
        payouts.forEach((player, amount) -> text.append(text.isEmpty() ? "" : ",").append(player).append('=').append(amount));
        return ask(arena, server, ArenaProtocol.STAKE_PAY, Map.of("fight", fight, "payouts", text.toString(),
                "participants", ADA + "," + BO + "," + CY));
    }

    private void everybodyHasMoneyAndAgreed(GateConsents first, GateConsents second) {
        bank.balances.put(ADA, 500L);
        bank.balances.put(BO, 500L);
        bank.balances.put(CY, 500L);
        first.agree(ADA, 100, false);
        first.agree(BO, 100, false);
        second.agree(CY, 100, false);
    }

    @Test
    @DisplayName("a fight of two servers' players: each holds its own, the winner of the other is paid from both, nothing is lost")
    void fightAcrossTwoServers() throws Exception {
        GateConsents first = gate("smp-1");
        GateConsents second = gate("smp-2");
        NetworkWire arena = wire("arena");
        everybodyHasMoneyAndAgreed(first, second);

        assertThat(hold(arena, "smp-1", "f1", ADA + "," + BO)).containsEntry("ok", "true");
        assertThat(hold(arena, "smp-2", "f1", CY.toString())).containsEntry("ok", "true");
        assertThat(bank.balances).containsEntry(ADA, 400L).containsEntry(BO, 400L).containsEntry(CY, 400L);

        assertThat(pay(arena, "smp-1", "f1", Map.of(CY, 200L))).containsEntry("ok", "true");
        assertThat(pay(arena, "smp-2", "f1", Map.of(CY, 100L))).containsEntry("ok", "true");

        assertThat(bank.balances).containsEntry(ADA, 400L).containsEntry(BO, 400L).containsEntry(CY, 700L);
        assertThat(bank.total()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("every call asked again after a lost answer — hold, pay, refund — moves money once")
    void askedAgain() throws Exception {
        GateConsents first = gate("smp-1");
        GateConsents second = gate("smp-2");
        NetworkWire arena = wire("arena");
        everybodyHasMoneyAndAgreed(first, second);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(hold(arena, "smp-1", "f1", ADA + "," + BO)).containsEntry("ok", "true");
        }
        assertThat(bank.balances).containsEntry(ADA, 400L).containsEntry(BO, 400L);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(pay(arena, "smp-1", "f1", Map.of(ADA, 200L))).containsEntry("ok", "true");
        }
        assertThat(bank.balances).containsEntry(ADA, 600L).containsEntry(BO, 400L);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(ask(arena, "smp-1", ArenaProtocol.STAKE_REFUND, Map.of("fight", "f1"))).containsEntry("ok", "true");
        }
        assertThat(bank.total()).isEqualTo(1500L);
        assertThat(bank.balances).containsEntry(ADA, 600L).containsEntry(BO, 400L);
    }

    @Test
    @DisplayName("a fight called off is given back by every server, each exactly once")
    void refundEverywhere() throws Exception {
        GateConsents first = gate("smp-1");
        GateConsents second = gate("smp-2");
        NetworkWire arena = wire("arena");
        everybodyHasMoneyAndAgreed(first, second);
        hold(arena, "smp-1", "f1", ADA + "," + BO);
        hold(arena, "smp-2", "f1", CY.toString());

        for (int attempt = 0; attempt < 2; attempt++) {
            ask(arena, "smp-1", ArenaProtocol.STAKE_REFUND, Map.of("fight", "f1"));
            ask(arena, "smp-2", ArenaProtocol.STAKE_REFUND, Map.of("fight", "f1"));
        }

        assertThat(bank.balances).containsEntry(ADA, 500L).containsEntry(BO, 500L).containsEntry(CY, 500L);
    }

    @Test
    @DisplayName("somebody who agreed on one server is not taken from by the other, nor by the arena naming them")
    void consentIsTheHomeServers() throws Exception {
        GateConsents first = gate("smp-1");
        gate("smp-2");
        NetworkWire arena = wire("arena");
        everybodyHasMoneyAndAgreed(first, new GateConsents(System::currentTimeMillis));

        assertThat(hold(arena, "smp-2", "f1", CY.toString())).containsEntry("ok", "false");
        assertThat(bank.balances).containsEntry(CY, 500L);
    }

    @Test
    @DisplayName("with the bank away the hold is refused and the pay waits: nothing moves, nothing is settled")
    void bankAway() throws Exception {
        GateConsents first = gate("smp-1");
        gate("smp-2");
        NetworkWire arena = wire("arena");
        everybodyHasMoneyAndAgreed(first, new GateConsents(System::currentTimeMillis));
        assertThat(hold(arena, "smp-1", "f1", ADA + "," + BO)).containsEntry("ok", "true");

        bankUp[0] = false;
        assertThat(hold(arena, "smp-1", "f2", ADA.toString())).containsEntry("ok", "false").containsEntry("who", "the economy");
        assertThat(pay(arena, "smp-1", "f1", Map.of(ADA, 200L))).containsEntry("ok", "false");
        assertThat(bank.balances).containsEntry(ADA, 400L).containsEntry(BO, 400L);

        bankUp[0] = true;
        assertThat(pay(arena, "smp-1", "f1", Map.of(ADA, 200L))).containsEntry("ok", "true");
        assertThat(bank.balances).containsEntry(ADA, 600L).containsEntry(BO, 400L);
    }

    @Test
    @DisplayName("only the arena may move stakes: another server of the network holding, paying or refunding is refused")
    void onlyTheArena() throws Exception {
        GateConsents first = gate("smp-1");
        GateConsents second = gate("smp-2");
        NetworkWire arena = wire("arena");
        NetworkWire elsewhere = wire("hub");
        everybodyHasMoneyAndAgreed(first, second);
        UUID thief = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

        assertThat(hold(elsewhere, "smp-1", "f9", ADA + "," + BO)).containsEntry("ok", "false");
        assertThat(bank.balances).containsEntry(ADA, 500L).containsEntry(BO, 500L);

        assertThat(hold(arena, "smp-1", "f1", ADA + "," + BO)).containsEntry("ok", "true");
        assertThat(ask(elsewhere, "smp-1", ArenaProtocol.STAKE_PAY, Map.of("fight", "f1", "payouts", thief + "=200",
                "participants", thief.toString()))).containsEntry("ok", "false");
        assertThat(ask(elsewhere, "smp-1", ArenaProtocol.STAKE_REFUND, Map.of("fight", "f1"))).containsEntry("ok", "false");
        assertThat(bank.balances).containsEntry(ADA, 400L).containsEntry(BO, 400L).doesNotContainKey(thief);
        assertThat(ask(elsewhere, "smp-1", ArenaProtocol.GEAR, Map.of("player", ADA.toString()))).containsEntry("ok", "false");
    }
}
