package de.raindancer.modules.arenagate.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.arenagate.GateSettings;
import de.raindancer.modules.arenagate.store.EscrowDatabase;
import de.raindancer.modules.arenagate.store.EscrowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Stakes held for arena fights: not one unit created or lost, whatever order things are asked in, however often. */
@Tag("database")
class EscrowServiceTest {

    /** Balances in a map; refuses a withdrawal larger than the balance, like a real economy. */
    private static final class Ledger implements Economy {
        final Map<UUID, Long> balances = new HashMap<>();

        @Override
        public String name() {
            return "test";
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
        public Money balance(UUID player) {
            return Money.of(balances.getOrDefault(player, 0L));
        }

        @Override
        public EconomyResult deposit(UUID player, Money amount, String reason) {
            balances.merge(player, amount.minor(), Long::sum);
            return EconomyResult.done(amount, balance(player));
        }

        @Override
        public EconomyResult withdraw(UUID player, Money amount, String reason) {
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

        long total() {
            return balances.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    @TempDir
    Path folder;

    private final Ledger ledger = new Ledger();
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final UUID ada = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private Database database;
    private EscrowService escrow;

    @BeforeEach
    void open() {
        database = TestDatabases.open(folder.resolve("arenagate.db"), EscrowDatabase.SCHEMA);
        escrow = new EscrowService(() -> Optional.of(ledger), new EscrowStore(database), clock::get,
                UUID::toString, GateSettings.DEFAULTS);
        ledger.balances.put(ada, 500L);
        ledger.balances.put(bo, 500L);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    @DisplayName("held from both, paid to the winner: the winner has the pot, the loser lost their stake, nothing else moved")
    void holdAndPay() {
        assertThat(escrow.hold("f1", List.of(ada, bo), 100)).isEmpty();
        assertThat(ledger.balances).containsEntry(ada, 400L).containsEntry(bo, 400L);

        assertThat(escrow.pay("f1", Map.of(ada, 200L))).isTrue();

        assertThat(ledger.balances).containsEntry(ada, 600L).containsEntry(bo, 400L);
        assertThat(ledger.total()).isEqualTo(1000);
    }

    @Test
    @DisplayName("somebody who cannot pay calls it off, and whoever already paid gets it back at once")
    void cannotPay() {
        ledger.balances.put(bo, 50L);

        assertThat(escrow.hold("f1", List.of(ada, bo), 100)).contains(bo.toString());

        assertThat(ledger.balances).containsEntry(ada, 500L).containsEntry(bo, 50L);
        assertThat(escrow.pay("f1", Map.of(ada, 200L))).as("nothing is held to pay out").isTrue();
        assertThat(ledger.balances).containsEntry(ada, 500L);
    }

    @Test
    @DisplayName("asked twice to pay, the second moves no money; asked to refund after paying, nothing moves either")
    void settledOnce() {
        escrow.hold("f1", List.of(ada, bo), 100);
        escrow.pay("f1", Map.of(ada, 200L));

        escrow.pay("f1", Map.of(ada, 200L));
        escrow.refund("f1");

        assertThat(ledger.balances).containsEntry(ada, 600L).containsEntry(bo, 400L);
    }

    @Test
    @DisplayName("held twice for the same fight takes the stake once")
    void heldOnce() {
        escrow.hold("f1", List.of(ada, bo), 100);
        escrow.hold("f1", List.of(ada, bo), 100);

        assertThat(ledger.balances).containsEntry(ada, 400L).containsEntry(bo, 400L);
    }

    @Test
    @DisplayName("a draw gives every stake back")
    void refund() {
        escrow.hold("f1", List.of(ada, bo), 100);

        assertThat(escrow.refund("f1")).isTrue();

        assertThat(ledger.balances).containsEntry(ada, 500L).containsEntry(bo, 500L);
    }

    @Test
    @DisplayName("a payout larger than the pot is never made — the stakes go back instead")
    void neverMoreThanThePot() {
        escrow.hold("f1", List.of(ada, bo), 100);

        escrow.pay("f1", Map.of(ada, 10_000L));

        assertThat(ledger.balances).containsEntry(ada, 500L).containsEntry(bo, 500L);
    }

    @Test
    @DisplayName("the house cut leaves the economy; the winner gets the rest")
    void houseCut() {
        escrow.settings(GateSettings.DEFAULTS.withHouseCutPercent(10));
        escrow.hold("f1", List.of(ada, bo), 100);

        escrow.pay("f1", Map.of(ada, 200L));

        assertThat(ledger.balances).containsEntry(ada, 580L).containsEntry(bo, 400L);
        assertThat(ledger.total()).isEqualTo(980);
    }

    @Test
    @DisplayName("stakes held over two hours without a result go back by themselves; newer ones stay held")
    void staleGoesBack() {
        escrow.hold("old", List.of(ada, bo), 100);
        clock.addAndGet(EscrowService.STALE_MILLIS + 1);
        escrow.hold("new", List.of(ada, bo), 50);

        escrow.sweep();

        assertThat(ledger.balances).containsEntry(ada, 450L).containsEntry(bo, 450L);
    }

    @Test
    @DisplayName("no economy: nothing is held and the fight is off")
    void noEconomy() {
        EscrowService without = new EscrowService(Optional::empty, new EscrowStore(database), clock::get,
                UUID::toString, GateSettings.DEFAULTS);

        assertThat(without.hold("f1", List.of(ada, bo), 100)).contains("the economy");
        assertThat(ledger.total()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a payout to somebody who did not put a stake in is never made — the stakes go back instead")
    void onlyToThoseWhoStaked() {
        UUID stranger = UUID.randomUUID();
        escrow.hold("f1", List.of(ada, bo), 100);

        escrow.pay("f1", Map.of(stranger, 200L));

        assertThat(ledger.balances).doesNotContainKey(stranger);
        assertThat(ledger.balances).containsEntry(ada, 500L).containsEntry(bo, 500L);
    }

    @Test
    @DisplayName("a winner who belongs to another survival server is paid out of this pot, and the total stays the same")
    void payAWinnerFromElsewhere() {
        UUID cy = UUID.randomUUID();
        escrow.hold("f1", List.of(ada, bo), 100);

        assertThat(escrow.pay("f1", Map.of(cy, 200L), Set.of(ada, bo, cy))).isTrue();

        assertThat(ledger.balances).containsEntry(ada, 400L).containsEntry(bo, 400L).containsEntry(cy, 200L);
        assertThat(ledger.total()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a share of the pot may be smaller than the pot: the rest goes back to whoever put it in")
    void aShareOfThePot() {
        UUID cy = UUID.randomUUID();
        escrow.hold("f1", List.of(ada, bo), 100);

        assertThat(escrow.pay("f1", Map.of(cy, 100L), Set.of(ada, bo, cy))).isTrue();

        assertThat(ledger.balances).containsEntry(ada, 450L).containsEntry(bo, 450L).containsEntry(cy, 100L);
        assertThat(ledger.total()).isEqualTo(1000);
    }

    @Test
    @DisplayName("somebody the arena does not name as being in the fight is never paid, even from another server")
    void notAParticipant() {
        UUID stranger = UUID.randomUUID();
        escrow.hold("f1", List.of(ada, bo), 100);

        escrow.pay("f1", Map.of(stranger, 200L), Set.of(ada, bo));

        assertThat(ledger.balances).doesNotContainKey(stranger);
        assertThat(ledger.balances).containsEntry(ada, 500L).containsEntry(bo, 500L);
    }

    @Test
    @DisplayName("while the bank cannot be reached nothing is settled — the stakes stay held to be settled when it is back")
    void bankAway() {
        boolean[] reachable = {true};
        escrow.bank(() -> reachable[0]);
        escrow.hold("f1", List.of(ada, bo), 100);
        reachable[0] = false;

        assertThat(escrow.pay("f1", Map.of(ada, 200L))).isFalse();
        assertThat(escrow.refund("f1")).isFalse();
        assertThat(escrow.hold("f2", List.of(ada, bo), 100)).contains("the economy");
        assertThat(ledger.balances).containsEntry(ada, 400L).containsEntry(bo, 400L);

        reachable[0] = true;
        assertThat(escrow.pay("f1", Map.of(ada, 200L))).isTrue();
        assertThat(ledger.balances).containsEntry(ada, 600L).containsEntry(bo, 400L);
    }

    @Test
    @DisplayName("the same fight asked to pay again — a retry after a lost answer — pays nothing a second time")
    void retryAfterALostAnswer() {
        UUID cy = UUID.randomUUID();
        escrow.hold("f1", List.of(ada, bo), 100);

        escrow.pay("f1", Map.of(cy, 200L), Set.of(ada, bo, cy));
        escrow.pay("f1", Map.of(cy, 200L), Set.of(ada, bo, cy));
        escrow.pay("f1", Map.of(cy, 200L), Set.of(ada, bo, cy));

        assertThat(ledger.balances).containsEntry(cy, 200L);
        assertThat(ledger.total()).isEqualTo(1000);
    }
}
