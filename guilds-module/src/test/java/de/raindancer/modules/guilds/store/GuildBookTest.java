package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamEmblem;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.guilds.model.Fixtures;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildEvent;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.HomeSpot;
import de.raindancer.modules.guilds.model.Invitation;
import de.raindancer.modules.guilds.model.JoinMode;
import de.raindancer.modules.guilds.model.Member;
import de.raindancer.modules.guilds.model.Rank;
import de.raindancer.modules.guilds.model.Relation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static de.raindancer.modules.guilds.model.Fixtures.LEADER;
import static de.raindancer.modules.guilds.model.Fixtures.MEMBER;
import static de.raindancer.modules.guilds.model.Fixtures.OFFICER;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class GuildBookTest {

    @TempDir
    Path folder;
    private TwoServers servers;
    private GuildBook book;

    @BeforeEach
    void open() {
        servers = new TwoServers(folder);
        book = servers.book("smp");
    }

    @AfterEach
    void close() {
        servers.close();
    }

    @Test
    @DisplayName("everything about a guild survives a restart")
    void roundTrip() {
        Guild elves = Fixtures.guild("Elves")
                .withLook(TeamColour.values()[3], TeamEmblem.visible().getFirst())
                .withMotd("<green>Hello")
                .withJoinMode(JoinMode.APPLY)
                .withXp(1234)
                .withBank(5678)
                .withHome(Optional.of(new HomeSpot("world", 1.5, 64, -3.25, 90f, -10f, "smp")))
                .withFriendlyFire(true, false)
                .withMember(new Member(MEMBER, Rank.MEMBER, 3, 100, 40, 77, 40, 20000))
                .withRank(new Rank("archer", "Archer", 150, Set.of(GuildPermission.USE_HOME, GuildPermission.KICK), 250));
        Guild dwarves = Fixtures.solo("Dwarves", Fixtures.player("thorin"));
        assertThat(book.create(elves)).isEqualTo(GuildOutcome.SUCCESS);
        assertThat(book.create(dwarves)).isEqualTo(GuildOutcome.SUCCESS);
        assertThat(book.updateBoth(elves.id(), g -> g.withRelation(dwarves.id(), Relation.ALLY),
                dwarves.id(), g -> g.withRelation(elves.id(), Relation.ALLY))).isEqualTo(GuildOutcome.SUCCESS);
        book.log(GuildEvent.of(elves.id().value(), 10, GuildEvent.Kind.FOUNDED, LEADER, null, 0, "Elves"));
        assertThat(book.flush()).isTrue();

        GuildBook again = servers.restarted();
        Guild read = again.guild(elves.id()).orElseThrow();
        Guild expected = book.guild(elves.id()).orElseThrow();
        assertThat(read).usingRecursiveComparison().ignoringFields("members").isEqualTo(expected);
        assertThat(read.members().get(MEMBER)).isEqualTo(elves.members().get(MEMBER).withSeen(read.members().get(MEMBER).seen()));
        assertThat(read.home()).contains(new HomeSpot("world", 1.5, 64, -3.25, 90f, -10f, "smp"));
        assertThat(read.bank()).isEqualTo(5678);
        assertThat(again.guildOf(MEMBER)).map(Guild::id).contains(elves.id());
        assertThat(again.events(elves.id(), Set.of(), 10, 0)).extracting(GuildEvent::kind).containsExactly(GuildEvent.Kind.FOUNDED);
        assertThat(servers.problems).isEmpty();
    }

    @Test
    @DisplayName("names and tags are unique ignoring case, and nobody is in two guilds")
    void uniqueness() {
        assertThat(book.create(Fixtures.solo("Elves", Fixtures.player("a")))).isEqualTo(GuildOutcome.SUCCESS);
        assertThat(book.create(Fixtures.solo("ELVES", Fixtures.player("b")))).isEqualTo(GuildOutcome.NAME_TAKEN);
        Guild sameTag = Fixtures.solo("Woods", Fixtures.player("c")).withTag(book.find("elves").orElseThrow().tag().toLowerCase());
        assertThat(book.create(sameTag)).isEqualTo(GuildOutcome.TAG_TAKEN);
        assertThat(book.create(Fixtures.solo("Dwarves", Fixtures.player("a")))).isEqualTo(GuildOutcome.ALREADY_IN_GUILD);
        assertThat(book.count()).isEqualTo(1);

        Guild dwarves = Fixtures.solo("Dwarves", Fixtures.player("d"));
        book.create(dwarves);
        assertThat(book.update(dwarves.id(), g -> g.withMember(Member.joining(Fixtures.player("a"), Rank.RECRUIT, 0))))
                .isEqualTo(GuildOutcome.ALREADY_IN_GUILD);
        assertThat(book.update(dwarves.id(), g -> g.withName("elves"))).isEqualTo(GuildOutcome.NAME_TAKEN);
        assertThat(book.guild(dwarves.id()).orElseThrow().name()).isEqualTo("Dwarves");
    }

    @Test
    @DisplayName("names, tags and ids are one namespace, so a typed word never means two guilds")
    void oneNamespace() {
        Guild orcs = Fixtures.solo("Orcs", Fixtures.player("a")).withTag("GRR");
        assertThat(book.create(orcs)).isEqualTo(GuildOutcome.SUCCESS);
        assertThat(book.create(Fixtures.solo("Wolves", Fixtures.player("b")).withTag("ORCS")))
                .as("a tag that is another guild's name").isEqualTo(GuildOutcome.TAG_TAKEN);
        assertThat(book.create(Fixtures.solo("grr", Fixtures.player("c")).withTag("XX")))
                .as("a name that is another guild's tag").isEqualTo(GuildOutcome.NAME_TAKEN);
        Guild wolves = Fixtures.solo("Wolves", Fixtures.player("d")).withTag("WLF");
        book.create(wolves);
        book.update(orcs.id(), g -> g.withName("Uruks"));
        assertThat(book.update(wolves.id(), g -> g.withTag("ORCS"))).as("the id the orcs were founded with").isEqualTo(GuildOutcome.TAG_TAKEN);
        assertThat(book.update(wolves.id(), g -> g.withName("Uruks"))).isEqualTo(GuildOutcome.NAME_TAKEN);
        assertThat(book.find("uruks")).map(Guild::id).contains(orcs.id());
        assertThat(book.find("wlf")).map(Guild::id).contains(wolves.id());
    }

    @Test
    @DisplayName("guilds from before names were a table claim their words when read, so a second one cannot take them")
    void oldGuildsClaimTheirWords() {
        book.create(Fixtures.guild("Elves"));
        assertThat(servers.database().write(connection -> connection.createStatement().executeUpdate("DELETE FROM guild_word")))
                .isTrue();
        GuildBook again = servers.restarted();
        assertThat(again.create(Fixtures.solo("ELVES", Fixtures.player("late")))).isEqualTo(GuildOutcome.NAME_TAKEN);
        assertThat(again.create(Fixtures.solo("Woods", Fixtures.player("late")).withTag("ELVE"))).isEqualTo(GuildOutcome.TAG_TAKEN);
    }

    @Test
    @DisplayName("a rename keeps the id and every member; find works by name, tag and id")
    void rename() {
        Guild elves = Fixtures.guild("Elves");
        book.create(elves);
        assertThat(book.update(elves.id(), g -> g.withName("Wood Elves"))).isEqualTo(GuildOutcome.SUCCESS);
        assertThat(book.find("wood elves")).map(Guild::id).contains(elves.id());
        assertThat(book.find(elves.tag())).isPresent();
        assertThat(book.find("elves")).as("by the id it was founded with").isPresent();
        assertThat(book.guildOf(OFFICER)).map(Guild::name).contains("Wood Elves");
        assertThat(servers.restarted().find("wood elves")).isPresent();
    }

    @Test
    @DisplayName("leaving and joining move people between guilds, and a change that does nothing is a success")
    void membership() {
        Guild elves = Fixtures.guild("Elves");
        book.create(elves);
        book.update(elves.id(), g -> g.withoutMember(MEMBER));
        assertThat(book.guildOf(MEMBER)).isEmpty();
        UUID newcomer = Fixtures.player("newcomer");
        book.update(elves.id(), g -> g.withMember(Member.joining(newcomer, Rank.RECRUIT, 5)));
        assertThat(book.guildOf(newcomer)).map(Guild::id).contains(elves.id());
        assertThat(book.update(elves.id(), g -> g)).isEqualTo(GuildOutcome.SUCCESS);
        book.update(elves.id(), g -> g.withLeader(OFFICER).withMember(g.member(OFFICER).orElseThrow().withRank(Rank.LEADER))
                .withMember(g.member(LEADER).orElseThrow().withRank(Rank.OFFICER)));
        Guild again = servers.restarted().guild(elves.id()).orElseThrow();
        assertThat(again.leader()).isEqualTo(OFFICER);
        assertThat(again.member(LEADER).orElseThrow().rank()).isEqualTo(Rank.OFFICER);
        assertThat(again.isMember(MEMBER)).isFalse();
        assertThat(book.update(new TeamId("nobody"), g -> g.withMotd("x"))).isEqualTo(GuildOutcome.NO_SUCH_GUILD);
    }

    @Test
    @DisplayName("disbanding takes the guild, its members' places, relations to it and questions about it")
    void disband() {
        Guild elves = Fixtures.guild("Elves");
        Guild dwarves = Fixtures.solo("Dwarves", Fixtures.player("thorin"));
        book.create(elves);
        book.create(dwarves);
        book.updateBoth(elves.id(), g -> g.withRelation(dwarves.id(), Relation.ALLY), dwarves.id(), g -> g.withRelation(elves.id(), Relation.ALLY));
        book.ask(new Invitation(Invitation.Kind.INVITE, elves.id().value(), Fixtures.STRANGER, LEADER, "", Long.MAX_VALUE), 0);

        assertThat(book.disband(elves.id())).isPresent();
        assertThat(book.guildOf(OFFICER)).isEmpty();
        assertThat(book.guild(dwarves.id()).orElseThrow().relations()).isEmpty();
        assertThat(book.questions(Invitation.Kind.INVITE, 0)).isEmpty();
        assertThat(book.create(Fixtures.solo("Elves", OFFICER))).as("the name is free again").isEqualTo(GuildOutcome.SUCCESS);

        GuildBook again = servers.restarted();
        assertThat(again.guild(dwarves.id()).orElseThrow().relations()).isEmpty();
        assertThat(again.guild(elves.id()).orElseThrow().size()).isEqualTo(1);
        assertThat(again.questions(Invitation.Kind.INVITE, 0)).isEmpty();
    }

    @Test
    @DisplayName("a guild whose bank still holds money is not disbanded: what was put in meanwhile is not lost with it")
    void disbandKeepsMoney() {
        Guild elves = Fixtures.guild("Elves").withBank(900);
        book.create(elves);
        assertThat(book.disband(elves.id())).isEmpty();
        assertThat(book.guild(elves.id())).isPresent();
        book.update(elves.id(), g -> g.withBank(0));
        assertThat(book.disband(elves.id())).isPresent();
    }

    @Test
    @DisplayName("questions: asked once, answered once, gone when they run out")
    void questions() {
        Invitation invite = new Invitation(Invitation.Kind.INVITE, "elves", MEMBER, LEADER, "", 100);
        assertThat(book.ask(invite, 0)).isTrue();
        assertThat(book.ask(invite, 50)).as("still open").isFalse();
        assertThat(book.answer(Invitation.Kind.INVITE, "elves", MEMBER, "", 50)).contains(invite);
        assertThat(book.answer(Invitation.Kind.INVITE, "elves", MEMBER, "", 50)).isEmpty();
        book.ask(invite, 0);
        assertThat(book.answer(Invitation.Kind.INVITE, "elves", MEMBER, "", 100)).as("ran out").isEmpty();
        book.ask(invite, 0);
        assertThat(book.expire(100)).containsExactly(invite);
        book.ask(invite, 0);
        assertThat(servers.restarted().questions(Invitation.Kind.INVITE, 0)).containsExactly(invite);
        book.forgetQuestionsOf(MEMBER);
        assertThat(servers.restarted().questions(Invitation.Kind.INVITE, 0)).isEmpty();
    }

    @Test
    @DisplayName("what a flush could not write stays queued: nothing counted or logged is lost to a database that blinks")
    void failedWriteRequeues() {
        Database mine = servers.separate();
        GuildBook shaky = new GuildBook(mine, problem -> { });
        shaky.load();
        Guild elves = Fixtures.guild("Elves");
        assertThat(shaky.create(elves)).isEqualTo(GuildOutcome.SUCCESS);
        shaky.addXp(elves.id(), LEADER, 40);
        shaky.log(GuildEvent.of("elves", 1, GuildEvent.Kind.FOUNDED, LEADER, null, 0, ""));
        mine.close();
        assertThat(shaky.flush()).isFalse();
        assertThat(shaky.hasUnwritten()).isTrue();
        assertThat(shaky.guild(elves.id()).orElseThrow().xp()).as("still counted here").isEqualTo(40);
    }

    @Test
    @DisplayName("an unreadable database is never written over")
    void unreadable() throws Exception {
        Database broken = TestDatabases.unusable(folder.resolve("broken.db"), GuildsDatabase.SCHEMA);
        GuildBook held = new GuildBook(broken, problem -> { });
        held.load();
        assertThat(held.readable()).isFalse();
        assertThat(held.create(Fixtures.guild("Elves"))).isEqualTo(GuildOutcome.NOT_SAVED);
        assertThat(held.update(new TeamId("elves"), g -> g.withMotd("x"))).isEqualTo(GuildOutcome.NOT_SAVED);
        broken.close();
    }

    @Test
    @DisplayName("several threads joining and leaving at once never put anybody in two guilds")
    void concurrency() throws InterruptedException {
        List<TeamId> ids = new ArrayList<>();
        for (int each = 0; each < 4; each++) {
            Guild guild = Fixtures.solo("Guild" + each, Fixtures.player("leader" + each));
            book.create(guild);
            ids.add(guild.id());
        }
        List<UUID> players = new ArrayList<>();
        for (int each = 0; each < 12; each++) {
            players.add(Fixtures.player("p" + each));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int thread = 0; thread < 4; thread++) {
            pool.submit(() -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int round = 0; round < 60; round++) {
                    UUID player = players.get(random.nextInt(players.size()));
                    TeamId id = ids.get(random.nextInt(ids.size()));
                    if (random.nextBoolean()) {
                        if (book.guildOf(player).isEmpty()) {
                            book.update(id, g -> g.isMember(player) ? g : g.withMember(Member.joining(player, Rank.RECRUIT, 0)));
                        }
                    } else {
                        book.guildOf(player).ifPresent(g -> book.update(g.id(), now -> now.withoutMember(player)));
                    }
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        GuildBook again = servers.restarted();
        for (UUID player : players) {
            long in = again.all().stream().filter(guild -> guild.isMember(player)).count();
            assertThat(in).as("guilds holding %s", player).isLessThanOrEqualTo(1);
            assertThat(again.guildOf(player).map(Guild::id)).isEqualTo(book.guildOf(player).map(Guild::id));
        }
        assertThat(servers.problems).isEmpty();
    }
}
