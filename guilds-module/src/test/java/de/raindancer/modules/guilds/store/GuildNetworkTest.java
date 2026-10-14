package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.modules.guilds.model.Fixtures;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.Invitation;
import de.raindancer.modules.guilds.model.Member;
import de.raindancer.modules.guilds.model.Rank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static de.raindancer.modules.guilds.model.Fixtures.LEADER;
import static de.raindancer.modules.guilds.model.Fixtures.MEMBER;
import static org.assertj.core.api.Assertions.assertThat;

/** Two servers of one network over one database: what one does, the other sees, and what must happen once happens once. */
@Tag("database")
class GuildNetworkTest {

    @TempDir
    Path folder;

    private TwoServers servers;
    private GuildBook smp;
    private GuildBook arena;

    @BeforeEach
    void start() {
        servers = new TwoServers(folder);
        smp = servers.book("smp");
        arena = servers.book("arena");
    }

    @AfterEach
    void stop() {
        servers.close();
    }

    /** Runs the two at the same moment and answers what each returned. */
    private static <T> List<T> together(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> running = new ArrayList<>();
        for (Callable<T> each : List.of(first, second)) {
            running.add(pool.submit(() -> {
                go.await();
                return each.call();
            }));
        }
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> each : running) {
            results.add(each.get());
        }
        pool.shutdown();
        return results;
    }

    @Test
    @DisplayName("a guild founded on one server is on the other, with every change made to it there")
    void oneNetwork() {
        Guild elves = Fixtures.guild("Elves");
        assertThat(smp.create(elves)).isEqualTo(GuildOutcome.SUCCESS);
        servers.settle();
        assertThat(arena.guildOf(MEMBER)).map(Guild::name).contains("Elves");

        UUID newcomer = Fixtures.player("newcomer");
        assertThat(arena.update(elves.id(), g -> g.withMember(Member.joining(newcomer, Rank.RECRUIT, 5)).withMotd("hi")))
                .isEqualTo(GuildOutcome.SUCCESS);
        servers.settle();
        assertThat(smp.guildOf(newcomer)).map(Guild::id).contains(elves.id());
        assertThat(smp.guild(elves.id()).orElseThrow().motd()).isEqualTo("hi");

        assertThat(arena.update(elves.id(), g -> g.withoutMember(newcomer))).isEqualTo(GuildOutcome.SUCCESS);
        servers.settle();
        assertThat(smp.guildOf(newcomer)).isEmpty();
    }

    @Test
    @DisplayName("two servers founding the same name at once: one guild, the other is told it is taken")
    void sameNameAtOnce() throws Exception {
        for (int round = 0; round < 6; round++) {
            String name = "Elves" + round;
            List<GuildOutcome> made = together(
                    () -> smp.create(Fixtures.solo(name, Fixtures.player("a" + name))),
                    () -> arena.create(Fixtures.solo(name.toUpperCase(), Fixtures.player("b" + name))));
            assertThat(made).containsExactlyInAnyOrder(GuildOutcome.SUCCESS, GuildOutcome.NAME_TAKEN);
        }
        servers.settle();
        assertThat(servers.restarted().count()).isEqualTo(6);
    }

    @Test
    @DisplayName("a name on one server and the same word as a tag on the other, at once: one of them gets it")
    void nameAgainstTagAtOnce() throws Exception {
        List<GuildOutcome> made = together(
                () -> smp.create(Fixtures.solo("Wolves", Fixtures.player("a")).withTag("PACK")),
                () -> arena.create(Fixtures.solo("Pack", Fixtures.player("b")).withTag("PK")));
        assertThat(made).filteredOn(GuildOutcome::isSuccess).hasSize(1);
    }

    @Test
    @DisplayName("a player founding two guilds at once on two servers is in one of them")
    void twoGuildsOnePlayer() throws Exception {
        UUID greedy = Fixtures.player("greedy");
        List<GuildOutcome> made = together(
                () -> smp.create(Fixtures.solo("Alpha", greedy)),
                () -> arena.create(Fixtures.solo("Beta", greedy)));
        assertThat(made).containsExactlyInAnyOrder(GuildOutcome.SUCCESS, GuildOutcome.ALREADY_IN_GUILD);
        assertThat(servers.restarted().all().stream().filter(guild -> guild.isMember(greedy))).hasSize(1);
    }

    @Test
    @DisplayName("a player joining two guilds at once on two servers joins one")
    void joiningTwo() throws Exception {
        Guild alpha = Fixtures.solo("Alpha", Fixtures.player("la"));
        Guild beta = Fixtures.solo("Beta", Fixtures.player("lb"));
        smp.create(alpha);
        smp.create(beta);
        servers.settle();
        UUID wanted = Fixtures.player("wanted");
        List<GuildOutcome> joined = together(
                () -> smp.update(alpha.id(), g -> g.withMember(Member.joining(wanted, Rank.RECRUIT, 1))),
                () -> arena.update(beta.id(), g -> g.withMember(Member.joining(wanted, Rank.RECRUIT, 1))));
        assertThat(joined).containsExactlyInAnyOrder(GuildOutcome.SUCCESS, GuildOutcome.ALREADY_IN_GUILD);
        assertThat(servers.restarted().all().stream().filter(guild -> guild.isMember(wanted))).hasSize(1);
    }

    @Test
    @DisplayName("deposits made on both servers at once are both in the bank: it is added to, never rewritten")
    void bankAddsUp() throws Exception {
        Guild elves = Fixtures.guild("Elves").withBank(100);
        smp.create(elves);
        servers.settle();
        together(
                () -> smp.update(elves.id(), g -> g.withBank(g.bank() + 40)
                        .withMember(g.member(LEADER).orElseThrow().depositing(40))),
                () -> arena.update(elves.id(), g -> g.withBank(g.bank() + 60)
                        .withMember(g.member(LEADER).orElseThrow().depositing(60))));
        servers.settle();
        for (GuildBook each : List.of(smp, arena, servers.restarted())) {
            Guild now = each.guild(elves.id()).orElseThrow();
            assertThat(now.bank()).isEqualTo(200);
            assertThat(now.member(LEADER).orElseThrow().deposited()).isEqualTo(100);
        }
    }

    @Test
    @DisplayName("two servers taking out more than the bank holds at once: the bank never goes below nothing")
    void bankNeverOverdrawn() throws Exception {
        Guild elves = Fixtures.guild("Elves").withBank(100);
        smp.create(elves);
        servers.settle();
        together(
                () -> smp.update(elves.id(), g -> g.bank() >= 80 ? g.withBank(g.bank() - 80) : g),
                () -> arena.update(elves.id(), g -> g.bank() >= 80 ? g.withBank(g.bank() - 80) : g));
        assertThat(servers.restarted().guild(elves.id()).orElseThrow().bank()).isEqualTo(20);
    }

    @Test
    @DisplayName("experience counted on both servers is added up, and each stretch of it is reported once from the database's count")
    void experienceAddsUp() {
        Guild elves = Fixtures.guild("Elves");
        smp.create(elves);
        servers.settle();
        List<long[]> reported = Collections.synchronizedList(new ArrayList<>());
        smp.onExperience((guild, before, after) -> reported.add(new long[]{before, after}));
        arena.onExperience((guild, before, after) -> reported.add(new long[]{before, after}));

        smp.addXp(elves.id(), LEADER, 60);
        arena.addXp(elves.id(), Fixtures.OFFICER, 50);
        assertThat(smp.guild(elves.id()).orElseThrow().xp()).as("each counts its own at once").isEqualTo(60);
        assertThat(smp.flush()).isTrue();
        assertThat(arena.flush()).isTrue();
        servers.settle();

        assertThat(smp.guild(elves.id()).orElseThrow().xp()).isEqualTo(110);
        assertThat(arena.guild(elves.id()).orElseThrow().xp()).isEqualTo(110);
        assertThat(servers.restarted().guild(elves.id()).orElseThrow().member(LEADER).orElseThrow().xp()).isEqualTo(60);
        assertThat(reported).extracting(range -> range[1] - range[0]).containsExactlyInAnyOrder(60L, 50L);
        assertThat(reported).extracting(range -> range[0]).containsExactlyInAnyOrder(0L, 60L);
    }

    @Test
    @DisplayName("a question answered on two servers at once is answered by one")
    void answeredOnce() throws Exception {
        Invitation invite = new Invitation(Invitation.Kind.INVITE, "elves", MEMBER, LEADER, "", Long.MAX_VALUE);
        smp.ask(invite, 0);
        servers.settle();
        assertThat(arena.questions(Invitation.Kind.INVITE, 0)).containsExactly(invite);
        List<Boolean> answered = together(
                () -> smp.answer(Invitation.Kind.INVITE, "elves", MEMBER, "", 1).isPresent(),
                () -> arena.answer(Invitation.Kind.INVITE, "elves", MEMBER, "", 1).isPresent());
        assertThat(answered).containsExactlyInAnyOrder(true, false);
        servers.settle();
        assertThat(smp.questions(Invitation.Kind.INVITE, 0)).isEmpty();
        assertThat(arena.questions(Invitation.Kind.INVITE, 0)).isEmpty();
    }

    @Test
    @DisplayName("a guild disbanded on one server is gone from the other, which lets go of what it kept about it")
    void disbandedElsewhere() {
        Guild elves = Fixtures.guild("Elves");
        smp.create(elves);
        servers.settle();
        List<String> gone = Collections.synchronizedList(new ArrayList<>());
        arena.onDisband(gone::add);
        assertThat(smp.disband(elves.id())).isPresent();
        servers.settle();
        assertThat(arena.guild(elves.id())).isEmpty();
        assertThat(arena.guildOf(LEADER)).isEmpty();
        assertThat(gone).containsExactly("elves");
    }

    @Test
    @DisplayName("a database that cannot be read keeps the guilds a server knows")
    void failedReadKeepsMemory() {
        Database mine = servers.separate();
        GuildBook blinking = new GuildBook(mine, servers.problems::add);
        blinking.load();
        blinking.connect(servers.hub.bus("third"));
        smp.create(Fixtures.guild("Elves"));
        servers.settle();
        assertThat(blinking.count()).isEqualTo(1);

        mine.close();
        smp.create(Fixtures.solo("Dwarves", Fixtures.player("thorin")));
        smp.disband(smp.guildOf(LEADER).orElseThrow().id());
        servers.settle();
        assertThat(blinking.count()).as("what it knew, not nothing").isEqualTo(1);
        assertThat(blinking.guildOf(LEADER)).isPresent();
        blinking.refreshAll();
        assertThat(blinking.guildOf(LEADER)).isPresent();
    }

    @Test
    @DisplayName("when somebody was last on, on any server, is kept for a leader's absence")
    void seenAnywhere() {
        Guild elves = Fixtures.guild("Elves");
        smp.create(elves);
        servers.settle();
        arena.seen(LEADER, 5_000);
        assertThat(arena.flush()).isTrue();
        assertThat(servers.restarted().guild(elves.id()).orElseThrow().member(LEADER).orElseThrow().seen())
                .isEqualTo(5_000);
        arena.seen(LEADER, 100);
        arena.flush();
        assertThat(servers.restarted().guild(elves.id()).orElseThrow().member(LEADER).orElseThrow().seen())
                .as("never moves back").isEqualTo(5_000);
    }
}
