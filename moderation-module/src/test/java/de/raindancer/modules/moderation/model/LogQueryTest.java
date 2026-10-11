package de.raindancer.modules.moderation.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LogQueryTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = ZonedDateTime.of(2026, 10, 11, 14, 20, 30, 0, BERLIN).toInstant();

    private static LogQuery parse(String typed) {
        return LogQuery.parse(typed.isEmpty() ? new String[0] : typed.split(" "), BERLIN, NOW);
    }

    private static Instant berlin(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, BERLIN).toInstant();
    }

    @Test
    @DisplayName("players, a Berlin time range and words combine; a date and a time may be two words")
    void everythingAtOnce() {
        LogQuery query = parse("player:MushyTheFish from:2026-10-10 17:30 to:2026-10-10 18:00 rules spawn");
        assertThat(query.problems()).isEmpty();
        assertThat(query.players()).containsExactly("MushyTheFish");
        assertThat(query.from()).isEqualTo(berlin(10, 17, 30));
        assertThat(query.to()).isEqualTo(berlin(10, 18, 1));
        assertThat(query.words()).containsExactly("rules", "spawn");
        assertThat(query.page()).isEqualTo(1);
    }

    @Test
    @DisplayName("a phrase in quotes is one term as it stands; a quoted value may hold spaces")
    void quotes() {
        LogQuery query = parse("\"near my base\" p:Bo from:\"2026-10-10 17:30\" hello");
        assertThat(query.phrases()).containsExactly("near my base");
        assertThat(query.words()).containsExactly("hello");
        assertThat(query.terms()).containsExactly("hello", "near my base");
        assertThat(query.players()).containsExactly("Bo");
        assertThat(query.from()).isEqualTo(berlin(10, 17, 30));
    }

    @Test
    @DisplayName("day:, last: and several players; a date alone is the whole Berlin day")
    void conveniences() {
        LogQuery yesterday = parse("day:yesterday player:Bo player:Cy");
        assertThat(yesterday.from()).isEqualTo(berlin(10, 0, 0));
        assertThat(yesterday.to()).isEqualTo(berlin(11, 0, 0));
        assertThat(yesterday.players()).containsExactly("Bo", "Cy");

        LogQuery lastTwo = parse("last:2h");
        assertThat(lastTwo.from()).isEqualTo(NOW.minus(Duration.ofHours(2)));
        assertThat(lastTwo.to()).isNull();

        LogQuery date = parse("from:2026-10-10 to:2026-10-10");
        assertThat(date.from()).isEqualTo(berlin(10, 0, 0));
        assertThat(date.to()).isEqualTo(berlin(11, 0, 0));
        assertThat(parse("day:today").from()).isEqualTo(berlin(11, 0, 0));
    }

    @Test
    @DisplayName("pages and kinds are read; words that only look like keys stay words")
    void pagesKindsAndColons() {
        LogQuery query = parse("page:3 type:join type:chat 17:30 http://example.com");
        assertThat(query.page()).isEqualTo(3);
        assertThat(query.types()).containsExactly("join", "chat");
        assertThat(query.words()).containsExactly("17:30", "http://example.com");
    }

    @Test
    @DisplayName("what cannot be read is named, never guessed at")
    void problems() {
        assertThat(parse("from:soon").problems()).containsExactly("from:soon");
        assertThat(parse("day:17:30").problems()).containsExactly("day:17:30");
        assertThat(parse("last:never").problems()).containsExactly("last:never");
        assertThat(parse("page:zero").problems()).containsExactly("page:zero");
        assertThat(parse("player:").problems()).containsExactly("player:");
        assertThat(parse("from:2026-10-11 to:2026-10-10").problems()).containsExactly("to:2026-10-10");
    }

    @Test
    @DisplayName("written back as a command, it reads as the same search — a moving 'last 2h' is pinned down")
    void roundTrip() {
        LogQuery query = parse("player:Bo last:2h \"near spawn\" rules type:chat page:2");
        String written = query.arguments(BERLIN);
        LogQuery again = LogQuery.parse(written.split(" "), BERLIN, NOW.plus(Duration.ofHours(5)));
        assertThat(again.problems()).isEmpty();
        assertThat(again.players()).isEqualTo(query.players());
        assertThat(again.from()).isEqualTo(query.from());
        assertThat(again.to()).isEqualTo(query.to());
        assertThat(again.terms()).isEqualTo(query.terms());
        assertThat(again.types()).isEqualTo(query.types());
        assertThat(again.page()).isEqualTo(2);

        LogQuery ranged = parse("from:2026-10-10 17:30 to:2026-10-10 18:00");
        LogQuery reread = LogQuery.parse(ranged.arguments(BERLIN).split(" "), BERLIN, NOW);
        assertThat(reread.from()).isEqualTo(ranged.from());
        assertThat(reread.to()).isEqualTo(ranged.to());
    }

    @Test
    @DisplayName("narrowing to one player keeps the rest of the search and starts at page one")
    void narrowing() {
        LogQuery query = parse("player:Bo player:Cy rules page:4").onlyPlayer("Cy").atPage(1);
        assertThat(query.players()).containsExactly("Cy");
        assertThat(query.words()).containsExactly("rules");
        assertThat(query.page()).isEqualTo(1);
        assertThat(parse("").isEverything()).isTrue();
        assertThat(parse("page:2").isEverything()).isTrue();
        assertThat(parse("rules").isEverything()).isFalse();
    }

    @Test
    @DisplayName("described for a header in Berlin time")
    void described() {
        assertThat(parse("player:Bo from:2026-10-10 17:30 to:2026-10-10 18:00 \"near spawn\" rules").describe(BERLIN))
                .isEqualTo("player Bo · 2026-10-10 17:30 – 2026-10-10 18:00 · \"rules\" \"near spawn\"");
        assertThat(parse("").describe(BERLIN)).isEqualTo("everything");
    }
}
