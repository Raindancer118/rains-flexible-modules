package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.guilds.model.Fixtures;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildEvent;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.HomeSpot;
import de.raindancer.modules.guilds.model.Invitation;
import de.raindancer.modules.guilds.model.Perk;
import de.raindancer.modules.guilds.model.XpSource;
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

import static de.raindancer.modules.guilds.model.Fixtures.LEADER;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class GuildMigrationTest {

    @TempDir
    Path folder;

    private Database own;
    private Database shared;
    private final List<String> problems = new ArrayList<>();

    @BeforeEach
    void start() {
        own = TestDatabases.open(folder.resolve("guilds_smp.db"), GuildsDatabase.SCHEMA);
        shared = TestDatabases.open(folder.resolve("guilds.db"), GuildsDatabase.SCHEMA);
    }

    @AfterEach
    void stop() {
        own.close();
        shared.close();
    }

    private Guild founded(Database database, Guild guild) {
        GuildBook book = new GuildBook(database, problems::add);
        book.load();
        assertThat(book.create(guild)).isEqualTo(GuildOutcome.SUCCESS);
        book.log(GuildEvent.of(guild.id().value(), 5, GuildEvent.Kind.FOUNDED, LEADER, null, 0, guild.name()));
        book.ask(new Invitation(Invitation.Kind.INVITE, guild.id().value(), Fixtures.STRANGER, LEADER, "", Long.MAX_VALUE), 0);
        book.flush();
        ProgressBook progress = new ProgressBook(database);
        progress.load();
        progress.add(guild.id().value(), XpSource.MINE, 77, 2_900);
        progress.standing(guild.id().value(), () -> 4, standing -> standing.withPerk(Perk.HASTE, 1));
        progress.flush();
        return guild;
    }

    @Test
    @DisplayName("a server's own guilds come into the network's database whole, with a home that named no server now naming this one")
    void brought() {
        Guild elves = founded(own, Fixtures.guild("Elves").withBank(900).withXp(5)
                .withHome(Optional.of(new HomeSpot("world", 1, 2, 3, 0, 0))));
        GuildMigration.Report report = GuildMigration.run(own, shared, "smp", problems::add, () -> 1_000);
        assertThat(report.failed()).isFalse();
        assertThat(report.brought()).isEqualTo(1);

        GuildBook book = new GuildBook(shared, problems::add);
        book.load();
        Guild read = book.guild(elves.id()).orElseThrow();
        assertThat(read.bank()).isEqualTo(900);
        assertThat(read.xp()).isEqualTo(5);
        assertThat(read.members()).hasSize(4);
        assertThat(read.home()).map(HomeSpot::server).contains("smp");
        assertThat(book.events(elves.id(), Set.of(), 5, 0)).extracting(GuildEvent::kind).containsExactly(GuildEvent.Kind.FOUNDED);
        assertThat(book.questions(Invitation.Kind.INVITE, 0)).hasSize(1);
        ProgressBook progress = new ProgressBook(shared);
        progress.load();
        assertThat(progress.totals(elves.id().value())).containsEntry(XpSource.MINE, 77L);
        assertThat(progress.standing(elves.id().value(), () -> 0).rank(Perk.HASTE)).isEqualTo(1);
        assertThat(progress.weekXp(2_900)).containsEntry(elves.id().value(), 77L);
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("it happens once: what was brought is not brought again, and what happened since is left alone")
    void once() {
        founded(own, Fixtures.guild("Elves"));
        GuildMigration.run(own, shared, "smp", problems::add, () -> 1_000);
        GuildBook book = new GuildBook(shared, problems::add);
        book.load();
        book.update(book.find("Elves").orElseThrow().id(), guild -> guild.withMotd("changed since"));
        assertThat(GuildMigration.run(own, shared, "smp", problems::add, () -> 2_000)).isEqualTo(new GuildMigration.Report(0, 0, false));
        GuildBook again = new GuildBook(shared, problems::add);
        again.load();
        assertThat(again.find("Elves").orElseThrow().motd()).isEqualTo("changed since");
    }

    @Test
    @DisplayName("a guild whose name another server's guild holds, or whose members are in another guild, stays where it was")
    void conflicts() {
        founded(shared, Fixtures.solo("Elves", Fixtures.player("other")));
        founded(own, Fixtures.guild("Elves"));
        founded(own, Fixtures.solo("Dwarves", Fixtures.player("thorin")));
        GuildMigration.Report report = GuildMigration.run(own, shared, "smp", problems::add, () -> 1_000);
        assertThat(report.brought()).isEqualTo(1);
        assertThat(report.left()).isEqualTo(1);
        assertThat(problems).anyMatch(problem -> problem.contains("Elves") && problem.contains("NAME_TAKEN"));
        GuildBook book = new GuildBook(shared, problems::add);
        book.load();
        assertThat(book.find("Dwarves")).isPresent();
        assertThat(book.guildOf(LEADER)).isEmpty();
        assertThat(book.find("Elves").orElseThrow().size()).as("the other server's, untouched").isEqualTo(1);
    }

    @Test
    @DisplayName("a database that cannot be read brings nothing and is tried again at the next start")
    void unreadable() throws Exception {
        Database broken = TestDatabases.unusable(folder.resolve("broken.db"), GuildsDatabase.SCHEMA);
        assertThat(GuildMigration.run(broken, shared, "smp", problems::add, () -> 1).failed()).isTrue();
        broken.close();
    }
}
