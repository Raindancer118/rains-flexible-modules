package de.raindancer.modules.guilds.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.guilds.model.Fixtures;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.Rank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class LimitMigrationTest {

    private static final LimitMigration.Limits LIMITS = new LimitMigration.Limits(1000, 10_000, 100, 1000);

    @TempDir
    Path folder;
    private Database database;

    @BeforeEach
    void start() {
        database = TestDatabases.open(folder.resolve("guilds.db"), GuildsDatabase.SCHEMA);
    }

    @AfterEach
    void stop() {
        database.close();
    }

    private GuildBook book() {
        GuildBook book = new GuildBook(database, problem -> { });
        book.load();
        return book;
    }

    private Guild guild(long officer, long member) {
        Guild guild = Fixtures.guild("Elves");
        guild = guild.withRank(guild.rank(Rank.OFFICER).orElseThrow().withDailyLimit(officer))
                .withRank(guild.rank(Rank.MEMBER).orElseThrow().withDailyLimit(member));
        return guild;
    }

    @Test
    @DisplayName("ranks still on the old default limits are raised to the new ones, once; limits a guild chose stay")
    void raisesOnlyOldDefaults() {
        GuildBook first = book();
        Guild guild = guild(1000, 250);
        assertThat(first.create(guild)).isEqualTo(GuildOutcome.SUCCESS);
        first.flush();

        assertThat(LimitMigration.run(database, LIMITS, () -> 7)).isEqualTo(1);
        Guild after = book().guild(guild.id().value()).orElseThrow();
        assertThat(after.rank(Rank.OFFICER).orElseThrow().dailyLimit()).isEqualTo(10_000);
        assertThat(after.rank(Rank.MEMBER).orElseThrow().dailyLimit()).as("chosen by the guild").isEqualTo(250);

        GuildBook again = book();
        again.update(guild.id(), now -> now.withRank(now.rank(Rank.OFFICER).orElseThrow().withDailyLimit(1000)));
        again.flush();
        assertThat(LimitMigration.run(database, LIMITS, () -> 8)).as("only ever once").isZero();
        assertThat(book().guild(guild.id().value()).orElseThrow().rank(Rank.OFFICER).orElseThrow().dailyLimit())
                .isEqualTo(1000);
    }

    @Test
    @DisplayName("never lowers: a server whose new limits are not higher leaves every rank alone")
    void neverLowers() {
        GuildBook first = book();
        Guild guild = guild(1000, 100);
        first.create(guild);
        first.flush();
        assertThat(LimitMigration.run(database, new LimitMigration.Limits(1000, 500, 100, 100), () -> 7)).isZero();
        Guild after = book().guild(guild.id().value()).orElseThrow();
        assertThat(after.rank(Rank.OFFICER).orElseThrow().dailyLimit()).isEqualTo(1000);
        assertThat(after.rank(Rank.MEMBER).orElseThrow().dailyLimit()).isEqualTo(100);
    }
}
