package de.raindancer.modules.jobs.service;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.roles.HeldRole;
import de.raindancer.core.social.roles.PlayerRoles;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.jobs.QuestSettings;
import de.raindancer.modules.jobs.model.Quest;
import de.raindancer.modules.jobs.model.QuestDay;
import de.raindancer.modules.jobs.model.QuestTask;
import de.raindancer.modules.jobs.store.QuestBook;
import de.raindancer.modules.jobs.store.QuestCatalogue;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuestServiceTest {

    private static final long DAY = Duration.ofDays(1).toMillis();

    @TempDir
    Path folder;

    private final AtomicLong now = new AtomicLong(20_000L * DAY + 3_600_000L);
    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final Bank bank = new Bank();
    private final UUID ana = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private QuestService service;
    private QuestBook book;

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

    @BeforeEach
    void start() {
        bank.balances.put(ana, Money.of(1_000));
        Economies.provide(mock(org.bukkit.plugin.Plugin.class), bank);
        when(player.getUniqueId()).thenReturn(ana);
        when(player.isOnline()).thenReturn(true);
        when(server.getPlayer(ana)).thenReturn(player);
        QuestCatalogue catalogue = new QuestCatalogue(new YamlStore(folder.resolve("quests.yml")), () ->
                new ByteArrayInputStream("""
                        quests:
                          zombies: { task: kill, things: [ zombie, husk ], amount: 10, pay: 100 }
                          stone: { task: mine, things: [ stone ], amount: 64, pay: 80 }
                          fish: { task: fish, things: [ cod ], amount: 5, pay: 90 }
                          walk: { task: travel, amount: 100, pay: 50 }
                          iron: { task: mine, things: [ iron_ore ], amount: 20, pay: 200, role: miner }
                          gold: { task: mine, things: [ gold_ore ], amount: 10, pay: 200, role: miner }
                          blazes: { task: kill, things: [ blaze ], amount: 5, pay: 200, role: mage }
                        """.getBytes(StandardCharsets.UTF_8)));
        catalogue.reload();
        book = new QuestBook(new YamlStore(folder.resolve("quest-progress.yml")));
        book.load();
        service = new QuestService(server, catalogue, book, messages, Log.of("jobs"), now::get, ZoneOffset.UTC,
                new Random(5), new QuestSettings(true, 3, 2, "5000", 35, 60, 8, 25, 100, true, 1));
    }

    @AfterEach
    void stop() {
        Economies.clear();
        PlayerRoles.clear();
    }

    private static Money coins(String written) {
        return de.raindancer.core.social.economy.Fees.amount(written);
    }

    private void miner() {
        PlayerRoles.provide(mock(org.bukkit.plugin.Plugin.class),
                id -> Optional.of(new HeldRole("miner", "Miner", "#8d99ae")));
    }

    private Quest quest(String template) {
        return service.today(ana).quests().stream().filter(each -> each.template().equals(template)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a day holds three quests for anybody and two of the player's role, none of another's")
    void aDay() {
        miner();
        QuestDay day = service.today(ana);
        assertThat(day.quests()).hasSize(5);
        assertThat(day.quests()).extracting(Quest::template).contains("iron", "gold").doesNotContain("blazes");
        assertThat(service.today(ana)).as("the same all day").isEqualTo(day);
    }

    @Test
    @DisplayName("a poor player gets the quests as written; a rich one bigger and better paid, the role's own with its bonus")
    void tiers() {
        miner();
        assertThat(service.today(ana).tier()).isZero();
        assertThat(quest("iron").amount()).isEqualTo(20);
        assertThat(quest("iron").pay()).as("role bonus").isEqualTo(coins("250"));

        UUID rich = UUID.randomUUID();
        bank.balances.put(rich, coins("40000"));
        QuestDay day = service.today(rich);
        assertThat(day.tier()).isEqualTo(3);
        Quest iron = day.quests().stream().filter(each -> each.template().equals("iron")).findFirst().orElseThrow();
        assertThat(iron.amount()).isEqualTo(49);
        assertThat(iron.pay()).isEqualTo(coins("1024"));
    }

    @Test
    @DisplayName("only what the quest names counts; done, it pays once and counts no further")
    void paysOnce() {
        miner();
        service.today(ana);
        service.progress(player, QuestTask.MINE, "DIAMOND_ORE", 5);
        assertThat(quest("iron").progress()).isZero();
        service.progress(player, QuestTask.MINE, "IRON_ORE", 19);
        assertThat(bank.balance(ana)).isEqualTo(Money.of(1_000));
        service.progress(player, QuestTask.MINE, "IRON_ORE", 5);
        assertThat(quest("iron").state()).isEqualTo(Quest.State.PAID);
        assertThat(bank.balance(ana)).isEqualTo(Money.of(1_000).plus(coins("250")));
        service.progress(player, QuestTask.MINE, "IRON_ORE", 50);
        assertThat(bank.balance(ana)).isEqualTo(Money.of(1_000).plus(coins("250")));
    }

    @Test
    @DisplayName("a payment the treasury refuses is owed, kept over the next day, and paid when it can be")
    void owed() {
        miner();
        service.today(ana);
        bank.treasuryEmpty = true;
        service.progress(player, QuestTask.MINE, "IRON_ORE", 20);
        assertThat(quest("iron").state()).isEqualTo(Quest.State.OWED);
        now.addAndGet(DAY);
        assertThat(service.today(ana).quests()).filteredOn(each -> each.state() == Quest.State.OWED).hasSize(1);
        bank.treasuryEmpty = false;
        service.tick();
        assertThat(bank.balance(ana)).isEqualTo(Money.of(1_000).plus(coins("250")));
        assertThat(service.today(ana).quests()).noneMatch(each -> each.state() == Quest.State.OWED);
    }

    @Test
    @DisplayName("a new day brings new quests, yesterday's left out while there are others")
    void nextDay() {
        var yesterday = service.today(ana).quests().stream().map(Quest::template).toList();
        now.addAndGet(DAY);
        QuestDay day = service.today(ana);
        assertThat(day.before()).containsExactlyInAnyOrderElementsOf(yesterday);
        var templates = day.quests().stream().map(Quest::template).toList();
        assertThat(templates).as("four for anybody, three a day: one new, two again").hasSize(3);
        assertThat(templates.stream().filter(yesterday::contains)).hasSize(2);
    }

    @Test
    @DisplayName("travel adds up the parts of blocks between samples")
    void travel() {
        UUID walker = UUID.randomUUID();
        Player legs = mock(Player.class);
        when(legs.getUniqueId()).thenReturn(walker);
        service.reset(walker);
        book.put(walker, new QuestDay(service.todayDate().toString(), 0,
                java.util.List.of(new Quest("walk", 100, Money.of(50), 0, Quest.State.OPEN)), java.util.List.of()));
        for (int i = 0; i < 4; i++) {
            service.travelled(legs, 2.5);
        }
        assertThat(service.today(walker).quests().getFirst().progress()).isEqualTo(10);
    }

    @Test
    @DisplayName("switched off, nobody gets quests and nothing counts")
    void off() {
        service.settings(new QuestSettings(false, 3, 2, "5000", 35, 60, 8, 25, 100, true, 1));
        assertThat(service.today(ana).quests()).isEmpty();
        service.progress(player, QuestTask.KILL, "ZOMBIE", 50);
        assertThat(bank.balance(ana)).isEqualTo(Money.of(1_000));
    }

    @Test
    @DisplayName("staff can give somebody one more quest by name, at their tier; an unknown one or one held already is refused")
    void give() {
        UUID rich = UUID.randomUUID();
        bank.balances.put(rich, coins("40000"));
        assertThat(service.give(rich, "blazes")).map(Quest::amount).contains(12);
        assertThat(service.today(rich).quests()).extracting(Quest::template).contains("blazes");
        assertThat(service.give(rich, "blazes")).isEmpty();
        assertThat(service.give(rich, "nonsense")).isEmpty();
    }

    @Test
    @DisplayName("one quest a day can be swapped for free for another of its kind, at the same tier; no second time")
    void freeReroll() {
        miner();
        QuestDay day = service.today(ana);
        int general = 0;
        while (service.template(day.quests().get(general)).orElseThrow().forRole()) {
            general++;
        }
        String before = day.quests().get(general).template();
        assertThat(service.rerollsLeft(ana)).isEqualTo(1);
        assertThat(service.reroll(ana, general)).isTrue();
        QuestDay after = service.today(ana);
        Quest swapped = after.quests().get(general);
        assertThat(swapped.template()).isNotEqualTo(before);
        assertThat(service.template(swapped).orElseThrow().forRole()).as("still one for anybody").isFalse();
        assertThat(after.quests()).extracting(Quest::template).doesNotHaveDuplicates();
        assertThat(service.rerollsLeft(ana)).isZero();
        assertThat(service.reroll(ana, general == 0 ? 1 : 0)).isFalse();
    }

    @Test
    @DisplayName("a quest already done or begun is not swapped")
    void notOnceBegun() {
        QuestDay day = service.today(ana);
        Quest first = day.quests().getFirst();
        var template = service.template(first).orElseThrow();
        service.progress(player, template.task(), template.things().items().isEmpty() ? "" : template.things().items().getFirst(), 1);
        assertThat(service.reroll(ana, 0)).isFalse();
        assertThat(service.rerollsLeft(ana)).isEqualTo(1);
    }
}
