package de.raindancer.modules.guilds.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.combat.Attack;
import de.raindancer.core.world.combat.Verdict;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Fixtures;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildEvent;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.JoinMode;
import de.raindancer.modules.guilds.model.Rank;
import de.raindancer.modules.guilds.model.Relation;
import de.raindancer.modules.guilds.rules.ShareRule;
import de.raindancer.modules.guilds.GuildProgressSettings;
import de.raindancer.modules.guilds.model.Activity;
import de.raindancer.modules.guilds.model.Contract;
import de.raindancer.modules.guilds.model.XpSource;
import de.raindancer.modules.guilds.store.GuildBook;
import de.raindancer.modules.guilds.store.ProgressBook;
import de.raindancer.modules.guilds.store.SqliteProgressStorage;
import de.raindancer.modules.guilds.store.GuildsDatabase;
import de.raindancer.modules.guilds.store.SqliteGuildStorage;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServicesTest {

    private static final UUID ALICE = Fixtures.player("alice");
    private static final UUID BOB = Fixtures.player("bob");
    private static final UUID CAROL = Fixtures.player("carol");
    private static final UUID DAVE = Fixtures.player("dave");
    private static final UUID ERIN = Fixtures.player("erin");
    private static final long DAY = Duration.ofDays(1).toMillis();

    @TempDir
    Path folder;
    private Database database;
    private GuildBook book;
    private FakeEconomy economy;
    private final AtomicLong clock = new AtomicLong(1000 * DAY);
    private final Map<UUID, Long> seen = new HashMap<>();
    private GuildService guilds;
    private BankService bank;
    private RelationService relations;
    private XpService xp;
    private ProgressBook progress;
    private final Map<UUID, Integer> near = new HashMap<>();
    private GuildsSettings settings;

    @BeforeEach
    void setUp() {
        database = Database.open(folder.resolve("guilds.db"), GuildsDatabase.SCHEMA, () -> false);
        book = new GuildBook(new SqliteGuildStorage(database, problem -> { }));
        book.load();
        economy = FakeEconomy.install();
        Server server = mock(Server.class);
        when(server.getOfflinePlayer(any(UUID.class))).thenAnswer(call -> {
            OfflinePlayer player = mock(OfflinePlayer.class);
            when(player.getUniqueId()).thenReturn(call.getArgument(0));
            when(player.getName()).thenReturn("someone");
            return player;
        });
        GuildNotifier notify = new GuildNotifier(server, new Messages(folder.resolve("messages.yml")));
        settings = new GuildsSettings("0", 3, 24, 2, 5, List.of(), 6, 48, 7, 30, true, false,
                1000, 1.5, 10, 3, 1, 10, 1, 2, 2, 0, 0, 2, 250, 25, 1,
                true, "", ShareRule.Mode.CONTRIBUTION, "1000", "100", 0, 0, 2, true);
        guilds = new GuildService(book, notify, Log.of("guilds-test"), clock::get,
                who -> seen.containsKey(who) ? OptionalLong.of(seen.get(who)) : OptionalLong.empty(), settings);
        bank = new BankService(book, notify, Log.of("guilds-test"), clock::get, BankService.current(), settings);
        relations = new RelationService(book, notify, clock::get, settings);
        progress = new ProgressBook(new SqliteProgressStorage(database));
        progress.load();
        book.onDisband(progress::forget);
        xp = new XpService(book, progress, notify, mock(Effects.class), clock::get, settings,
                GuildProgressSettings.DEFAULTS, (player, guild) -> near.getOrDefault(player, 0));
    }

    @AfterEach
    void tearDown() {
        FakeEconomy.uninstall();
        database.close();
    }

    private Guild guild(UUID member) {
        return book.guildOf(member).orElseThrow();
    }

    private Guild found(UUID leader, String name, String tag) {
        assertThat(guilds.found(leader, name, tag)).isEqualTo(GuildOutcome.SUCCESS);
        return guild(leader);
    }

    private void join(Guild guild, UUID player) {
        assertThat(guilds.invite(guild.leader(), player)).isEqualTo(GuildOutcome.SUCCESS);
        assertThat(guilds.acceptInvitation(player, guild.id())).isEqualTo(GuildOutcome.SUCCESS);
    }

    @Nested
    @DisplayName("founding")
    class Founding {

        @Test
        void foundsAndRefuses() {
            Guild elves = found(ALICE, "  The   Elves ", "ELF");
            assertThat(elves.name()).isEqualTo("The Elves");
            assertThat(elves.rankOf(ALICE)).map(Rank::isLeader).contains(true);
            assertThat(guilds.found(ALICE, "Other", "OTH")).isEqualTo(GuildOutcome.ALREADY_IN_GUILD);
            assertThat(guilds.found(BOB, "the elves", "XYZ")).isEqualTo(GuildOutcome.NAME_TAKEN);
            assertThat(guilds.found(BOB, "Dwarves", "elf")).isEqualTo(GuildOutcome.TAG_TAKEN);
            assertThat(guilds.found(BOB, "D", "DW")).isEqualTo(GuildOutcome.NAME_TOO_SHORT);
            assertThat(book.events(elves.id(), Set.of(GuildEvent.Kind.FOUNDED), 5, 0)).isEmpty();
            book.flush();
            assertThat(book.events(elves.id(), Set.of(GuildEvent.Kind.FOUNDED), 5, 0)).hasSize(1);
        }

        @Test
        @DisplayName("founding costs what the settings say, and nothing is founded on credit")
        void price() {
            guilds.settings(withPrice("500"));
            assertThat(guilds.found(ALICE, "Elves", "ELF")).isEqualTo(GuildOutcome.CANNOT_AFFORD);
            assertThat(book.count()).isZero();
            economy.give(ALICE, Money.of(0).plus(guilds.foundingPrice()).minor() + 7);
            assertThat(guilds.found(ALICE, "Elves", "ELF")).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(economy.balance(ALICE).minor()).isEqualTo(7);
        }

        private GuildsSettings withPrice(String price) {
            return new GuildsSettings(price, 3, 24, 2, 5, List.of(), 6, 48, 7, 30, true, false,
                    1000, 1.5, 10, 3, 1, 10, 1, 2, 2, 0, 0, 2, 250, 25, 1,
                    true, "", ShareRule.Mode.CONTRIBUTION, "1000", "100", 0, 0, 2, true);
        }
    }

    @Nested
    @DisplayName("getting in")
    class GettingIn {

        @Test
        @DisplayName("an invitation is accepted once; the newcomer starts at the bottom")
        void invite() {
            Guild elves = found(ALICE, "Elves", "ELF");
            assertThat(guilds.invite(ALICE, BOB)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guilds.invite(ALICE, BOB)).isEqualTo(GuildOutcome.ALREADY_INVITED);
            assertThat(guilds.invitationsOf(BOB)).hasSize(1);
            assertThat(guilds.acceptInvitation(BOB, elves.id())).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guilds.acceptInvitation(BOB, elves.id())).isEqualTo(GuildOutcome.NOT_INVITED);
            assertThat(guild(BOB).rankOf(BOB)).map(Rank::id).contains(Rank.RECRUIT);
            assertThat(guilds.invite(BOB, CAROL)).as("a recruit may not invite").isEqualTo(GuildOutcome.NOT_ALLOWED);
            assertThat(guilds.invite(ALICE, BOB)).isEqualTo(GuildOutcome.ALREADY_IN_GUILD);
        }

        @Test
        @DisplayName("an invitation runs out")
        void expires() {
            Guild elves = found(ALICE, "Elves", "ELF");
            guilds.invite(ALICE, BOB);
            clock.addAndGet(Duration.ofHours(49).toMillis());
            assertThat(guilds.acceptInvitation(BOB, elves.id())).isEqualTo(GuildOutcome.NOT_INVITED);
        }

        @Test
        @DisplayName("a guild at its member cap takes nobody, by any door")
        void full() {
            Guild elves = found(ALICE, "Elves", "ELF");
            join(elves, BOB);
            join(elves, CAROL);
            assertThat(guilds.invite(ALICE, DAVE)).isEqualTo(GuildOutcome.FULL);
            guilds.joinMode(ALICE, JoinMode.OPEN);
            assertThat(guilds.request(DAVE, elves.id())).isEqualTo(GuildOutcome.FULL);
            assertThat(guild(ALICE).size()).isEqualTo(3);
        }

        @Test
        @DisplayName("open guilds are joined, invite-only ones refuse, others take applications")
        void modes() {
            Guild elves = found(ALICE, "Elves", "ELF");
            assertThat(guilds.request(BOB, elves.id())).isEqualTo(GuildOutcome.CLOSED);
            guilds.joinMode(ALICE, JoinMode.APPLY);
            assertThat(guilds.request(BOB, elves.id())).isEqualTo(GuildOutcome.ASKED);
            assertThat(guilds.request(BOB, elves.id())).isEqualTo(GuildOutcome.ALREADY_APPLIED);
            assertThat(guilds.applicationsTo(guild(ALICE))).hasSize(1);
            assertThat(guilds.acceptApplication(ALICE, BOB)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guilds.acceptApplication(ALICE, BOB)).isEqualTo(GuildOutcome.NO_APPLICATION);
            guilds.joinMode(ALICE, JoinMode.OPEN);
            assertThat(guilds.request(CAROL, elves.id())).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guild(CAROL).id()).isEqualTo(elves.id());
        }

        @Test
        @DisplayName("joining one guild drops what was open with others")
        void questionsGo() {
            Guild elves = found(ALICE, "Elves", "ELF");
            Guild dwarves = found(DAVE, "Dwarves", "DWF");
            guilds.invite(ALICE, BOB);
            guilds.invite(DAVE, BOB);
            assertThat(guilds.acceptInvitation(BOB, dwarves.id())).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guilds.invitationsOf(BOB)).isEmpty();
            assertThat(guilds.acceptInvitation(BOB, elves.id())).isEqualTo(GuildOutcome.NOT_INVITED);
        }
    }

    @Nested
    @DisplayName("the ladder")
    class Ladder {

        @Test
        void promoteKickHandOver() {
            Guild elves = found(ALICE, "Elves", "ELF");
            join(elves, BOB);
            join(elves, CAROL);
            assertThat(guilds.step(ALICE, BOB, true)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guilds.step(ALICE, BOB, true)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guild(BOB).rankOf(BOB)).map(Rank::id).contains(Rank.OFFICER);
            assertThat(guilds.step(ALICE, BOB, true)).as("never to leader by promotion").isEqualTo(GuildOutcome.NO_SUCH_RANK);
            assertThat(guilds.step(BOB, CAROL, true)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guilds.step(BOB, CAROL, true)).as("not to the officer's own rank").isEqualTo(GuildOutcome.OUTRANKED);
            assertThat(guilds.kick(CAROL, BOB)).isEqualTo(GuildOutcome.NOT_ALLOWED);
            assertThat(guilds.kick(BOB, ALICE)).isEqualTo(GuildOutcome.OUTRANKED);
            assertThat(guilds.leave(ALICE, bank)).isEqualTo(GuildOutcome.LEADER_MUST_HAND_OVER);
            assertThat(guilds.handOver(ALICE, BOB)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guild(ALICE).leader()).isEqualTo(BOB);
            assertThat(guild(ALICE).rankOf(ALICE)).map(Rank::id).contains(Rank.OFFICER);
            assertThat(guilds.kick(BOB, CAROL)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(book.guildOf(CAROL)).isEmpty();
            assertThat(guilds.leave(ALICE, bank)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guilds.leave(BOB, bank)).as("the last one out disbands").isEqualTo(GuildOutcome.SUCCESS);
            assertThat(book.count()).isZero();
        }

        @Test
        @DisplayName("a leader gone for longer than the setting hands over to the most senior active member")
        void succession() {
            Guild elves = found(ALICE, "Elves", "ELF");
            join(elves, BOB);
            join(elves, CAROL);
            guilds.step(ALICE, CAROL, true);
            seen.put(ALICE, clock.get());
            seen.put(BOB, clock.get());
            seen.put(CAROL, clock.get());
            assertThat(guilds.succeed()).isZero();
            clock.addAndGet(31 * DAY);
            seen.put(CAROL, clock.get());
            seen.put(BOB, clock.get());
            assertThat(guilds.succeed()).isEqualTo(1);
            assertThat(guild(ALICE).leader()).isEqualTo(CAROL);
            assertThat(guilds.succeed()).isZero();
        }

        @Test
        @DisplayName("ranks are made low, edited only below oneself, and never with a permission one lacks")
        void ranks() {
            Guild elves = found(ALICE, "Elves", "ELF");
            join(elves, BOB);
            assertThat(guilds.createRank(ALICE, "Elder")).isEqualTo(GuildOutcome.SUCCESS);
            Guild now = guild(ALICE);
            Rank elder = now.rank("elder").orElseThrow();
            assertThat(elder.weight()).isGreaterThan(now.rank(Rank.RECRUIT).orElseThrow().weight())
                    .isLessThan(now.rank(Rank.MEMBER).orElseThrow().weight());
            assertThat(guilds.toggle(ALICE, "elder", GuildPermission.KICK)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guild(ALICE).rank("elder").orElseThrow().may(GuildPermission.KICK)).isTrue();
            assertThat(guilds.moveRank(ALICE, "elder", true)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guild(ALICE).rank("elder").orElseThrow().weight()).isGreaterThan(guild(ALICE).rank(Rank.MEMBER).orElseThrow().weight());
            assertThat(guilds.setRank(ALICE, BOB, "elder")).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guilds.deleteRank(ALICE, "elder")).isEqualTo(GuildOutcome.RANK_IN_USE);
            assertThat(guilds.renameRank(ALICE, "elder", "Old <red>One")).isEqualTo(GuildOutcome.NAME_CHARACTERS);
            assertThat(guilds.renameRank(ALICE, "elder", "Old One")).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guilds.renameRank(ALICE, "elder", "officer")).as("another rank's name, any case").isEqualTo(GuildOutcome.NAME_TAKEN);
            assertThat(guilds.createRank(ALICE, "MEMBER")).isEqualTo(GuildOutcome.NAME_TAKEN);
            assertThat(guilds.editRank(ALICE, Rank.LEADER, rank -> rank, "")).isEqualTo(GuildOutcome.LEADER_RANK);
            for (int each = 0; each < 3; each++) {
                guilds.createRank(ALICE, "Extra " + each);
            }
            assertThat(guilds.createRank(ALICE, "One too many")).isEqualTo(GuildOutcome.TOO_MANY_RANKS);
        }
    }

    @Nested
    @DisplayName("the bank")
    class Bank {

        @Test
        @DisplayName("nobody sets a rank's daily limit above their own, so an officer cannot open the bank to a friend")
        void limitsCannotOutgrowTheSetter() {
            Guild elves = found(ALICE, "Elves", "ELF");
            join(elves, BOB);
            guilds.setRank(ALICE, BOB, Rank.OFFICER);
            guilds.toggle(ALICE, Rank.OFFICER, GuildPermission.MANAGE_RANKS);
            long officerLimit = guild(ALICE).rank(Rank.OFFICER).orElseThrow().dailyLimit();
            assertThat(guilds.limitRank(BOB, Rank.MEMBER, -1)).as("unlimited, above the officer's own")
                    .isEqualTo(GuildOutcome.OUTRANKED);
            assertThat(guilds.limitRank(BOB, Rank.MEMBER, officerLimit + 1)).isEqualTo(GuildOutcome.OUTRANKED);
            assertThat(guilds.limitRank(BOB, Rank.MEMBER, officerLimit)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guilds.limitRank(ALICE, Rank.MEMBER, -1)).as("the leader has no limit to stay under")
                    .isEqualTo(GuildOutcome.SUCCESS);
        }

        @Test
        @DisplayName("paying in moves money from the player to the guild and counts as their contribution")
        void deposit() {
            Guild elves = found(ALICE, "Elves", "ELF");
            economy.give(ALICE, 1000);
            assertThat(bank.deposit(ALICE, Money.of(400))).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(bank.deposit(ALICE, Money.of(4000))).isEqualTo(GuildOutcome.CANNOT_AFFORD);
            assertThat(bank.deposit(ALICE, Money.of(-1))).isEqualTo(GuildOutcome.INVALID_AMOUNT);
            assertThat(economy.balance(ALICE).minor()).isEqualTo(600);
            assertThat(guild(ALICE).bank()).isEqualTo(400);
            assertThat(guild(ALICE).member(ALICE).orElseThrow().deposited()).isEqualTo(400);
            assertThat(bank.deposit(BOB, Money.of(1))).isEqualTo(GuildOutcome.NO_GUILD);
            assertThat(elves).isNotNull();
        }

        @Test
        @DisplayName("taking out respects the rank's daily limit, which starts again the next day")
        void withdraw() {
            Guild elves = found(ALICE, "Elves", "ELF");
            join(elves, BOB);
            guilds.step(ALICE, BOB, true);
            long limit = de.raindancer.core.social.economy.Fees.amount("100").minor();
            economy.give(ALICE, 10 * limit);
            bank.deposit(ALICE, Money.of(5 * limit));
            assertThat(bank.withdraw(BOB, Money.of(limit * 6 / 10))).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(bank.withdraw(BOB, Money.of(limit * 6 / 10))).isEqualTo(GuildOutcome.OVER_DAILY_LIMIT);
            assertThat(bank.withdraw(BOB, Money.of(limit * 4 / 10))).isEqualTo(GuildOutcome.SUCCESS);
            clock.addAndGet(DAY);
            assertThat(bank.withdraw(BOB, Money.of(limit))).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(bank.withdraw(ALICE, Money.of(99 * limit))).isEqualTo(GuildOutcome.BANK_EMPTY);
            assertThat(economy.balance(BOB).minor()).isEqualTo(2 * limit);
            assertThat(guild(ALICE).bank()).isEqualTo(3 * limit);
        }

        @Test
        @DisplayName("when paying the player fails, the guild gets its money back")
        void failedPayout() {
            found(ALICE, "Elves", "ELF");
            economy.give(ALICE, 1000);
            bank.deposit(ALICE, Money.of(1000));
            economy.depositsFail(true);
            assertThat(bank.withdraw(ALICE, Money.of(300))).isEqualTo(GuildOutcome.ECONOMY_DOWN);
            assertThat(guild(ALICE).bank()).isEqualTo(1000);
            assertThat(guild(ALICE).member(ALICE).orElseThrow().withdrawn()).isZero();
        }

        @Test
        @DisplayName("eight members taking out at once never take more than the bank or their limits")
        void concurrentWithdrawals() throws InterruptedException {
            Guild elves = found(ALICE, "Elves", "ELF");
            guilds.settings(settings);
            List<UUID> members = List.of(BOB, CAROL);
            join(elves, BOB);
            join(elves, CAROL);
            guilds.step(ALICE, BOB, true);
            guilds.step(ALICE, CAROL, true);
            long limit = de.raindancer.core.social.economy.Fees.amount("100").minor();
            economy.give(ALICE, 3 * limit / 2);
            bank.deposit(ALICE, Money.of(3 * limit / 2));
            long before = economy.total() + guild(ALICE).bank();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            for (int thread = 0; thread < 8; thread++) {
                UUID who = members.get(thread % members.size());
                pool.submit(() -> {
                    for (int round = 0; round < 50; round++) {
                        bank.withdraw(who, Money.of(limit / 13));
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            assertThat(economy.balance(BOB).minor()).isLessThanOrEqualTo(limit);
            assertThat(economy.balance(CAROL).minor()).isLessThanOrEqualTo(limit);
            assertThat(economy.balance(BOB).minor() + economy.balance(CAROL).minor()).isGreaterThan(limit);
            assertThat(guild(ALICE).bank()).isGreaterThanOrEqualTo(0);
            assertThat(economy.total() + guild(ALICE).bank()).as("no money made or lost").isEqualTo(before);
        }

        @Test
        @DisplayName("disbanding shares the bank by contribution, exactly; a bank that cannot be paid keeps the guild")
        void disband() {
            Guild elves = found(ALICE, "Elves", "ELF");
            join(elves, BOB);
            economy.give(ALICE, 300).give(BOB, 100);
            bank.deposit(ALICE, Money.of(300));
            bank.deposit(BOB, Money.of(100));
            economy.depositsFail(true);
            assertThat(guilds.disband(ALICE, elves.id(), false, bank)).isEqualTo(GuildOutcome.ECONOMY_DOWN);
            assertThat(book.guild(elves.id())).isPresent();
            assertThat(guild(ALICE).bank()).isEqualTo(400);
            economy.depositsFail(false);
            assertThat(guilds.disband(BOB, elves.id(), false, bank)).isEqualTo(GuildOutcome.NOT_ALLOWED);
            assertThat(guilds.disband(ALICE, elves.id(), false, bank)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(economy.balance(ALICE).minor()).isEqualTo(300);
            assertThat(economy.balance(BOB).minor()).isEqualTo(100);
            assertThat(book.guildOf(BOB)).isEmpty();
        }
    }

    @Nested
    @DisplayName("relations")
    class Relations {

        @Test
        @DisplayName("an alliance takes both guilds, and asking each other forms it")
        void alliance() {
            Guild elves = found(ALICE, "Elves", "ELF");
            Guild dwarves = found(BOB, "Dwarves", "DWF");
            assertThat(relations.askAlliance(ALICE, dwarves.id())).isEqualTo(GuildOutcome.ASKED);
            assertThat(guild(ALICE).relationTo(dwarves.id())).isEqualTo(Relation.NEUTRAL);
            assertThat(relations.alliancesAskedOf(guild(BOB))).hasSize(1);
            assertThat(relations.askAlliance(BOB, elves.id())).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guild(ALICE).relationTo(dwarves.id())).isEqualTo(Relation.ALLY);
            assertThat(guild(BOB).relationTo(elves.id())).isEqualTo(Relation.ALLY);
            assertThat(relations.askAlliance(ALICE, dwarves.id())).isEqualTo(GuildOutcome.ALREADY_ALLIED);
            Guild orcs = found(CAROL, "Orcs", "ORC");
            assertThat(relations.askAlliance(ALICE, orcs.id())).as("one ally at level 1").isEqualTo(GuildOutcome.TOO_MANY_ALLIES);
            assertThat(relations.endAlliance(BOB, elves.id())).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guild(ALICE).relations()).isEmpty();
            // Asking each other forms an alliance only when the other side has a slot too.
            relations.askAlliance(CAROL, dwarves.id());
            relations.acceptAlliance(BOB, orcs.id());
            assertThat(guild(BOB).allies()).isEqualTo(1);
            assertThat(relations.askAlliance(BOB, elves.id())).isEqualTo(GuildOutcome.TOO_MANY_ALLIES);
            assertThat(relations.askAlliance(ALICE, dwarves.id())).as("the dwarves have no slot left").isEqualTo(GuildOutcome.TOO_MANY_ALLIES);
            assertThat(guild(BOB).allies()).isEqualTo(1);
            assertThat(guild(ALICE).relations()).isEmpty();
            assertThat(relations.askAlliance(ALICE, elves.id())).isEqualTo(GuildOutcome.SAME_GUILD);
        }

        @Test
        @DisplayName("a rivalry is one-sided and ends an alliance on both sides")
        void rivalry() {
            Guild elves = found(ALICE, "Elves", "ELF");
            Guild dwarves = found(BOB, "Dwarves", "DWF");
            relations.askAlliance(ALICE, dwarves.id());
            relations.acceptAlliance(BOB, elves.id());
            assertThat(relations.declareRival(ALICE, dwarves.id())).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guild(ALICE).relationTo(dwarves.id())).isEqualTo(Relation.RIVAL);
            assertThat(guild(BOB).relationTo(elves.id())).isEqualTo(Relation.NEUTRAL);
            assertThat(relations.endRivalry(BOB, elves.id())).isEqualTo(GuildOutcome.NOT_RIVALS);
            assertThat(relations.endRivalry(ALICE, dwarves.id())).isEqualTo(GuildOutcome.SUCCESS);
            join(guild(ALICE), CAROL);
            assertThat(relations.declareRival(CAROL, dwarves.id())).as("a recruit cannot").isEqualTo(GuildOutcome.NOT_ALLOWED);
        }

        @Test
        @DisplayName("members of one guild cannot hurt each other; allies only when the attacker's guild allows")
        void friendlyFire() {
            Guild elves = found(ALICE, "Elves", "ELF");
            join(elves, CAROL);
            Guild dwarves = found(BOB, "Dwarves", "DWF");
            FriendlyFireService fire = new FriendlyFireService(book);
            assertThat(fire.judge(Attack.between(ALICE, CAROL, "world"))).isEqualTo(Verdict.PROTECTED);
            assertThat(fire.judge(Attack.between(ALICE, BOB, "world"))).isNull();
            assertThat(fire.judge(Attack.between(ALICE, ERIN, "world"))).isNull();
            relations.askAlliance(ALICE, dwarves.id());
            relations.acceptAlliance(BOB, elves.id());
            assertThat(fire.judge(Attack.between(ALICE, BOB, "world"))).isEqualTo(Verdict.PROTECTED);
            guilds.friendlyFire(ALICE, true, true);
            assertThat(fire.judge(Attack.between(ALICE, CAROL, "world"))).isNull();
            assertThat(fire.judge(Attack.between(ALICE, BOB, "world"))).isNull();
            assertThat(fire.judge(Attack.between(BOB, ALICE, "world"))).as("the dwarves did not allow it").isEqualTo(Verdict.PROTECTED);
        }
    }

    @Nested
    @DisplayName("experience")
    class Experience {

        @Test
        @DisplayName("members earn their guild levels, and a level up raises the member cap")
        void levels() {
            Guild elves = found(ALICE, "Elves", "ELF");
            assertThat(guilds.memberCap(elves)).isEqualTo(3);
            assertThat(xp.activity(ALICE, new Activity(XpSource.BOSS, "wither", 4))).as("4 bosses, 250 each, no cap").isEqualTo(1000);
            assertThat(guilds.level(guild(ALICE))).isEqualTo(2);
            assertThat(guilds.memberCap(guild(ALICE))).isEqualTo(4);
            assertThat(guild(ALICE).member(ALICE).orElseThrow().xp()).isEqualTo(1000);
            assertThat(xp.activity(BOB, Activity.of(XpSource.BOSS, "wither"))).as("no guild, nothing counted").isZero();
            assertThat(progress.totals(elves.id().value())).containsEntry(XpSource.BOSS, 1000L);
            book.flush();
            assertThat(book.events(elves.id(), Set.of(GuildEvent.Kind.LEVEL_UP), 5, 0)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("styled tag and name")
    class Styles {

        private final de.raindancer.core.ui.text.NameStyle gold = de.raindancer.core.ui.text.TypedStyles
                .read(List.of("gold", "#ff0000", "bold"), word -> null).style().orElseThrow();

        @Test
        @DisplayName("locked below the level the server set; open from it")
        void fromLevelTwo() {
            found(ALICE, "Elves", "ELF");
            assertThat(guilds.style(ALICE, true, gold)).isEqualTo(GuildOutcome.STYLE_LOCKED);
            xp.activity(ALICE, new Activity(XpSource.BOSS, "wither", 4));
            assertThat(guilds.level(guild(ALICE))).isEqualTo(2);
            assertThat(guilds.style(ALICE, true, gold)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guilds.style(ALICE, false, gold)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guild(ALICE).tagStyle()).isEqualTo(gold);
            assertThat(guild(ALICE).nameStyle()).isEqualTo(gold);
        }

        @Test
        @DisplayName("only who may edit the guild's info may paint it; reset takes it off")
        void permissionAndReset() {
            Guild elves = found(ALICE, "Elves", "ELF");
            join(elves, BOB);
            xp.activity(ALICE, new Activity(XpSource.BOSS, "wither", 4));
            assertThat(guilds.style(BOB, true, gold)).isEqualTo(GuildOutcome.NOT_ALLOWED);
            guilds.style(ALICE, true, gold);
            assertThat(guilds.style(ALICE, true, de.raindancer.core.ui.text.NameStyle.NONE)).isEqualTo(GuildOutcome.SUCCESS);
            assertThat(guild(ALICE).tagStyle().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the tag is drawn in its style, still reading [TAG]; without one, in the guild's colour")
        void drawn() {
            found(ALICE, "Elves", "ELF");
            assertThat(de.raindancer.core.ui.text.Text.plain(GuildNotifier.tag(guild(ALICE)))).isEqualTo("[ELF]");
            xp.activity(ALICE, new Activity(XpSource.BOSS, "wither", 4));
            guilds.style(ALICE, true, gold);
            net.kyori.adventure.text.Component tag = GuildNotifier.tag(guild(ALICE));
            assertThat(de.raindancer.core.ui.text.Text.plain(tag)).isEqualTo("[ELF]");
            assertThat(tag.children()).as("a gradient is drawn letter by letter").isNotEmpty();
        }

        @Test
        @DisplayName("a style survives being read back from guilds.db")
        void persisted() {
            found(ALICE, "Elves", "ELF");
            xp.activity(ALICE, new Activity(XpSource.BOSS, "wither", 4));
            guilds.style(ALICE, false, gold);
            assertThat(book.flush()).isTrue();
            GuildBook again = new GuildBook(new SqliteGuildStorage(database, problem -> { }));
            again.load();
            assertThat(again.guildOf(ALICE).orElseThrow().nameStyle()).isEqualTo(gold);
        }
    }

    @Nested
    @DisplayName("the sidebar")
    class SidebarLines {

        @Test
        @DisplayName("a member sees the guild, its level with the experience to the next, and the bank")
        void lines() {
            GuildSidebar sidebar = new GuildSidebar(book, guilds, bank::format, () -> settings);
            assertThat(sidebar.lines(ALICE)).as("no guild, no lines").isEmpty();
            found(ALICE, "Elves", "ELF");
            xp.activity(ALICE, Activity.of(XpSource.BOSS, "wither"));
            economy.give(ALICE, 100);
            bank.deposit(ALICE, Money.of(100));
            List<String> lines = sidebar.lines(ALICE).stream().map(de.raindancer.core.ui.text.Text::plain).toList();
            assertThat(lines).hasSize(3);
            assertThat(lines.get(0)).contains("[ELF]").contains("Elves");
            assertThat(lines.get(1)).contains("Level 1").contains("250").contains("1,000");
            assertThat(lines.get(2)).contains("Bank").contains(bank.format(100));
        }

        @Test
        @DisplayName("switched off by the server, nobody gets the lines")
        void off() {
            found(ALICE, "Elves", "ELF");
            GuildsSettings off = new GuildsSettings("0", 3, 24, 2, 5, List.of(), 6, 48, 7, 30, true, false,
                    1000, 1.5, 10, 3, 1, 10, 1, 2, 2, 0, 0, 2, 250, 25, 1,
                    true, "", ShareRule.Mode.CONTRIBUTION, "1000", "100", 0, 0, 2, false);
            assertThat(new GuildSidebar(book, guilds, bank::format, () -> off).lines(ALICE)).isEmpty();
        }
    }

    @Nested
    @DisplayName("more ways to level up")
    class MoreWays {

        @Test
        @DisplayName("mining counts by rarity, a boost and guildmates nearby add their percent")
        void bonuses() {
            found(ALICE, "Elves", "ELF");
            assertThat(xp.activity(ALICE, Activity.of(XpSource.MINE, "diamond"))).isEqualTo(8);
            near.put(ALICE, 2);
            assertThat(xp.activity(ALICE, new Activity(XpSource.MINE, "diamond", 10))).as("+20% together").isEqualTo(96);
            xp.boost(50, Duration.ofHours(1), BOB);
            assertThat(xp.activity(ALICE, new Activity(XpSource.MINE, "diamond", 10))).as("+50% boost +20%").isEqualTo(136);
            clock.addAndGet(Duration.ofHours(2).toMillis());
            near.clear();
            assertThat(xp.activity(ALICE, new Activity(XpSource.MINE, "diamond", 10))).as("the boost ran out").isEqualTo(80);
            assertThat(progress.totals(guild(ALICE).id().value()).get(XpSource.MINE)).isEqualTo(8 + 96 + 136 + 80);
        }

        @Test
        @DisplayName("a rival's defeat counts once an hour per victim, and only between rivals")
        void rivals() {
            Guild elves = found(ALICE, "Elves", "ELF");
            Guild orcs = found(BOB, "Orcs", "ORC");
            assertThat(xp.rivalDefeated(ALICE, BOB)).as("not rivals").isZero();
            relations.declareRival(BOB, elves.id());
            assertThat(xp.rivalDefeated(ALICE, BOB)).as("rivals either way round").isEqualTo(50);
            assertThat(xp.rivalDefeated(ALICE, BOB)).as("the same victim again").isZero();
            clock.addAndGet(Duration.ofMinutes(61).toMillis());
            assertThat(xp.rivalDefeated(ALICE, BOB)).isEqualTo(50);
            assertThat(xp.rivalDefeated(ALICE, CAROL)).as("guildless").isZero();
            assertThat(orcs).isNotNull();
        }

        @Test
        @DisplayName("contracts are dealt once a week, count what they ask for, and pay their reward once")
        void contracts() {
            Guild elves = found(ALICE, "Elves", "ELF");
            List<Contract> week = xp.contracts(guild(ALICE));
            assertThat(week).hasSize(3);
            assertThat(xp.contracts(guild(ALICE))).isEqualTo(week);
            Contract first = week.getFirst();
            long before = guild(ALICE).xp();
            xp.activity(ALICE, new Activity(first.source(), first.what().isEmpty() ? "zombie" : first.what(), first.goal() - 1));
            assertThat(xp.contracts(guild(ALICE)).getFirst().done()).isFalse();
            xp.activity(ALICE, new Activity(first.source(), first.what().isEmpty() ? "zombie" : first.what(), 5));
            Contract done = xp.contracts(guild(ALICE)).getFirst();
            assertThat(done.done()).isTrue();
            assertThat(progress.totals(elves.id().value())).containsEntry(XpSource.CONTRACT, first.reward());
            xp.activity(ALICE, new Activity(first.source(), first.what().isEmpty() ? "zombie" : first.what(), 5));
            assertThat(progress.totals(elves.id().value())).as("paid once").containsEntry(XpSource.CONTRACT, first.reward());
            assertThat(guild(ALICE).xp()).isGreaterThanOrEqualTo(before + first.reward());
            clock.addAndGet(Duration.ofDays(7).toMillis());
            assertThat(xp.contracts(guild(ALICE))).as("a new week, new contracts").allMatch(each -> !each.done() && each.progress() == 0);
        }

        @Test
        @DisplayName("eight members finishing the same contract at once collect its reward once")
        void contractRace() throws InterruptedException {
            Guild elves = found(ALICE, "Elves", "ELF");
            for (UUID member : List.of(BOB, CAROL)) {
                join(elves, member);
            }
            Contract first = xp.contracts(guild(ALICE)).getFirst();
            String what = first.what().isEmpty() ? "zombie" : first.what();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<UUID> who = List.of(ALICE, BOB, CAROL);
            for (int thread = 0; thread < 8; thread++) {
                UUID member = who.get(thread % who.size());
                pool.submit(() -> {
                    for (int round = 0; round < 200; round++) {
                        xp.activity(member, new Activity(first.source(), what, 1));
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            assertThat(progress.totals(elves.id().value()).getOrDefault(XpSource.CONTRACT, 0L))
                    .isEqualTo(first.goal() <= 1600 ? first.reward() : 0L);
        }

        @Test
        @DisplayName("deposits earn nothing by default, a disbanded guild's progress goes with it, and it all survives a restart")
        void depositsAndPersistence() {
            Guild elves = found(ALICE, "Elves", "ELF");
            xp.activity(ALICE, Activity.of(XpSource.DEPOSIT, 5));
            assertThat(progress.totals(elves.id().value())).doesNotContainKey(XpSource.DEPOSIT);
            xp.activity(ALICE, Activity.of(XpSource.FISH, 4));
            xp.contracts(guild(ALICE));
            xp.boost(25, Duration.ofHours(3), ALICE);
            assertThat(progress.flush()).isTrue();
            ProgressBook again = new ProgressBook(new SqliteProgressStorage(database));
            again.load();
            assertThat(again.totals(elves.id().value())).containsEntry(XpSource.FISH, 12L);
            assertThat(again.boost().percent()).isEqualTo(25);
            long week = de.raindancer.modules.guilds.rules.ContractRule.weekOf(clock.get());
            assertThat(again.contracts(elves.id().value(), week, List::of)).hasSize(3);
            guilds.disband(ALICE, elves.id(), false, bank);
            assertThat(progress.totals(elves.id().value())).isEmpty();
            progress.flush();
            ProgressBook third = new ProgressBook(new SqliteProgressStorage(database));
            third.load();
            assertThat(third.totals(elves.id().value())).isEmpty();
        }
    }

    @Test
    @DisplayName("a guild is a Core group: leader, officers (who may edit its info) as managers, every member")
    void asGroup() {
        Guild elves = found(ALICE, "Elves", "ELF");
        join(elves, BOB);
        de.raindancer.core.social.team.Groups.Group group = guilds.groupOf(guild(ALICE)).orElseThrow();
        assertThat(group.id()).isEqualTo("guild:" + elves.id().value() + ":" + elves.founded());
        assertThat(group.leader()).isEqualTo(ALICE);
        assertThat(group.isManager(ALICE)).isTrue();
        assertThat(group.isManager(BOB)).as("a recruit does not manage").isFalse();
        assertThat(group.isMember(BOB)).isTrue();
        assertThat(guilds.groupById(group.id())).contains(group);
        assertThat(guilds.groupById("guild:nothing")).isEmpty();
    }

    @Test
    @DisplayName("a guild founded again under a disbanded guild's name is a different group — it inherits no claims")
    void refoundedIsANewGroup() {
        Guild first = found(ALICE, "Elves", "ELF");
        String before = guilds.groupOf(first).orElseThrow().id();
        assertThat(guilds.disband(ALICE, first.id(), false, bank)).isEqualTo(GuildOutcome.SUCCESS);
        clock.addAndGet(1_000);
        Guild again = found(ALICE, "Elves", "ELF");
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(guilds.groupOf(again).orElseThrow().id()).isNotEqualTo(before);
        assertThat(guilds.groupById(before)).as("the old group is gone for good").isEmpty();
    }

    @Test
    @DisplayName("a guild's tag names its members as a player group — @ELF — whatever the case typed")
    void tagAsGroup() {
        Guild elves = found(ALICE, "Elves", "ELF");
        join(elves, BOB);
        assertThat(guilds.membersByTag("elf")).hasValueSatisfying(members -> assertThat(members).containsExactlyInAnyOrder(ALICE, BOB));
        assertThat(guilds.membersByTag("NOPE")).isEmpty();
        assertThat(guilds.tags()).containsExactly("ELF");
    }

    @Test
    @DisplayName("everything a test did survives being read back")
    void persists() {
        Guild elves = found(ALICE, "Elves", "ELF");
        join(elves, BOB);
        economy.give(ALICE, 100);
        bank.deposit(ALICE, Money.of(100));
        assertThat(book.flush()).isTrue();
        GuildBook again = new GuildBook(new SqliteGuildStorage(database, problem -> { }));
        again.load();
        assertThat(again.guildOf(BOB).map(Guild::bank)).contains(100L);
        assertThat(Optional.of(again.all().size())).contains(1);
    }
}
