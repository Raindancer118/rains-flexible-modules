package de.raindancer.modules.economy.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.core.platform.network.NetworkCooldowns;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.rules.BalanceRule;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.store.EconomyDatabase;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A payment made at the bank for a player on another server: the payee is told wherever they are. */
@Tag("database")
class PaymentRemoteTest {

    private static final UUID PAYER = UUID.randomUUID();
    private static final UUID PAYEE = UUID.randomUUID();

    @TempDir
    Path folder;

    private Database database;
    private RainEconomy economy;
    private PaymentService payments;
    private final List<String> told = new ArrayList<>();
    private final OfflinePlayer payee = mock(OfflinePlayer.class);
    private final Messages messages = mock(Messages.class);

    private PaymentService server() {
        return new PaymentService(mock(Plugin.class), economy, messages, mock(Effects.class),
                mock(ChatButtons.class), EconomySettings.DEFAULTS);
    }

    @BeforeEach
    void bank() {
        database = TestDatabases.open(folder.resolve("economy.db"), EconomyDatabase.SCHEMA);
        AccountBook book = new AccountBook(database, new BalanceRule(), () -> 1_000_000L);
        book.load();
        book.open(PAYER, "Alex", Money.of(100_000));
        book.open(PAYEE, "Bea", Money.ZERO);
        economy = new RainEconomy(book, id -> "player", EconomySettings.DEFAULTS);
        when(messages.variant(anyString(), any(Object[].class))).thenReturn(Component.empty());
        payments = server();
        when(payee.getUniqueId()).thenReturn(PAYEE);
        when(payee.getName()).thenReturn("Bea");
        when(payee.isOnline()).thenReturn(false);
        when(payee.getPlayer()).thenReturn(null);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    @DisplayName("a payee who is on another server is told there, with what they received")
    void payeeElsewhereIsTold() {
        payments.remote((player, key, values) -> told.add(player + " " + key + " " + String.join("|", values)));

        var answer = payments.payAcross(PAYER, "Alex", payee, Money.of(2_500), false);

        assertThat(answer).containsEntry("paid", "true");
        assertThat(told).containsExactly(PAYEE + " economy.pay.received amount|"
                + EconomySettings.DEFAULTS.currency().format(economy.balance(PAYEE)) + "|player|Alex");
    }

    @Test
    @DisplayName("nobody is told of a payment that was not made")
    void refusedPaymentTellsNobody() {
        payments.remote((player, key, values) -> told.add(key));

        var answer = payments.payAcross(PAYER, "Alex", payee, Money.of(900_000), false);

        assertThat(answer).containsEntry("paid", "false");
        assertThat(told).isEmpty();
    }

    @Test
    @DisplayName("the wait between payments is the network's: a payment through one service holds up the next through another")
    void waitFollowsThePlayer() {
        NetworkCooldowns.install(new NetworkCooldowns(null, System::currentTimeMillis, task -> { }));
        try {
            PaymentService here = server();
            PaymentService there = server();
            assertThat(here.payAcross(PAYER, "Alex", payee, Money.of(100), false)).containsEntry("paid", "true");

            assertThat(there.payAcross(PAYER, "Alex", payee, Money.of(100), false)).containsEntry("paid", "false");
        } finally {
            NetworkCooldowns.install(null);
        }
    }
}
