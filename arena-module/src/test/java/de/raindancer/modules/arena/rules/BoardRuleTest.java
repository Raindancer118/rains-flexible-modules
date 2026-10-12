package de.raindancer.modules.arena.rules;

import de.raindancer.modules.arena.model.Stats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BoardRuleTest {

    private static Stats s(String name, int wins, int wave) {
        return new Stats(UUID.randomUUID(), name, wins, 0, 0, 0, 0, wave);
    }

    @Test
    @DisplayName("the board: champions by wins and the furthest in waves, numbered, medals for the first three")
    void text() {
        String board = new BoardRule().text(List.of(s("Ada", 12, 0), s("Bo", 7, 0), s("Cy", 3, 0), s("Di", 1, 0)),
                List.of(s("Bo", 0, 18)));
        assertThat(board).contains("Ada").contains("12").contains("Bo").contains("18");
        assertThat(board.indexOf("Ada")).isLessThan(board.indexOf("Cy"));
        assertThat(board).contains("4.").doesNotContain("<Ada>");
    }

    @Test
    @DisplayName("an empty board says so instead of showing nothing")
    void empty() {
        assertThat(new BoardRule().text(List.of(), List.of())).contains("No champion yet");
    }

    @Test
    @DisplayName("a name is shown as written, never as markup")
    void namesAreText() {
        assertThat(new BoardRule().text(List.of(s("<red>x", 1, 0)), List.of())).contains("\\<red>x");
    }
}
