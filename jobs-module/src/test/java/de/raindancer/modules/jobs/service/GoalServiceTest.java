package de.raindancer.modules.jobs.service;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.jobs.JobsSettings;
import de.raindancer.modules.jobs.model.Goal;
import de.raindancer.modules.jobs.model.GoalKind;
import de.raindancer.modules.jobs.store.GoalBook;
import de.raindancer.modules.jobs.store.TemplateCatalogue;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoalServiceTest {

    private static final long DAY = Duration.ofDays(1).toMillis();

    @TempDir
    Path folder;

    private final AtomicLong now = new AtomicLong(1_000L * DAY);
    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final Bank bank = new Bank();
    private final UUID ana = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final UUID gone = UUID.randomUUID();
    private GoalService service;
    private GoalBook book;

    /** An economy in a map. */
    private static final class Bank implements Economy {
        final Map<UUID, Money> balances = new HashMap<>();
        boolean treasuryEmpty;

        public String name() {
            return "bank";
        }

        public Currency currency() {
            return Currency.DEFAULT;
        }

        public boolean hasAccount(UUID player) {
            return balances.containsKey(player);
        }

        public Money balance(UUID player) {
            return balances.getOrDefault(player, Money.ZERO);
        }

        public EconomyResult deposit(UUID player, Money amount, String reason) {
            if (treasuryEmpty && amount.isPositive()) {
                return EconomyResult.failed(EconomyResult.Outcome.TREASURY_EMPTY, amount, balance(player));
            }
            balances.merge(player, amount, Money::plus);
            return EconomyResult.done(amount, balances.get(player));
        }

        public EconomyResult withdraw(UUID player, Money amount, String reason) {
            return deposit(player, amount.negate(), reason);
        }

        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            withdraw(from, amount, reason);
            return deposit(to, amount, reason);
        }
    }

    private OfflinePlayer seen(UUID id, long lastSeen) {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getLastSeen()).thenReturn(lastSeen);
        return player;
    }

    @BeforeEach
    void start() {
        bank.balances.put(ana, Money.of(1_000));
        bank.balances.put(bo, Money.of(3_000));
        bank.balances.put(gone, Money.of(900_000));
        Economies.provide(mock(org.bukkit.plugin.Plugin.class), bank);
        OfflinePlayer[] everybody = {seen(ana, now.get()), seen(bo, now.get() - DAY), seen(gone, now.get() - 400 * DAY)};
        when(server.getOfflinePlayers()).thenReturn(everybody);
        when(server.getOnlinePlayers()).thenAnswer(call -> List.of());
        TemplateCatalogue templates = new TemplateCatalogue(new YamlStore(folder.resolve("jobs.yml")), () ->
                new ByteArrayInputStream("""
                        goals:
                          cod:
                            kind: deliver
                            items: [ cod ]
                            start: 100
                            least: 10
                            most: 1000
                            days: 5
                          fishing:
                            kind: fish
                            items: [ cod, salmon ]
                            start: 10
                            least: 5
                            most: 1000
                            days: 5
                          wheat:
                            kind: deliver
                            items: [ wheat ]
                            start: 50
                            days: 4
                          logs:
                            kind: deliver
                            items: [ "*_log" ]
                            start: 50
                            days: 4
                        """.getBytes(StandardCharsets.UTF_8)));
        templates.reload();
        book = new GoalBook(new YamlStore(folder.resolve("goals.yml")));
        book.load();
        service = new GoalService(server, templates, book, messages, Log.of("jobs"), now::get, new Random(7),
                new JobsSettings(3, 100, 30, "0", "", true, true, 100));
    }

    @AfterEach
    void stop() {
        Economies.clear();
    }

    private Goal on(GoalKind kind) {
        return service.goals().stream().filter(goal -> goal.kind() == kind).findFirst().orElseThrow();
    }

    private Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Test
    @DisplayName("the board fills to three goals, a fishing one among the deliveries, no kind twice")
    void fills() {
        service.tick();
        assertThat(service.goals()).hasSize(3);
        assertThat(service.goals()).filteredOn(goal -> goal.kind() == GoalKind.FISH).hasSize(1);
        assertThat(service.goals()).extracting(Goal::template).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the reward is the median of the players seen lately — the rich player who left does not count")
    void reward() {
        // ana 1,000 and bo 3,000 are recent: the median is 2,000; the 900,000 of a player gone a year is left out
        assertThat(service.poolNow()).contains(Money.of(2_000));
    }

    @Test
    @DisplayName("a fishing goal reached pays each their share of the reward, learns, and a new goal goes up")
    void fishingReached() {
        service.tick();
        Goal fishing = on(GoalKind.FISH);
        ItemStack cod = mock(ItemStack.class);
        when(cod.getType()).thenReturn(Material.COD);
        when(cod.getAmount()).thenReturn(1);
        ItemStack boot = mock(ItemStack.class);
        when(boot.getType()).thenReturn(Material.LEATHER_BOOTS);
        when(boot.getAmount()).thenReturn(1);
        Player anaPlaying = player(ana);
        Player boPlaying = player(bo);
        for (int i = 0; i < 6; i++) {
            service.caught(anaPlaying, cod);
        }
        service.caught(boPlaying, boot);
        assertThat(book.goal(fishing.number()).orElseThrow().progress()).as("boots do not count").isEqualTo(6);
        now.addAndGet(DAY / 2);
        for (int i = 0; i < 4; i++) {
            service.caught(boPlaying, cod);
        }
        // reached: 60% / 40% of 2,000
        assertThat(bank.balance(ana)).isEqualTo(Money.of(1_000 + 1_200));
        assertThat(bank.balance(bo)).isEqualTo(Money.of(3_000 + 800));
        assertThat(book.goal(fishing.number())).isEmpty();
        assertThat(book.learned("fishing")).as("reached in half a day of five: the next is twice as big").contains(20);
        assertThat(service.goals()).hasSize(3);
    }

    @Test
    @DisplayName("a goal whose time runs out pays for the part reached and shrinks the next")
    void missed() {
        service.tick();
        Goal fishing = on(GoalKind.FISH);
        book.add(fishing.number(), ana, 4);
        now.addAndGet(6 * DAY);
        service.tick();
        // 4 of 10: 40% of 2,000
        assertThat(bank.balance(ana)).isEqualTo(Money.of(1_000 + 800));
        assertThat(book.learned("fishing")).contains(5);
    }

    @Test
    @DisplayName("while cod is collected the shop may not sell cod; fish for a fishing goal still sells")
    void saleStop() {
        service.tick();
        List<String> delivered = service.goals().stream().filter(goal -> goal.kind() == GoalKind.DELIVER)
                .flatMap(goal -> goal.items().items().stream()).toList();
        String collected = delivered.getFirst().replace("*", "OAK");
        assertThat(service.reason(collected)).isPresent();
        assertThat(service.reason("SALMON")).as("only caught, never handed in").isEmpty();
        service.settings(new JobsSettings(3, 100, 30, "0", "", false, true, 100));
        assertThat(service.reason(collected)).isEmpty();
    }

    @Test
    @DisplayName("staff ending a goal pays for what was reached and puts another up")
    void staffEnd() {
        service.tick();
        Goal goal = service.goals().getFirst();
        book.add(goal.number(), bo, 1);
        assertThat(service.end(goal.number())).isTrue();
        assertThat(service.goals()).hasSize(3).extracting(Goal::number).doesNotContain(goal.number());
        assertThat(bank.balance(bo).isMoreThan(Money.of(3_000))).isTrue();
        assertThat(service.end(goal.number())).isFalse();
    }

    @Test
    @DisplayName("a kind that just ended is not put straight back up while another is free")
    void variety() {
        service.settings(new JobsSettings(1, 100, 30, "0", "", true, true, 100));
        service.tick();
        for (int round = 0; round < 10; round++) {
            Goal goal = service.goals().getFirst();
            service.end(goal.number());
            assertThat(service.goals().getFirst().template()).isNotEqualTo(goal.template());
        }
    }

    @Test
    @DisplayName("goals.pay-scale-percent scales the reward before it is paid and before the board shows it")
    void payScale() {
        service.settings(new JobsSettings(3, 100, 30, "0", "", true, true, 50));
        assertThat(service.poolNow()).contains(Money.of(1_000));
        service.tick();
        Goal goal = service.goals().getFirst();
        book.add(goal.number(), ana, goal.amount());
        service.end(goal.number());
        assertThat(bank.balance(ana)).isEqualTo(Money.of(1_000 + 1_000));
    }

    @Test
    @DisplayName("a payout the treasury refuses stays owed, and is paid on a later tick once it can")
    void treasuryEmpty() {
        service.tick();
        Goal goal = service.goals().getFirst();
        book.add(goal.number(), ana, goal.amount());
        bank.treasuryEmpty = true;
        service.end(goal.number());
        assertThat(bank.balance(ana)).isEqualTo(Money.of(1_000));
        assertThat(book.owed()).containsEntry(ana, Money.of(2_000));
        service.tick();
        assertThat(bank.balance(ana)).as("still refused, still owed").isEqualTo(Money.of(1_000));
        assertThat(book.owed()).containsEntry(ana, Money.of(2_000));
        bank.treasuryEmpty = false;
        service.tick();
        assertThat(bank.balance(ana)).isEqualTo(Money.of(3_000));
        assertThat(book.owed()).isEmpty();
    }
}
