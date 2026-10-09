package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.ui.choose.Category;
import de.raindancer.core.ui.choose.ItemSelection;
import de.raindancer.modules.jobs.model.Goal;
import de.raindancer.modules.jobs.model.GoalKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GoalBookTest {

    @Test
    @DisplayName("goals, who gave what, what was learned and the next number survive a restart")
    void persists(@TempDir Path folder) {
        UUID ana = UUID.randomUUID();
        Path file = folder.resolve("goals.yml");
        GoalBook book = new GoalBook(new YamlStore(file));
        book.load();
        assertThat(book.nextNumber()).isEqualTo(1);
        Goal cod = new Goal(book.nextNumber(), "cod", "Cod for the harbour", "COD", GoalKind.DELIVER,
                new ItemSelection(List.of(Category.FOOD), List.of("cod"), List.of("cooked_cod")), 200, 10, 99,
                Map.of(ana, 40));
        assertThat(book.start(cod)).isTrue();
        assertThat(book.learn("cod", 300)).isTrue();

        GoalBook again = new GoalBook(new YamlStore(file));
        again.load();
        assertThat(again.active()).hasSize(1);
        Goal read = again.active().getFirst();
        assertThat(read.title()).isEqualTo("Cod for the harbour");
        assertThat(read.items().covers("COD")).isTrue();
        assertThat(read.items().covers("COOKED_COD")).isFalse();
        assertThat(read.items().covers("BREAD")).as("the food drawer came back").isTrue();
        assertThat(read.givenBy(ana)).isEqualTo(40);
        assertThat(read.endsAt()).isEqualTo(99);
        assertThat(again.learned("cod")).contains(300);
        assertThat(again.nextNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("a contribution is added to the goal and saved; a finished goal leaves the board")
    void contributing(@TempDir Path folder) {
        UUID ana = UUID.randomUUID();
        GoalBook book = new GoalBook(new YamlStore(folder.resolve("goals.yml")));
        book.load();
        Goal goal = new Goal(book.nextNumber(), "fish", "Fishing season", "FISHING_ROD", GoalKind.FISH,
                new ItemSelection(List.of(), List.of("cod"), List.of()), 10, 0, 99, Map.of());
        book.start(goal);
        assertThat(book.add(goal.number(), ana, 4)).map(Goal::progress).contains(4);
        assertThat(book.add(goal.number(), ana, 50)).map(Goal::progress).as("never past the goal").contains(10);
        assertThat(book.add(999, ana, 1)).isEmpty();
        assertThat(book.end(goal.number())).isPresent();
        assertThat(book.active()).isEmpty();
        assertThat(book.end(goal.number())).isEmpty();
    }

    @Test
    @DisplayName("an unreadable goals.yml is not overwritten, and nothing can be changed until it is fixed")
    void unreadable(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("goals.yml");
        Files.writeString(file, "goals: [ : : broken");
        GoalBook book = new GoalBook(new YamlStore(file));
        book.load();
        assertThat(book.readable()).isFalse();
        assertThat(book.learn("cod", 5)).isFalse();
        assertThat(Files.readString(file)).contains("broken");
    }
}
