package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** The entry fee as the lobby uses it: charged at the start, paid out or refunded when the run ends. */
class SpeedrunLobbyFeesTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final UUID POOR = UUID.nameUUIDFromBytes("poor".getBytes());

    @TempDir
    Path dataFolder;

    private final Bank bank = new Bank();
    private final List<String> told = new ArrayList<>();
    private SettingsStore<SpeedrunSettings> settings;
    private JavaPlugin plugin;
    private SpeedrunEntryLedger ledger;
    private SpeedrunLobby lobby;

    private static final class Bank implements Economy {
        final Map<UUID, Money> balances = new HashMap<>();

        public String name() {
            return "bank";
        }

        public Currency currency() {
            return Currency.DEFAULT;
        }

        public boolean hasAccount(UUID player) {
            return balances.containsKey(player);
        }

        public boolean createAccount(UUID player) {
            balances.putIfAbsent(player, Money.ZERO);
            return true;
        }

        public Money balance(UUID player) {
            return balances.getOrDefault(player, Money.ZERO);
        }

        public EconomyResult deposit(UUID player, Money amount, String reason) {
            balances.merge(player, amount, Money::plus);
            return EconomyResult.done(amount, balances.get(player));
        }

        public EconomyResult withdraw(UUID player, Money amount, String reason) {
            if (balance(player).isAtLeast(amount)) {
                balances.merge(player, amount.negate(), Money::plus);
                return EconomyResult.done(amount, balances.get(player));
            }
            return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, balance(player));
        }

        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            withdraw(from, amount, reason);
            return deposit(to, amount, reason);
        }
    }

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(plugin.isEnabled()).thenReturn(true);
        settings = new SettingsStore<>(SettingsSchema.of(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS),
                dataFolder.resolve("speedrun.yml"));
        settings.load();
        settings.set("world-name", "world");
        settings.set("economy.entry-fee", "50");
        settings.set("economy.house-cut-percent", "10");
        bank.balances.put(ALICE, Money.of(10_000));
        bank.balances.put(BOB, Money.of(10_000));
        bank.balances.put(POOR, Money.of(100));
        Economies.provide(mock(org.bukkit.plugin.Plugin.class), bank);
        ledger = new SpeedrunEntryLedger(dataFolder.resolve("entries.yml"));
        ledger.load();
        lobby = new SpeedrunLobby(plugin, settings);
        lobby.useEntryFees(new SpeedrunEntryFees(ledger, (player, key, pairs) -> told.add(player + ":" + key),
                message -> { }, settings::current));
    }

    @AfterEach
    void tearDown() {
        Economies.clear();
    }

    private MockedStatic<Bukkit> withWorld() {
        MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
        return bukkit;
    }

    @Test
    @DisplayName("starting charges every racer and the fees form the pot")
    void startCharges() {
        try (MockedStatic<Bukkit> bukkit = withWorld()) {
            assertThat(lobby.start(Set.of(ALICE, BOB))).isEqualTo(SpeedrunLobby.StartOutcome.STARTED);
            assertThat(bank.balance(ALICE)).isEqualTo(Money.of(5_000));
            assertThat(bank.balance(BOB)).isEqualTo(Money.of(5_000));
            assertThat(ledger.pot()).isEqualTo(Money.of(10_000));
        }
    }

    @Test
    @DisplayName("a racer who cannot pay stops the start, and nobody is charged")
    void startRefused() {
        try (MockedStatic<Bukkit> bukkit = withWorld()) {
            assertThat(lobby.start(Set.of(ALICE, POOR))).isEqualTo(SpeedrunLobby.StartOutcome.ENTRY_FEE_REFUSED);
            assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.READY);
            assertThat(bank.balance(ALICE)).isEqualTo(Money.of(10_000));
            assertThat(ledger.pot()).isEqualTo(Money.ZERO);
            assertThat(lobby.messageFor(SpeedrunLobby.StartOutcome.ENTRY_FEE_REFUSED, Set.of()))
                    .isEqualTo("speedrun.start.entry-refused");
        }
    }

    @Test
    @DisplayName("a run won by its goal pays the racers the pot minus the house cut, shared evenly")
    void goalReachedPays() {
        try (MockedStatic<Bukkit> bukkit = withWorld()) {
            lobby.start(Set.of(ALICE, BOB));
            lobby.session().orElseThrow().finish("advancement:minecraft:end/kill_dragon");
            // pot 10,000 minus 10% = 9,000, 4,500 each
            assertThat(bank.balance(ALICE)).isEqualTo(Money.of(5_000 + 4_500));
            assertThat(bank.balance(BOB)).isEqualTo(Money.of(5_000 + 4_500));
            assertThat(ledger.pot()).isEqualTo(Money.ZERO);
        }
    }

    @Test
    @DisplayName("a run nobody won — everybody died, or an admin reset it — gives the fees back")
    void noWinnerRefunds() {
        try (MockedStatic<Bukkit> bukkit = withWorld()) {
            lobby.start(Set.of(ALICE, BOB));
            lobby.session().orElseThrow().finish("death-all");
            assertThat(bank.balance(ALICE)).isEqualTo(Money.of(10_000));
            assertThat(bank.balance(BOB)).isEqualTo(Money.of(10_000));
            assertThat(ledger.pot()).isEqualTo(Money.ZERO);
        }
        try (MockedStatic<Bukkit> bukkit = withWorld()) {
            SpeedrunLobby again = new SpeedrunLobby(plugin, settings);
            again.useEntryFees(new SpeedrunEntryFees(ledger, (player, key, pairs) -> { }, message -> { },
                    settings::current));
            again.start(Set.of(ALICE, BOB));
            again.session().orElseThrow().finish("admin-reset");
            assertThat(bank.balance(ALICE)).isEqualTo(Money.of(10_000));
        }
    }

    @Test
    @DisplayName("stopping the plugin mid-run refunds the pot")
    void shutdownRefunds() {
        try (MockedStatic<Bukkit> bukkit = withWorld()) {
            lobby.start(Set.of(ALICE, BOB));
            lobby.shutdown();
            assertThat(bank.balance(ALICE)).isEqualTo(Money.of(10_000));
            assertThat(ledger.pot()).isEqualTo(Money.ZERO);
        }
    }

    @Test
    @DisplayName("with the default fee of 0 the lobby never asks the economy, and none is needed")
    void freeNeedsNoEconomy() {
        Economies.clear();
        settings.set("economy.entry-fee", "0");
        try (MockedStatic<Bukkit> bukkit = withWorld()) {
            assertThat(lobby.start(Set.of(ALICE, BOB))).isEqualTo(SpeedrunLobby.StartOutcome.STARTED);
            lobby.session().orElseThrow().finish("advancement:minecraft:end/kill_dragon");
            assertThat(ledger.pot()).isEqualTo(Money.ZERO);
        }
    }
}
