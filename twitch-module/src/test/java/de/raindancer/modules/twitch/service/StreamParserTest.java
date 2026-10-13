package de.raindancer.modules.twitch.service;

import de.raindancer.modules.twitch.model.Observation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StreamParserTest {

    @Test
    @DisplayName("a stream in the answer is read, whatever else Twitch adds")
    void live() {
        String body = "{\"data\":[{\"id\":\"4242\",\"user_login\":\"lillyyxoxo_\",\"user_name\":\"Lillyyxoxo_\","
                + "\"game_name\":\"Minecraft\",\"type\":\"live\",\"title\":\"Building the castle\",\"viewer_count\":37,"
                + "\"started_at\":\"2026-10-13T18:00:00Z\",\"tags\":[\"English\"]}],\"pagination\":{}}";

        Observation seen = StreamParser.read(body, "lillyyxoxo_");

        assertThat(seen).isInstanceOf(Observation.Live.class);
        var stream = ((Observation.Live) seen).stream();
        assertThat(stream.id()).isEqualTo("4242");
        assertThat(stream.title()).isEqualTo("Building the castle");
        assertThat(stream.game()).isEqualTo("Minecraft");
        assertThat(stream.viewers()).isEqualTo(37);
        assertThat(stream.startedAt()).isEqualTo(Instant.parse("2026-10-13T18:00:00Z"));
        assertThat(stream.channel()).isEqualTo("Lillyyxoxo_");
    }

    @Test
    @DisplayName("an empty list is offline; something that is not Twitch's answer is unknown")
    void offlineAndGarbage() {
        assertThat(StreamParser.read("{\"data\":[],\"pagination\":{}}", "x")).isEqualTo(Observation.OFFLINE);
        assertThat(StreamParser.read("not json", "x")).isEqualTo(Observation.UNKNOWN);
        assertThat(StreamParser.read("{\"error\":\"Unauthorized\",\"status\":401}", "x")).isEqualTo(Observation.UNKNOWN);
        assertThat(StreamParser.read("{\"data\":[{\"id\":\"1\",\"type\":\"live\"}]}", "x")).isEqualTo(Observation.UNKNOWN);
        assertThat(StreamParser.read(null, "x")).isEqualTo(Observation.UNKNOWN);
    }

    @Test
    @DisplayName("how long a stream lasted is said in hours and minutes")
    void duration() {
        assertThat(StreamParser.spoken(java.time.Duration.ofMinutes(125))).isEqualTo("2 h 5 min");
        assertThat(StreamParser.spoken(java.time.Duration.ofMinutes(45))).isEqualTo("45 min");
        assertThat(StreamParser.spoken(java.time.Duration.ofSeconds(20))).isEqualTo("under a minute");
        assertThat(StreamParser.spoken(java.time.Duration.ofHours(3))).isEqualTo("3 h");
    }
}
