package de.raindancer.modules.oneblock.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.scoreboard.Sidebar;
import de.raindancer.modules.oneblock.OneBlockModule;
import de.raindancer.modules.oneblock.model.IslandView;
import de.raindancer.modules.oneblock.model.Progress;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** What the sidebar says, read as a player reads it. */
class SidebarServiceTest {

    @TempDir
    Path dir;
    private SidebarService sidebar;

    private static final Progress PLAINS = new Progress("Plains", 123, 64, "Underground", 36);

    @BeforeEach
    void setUp() {
        Messages messages = new Messages(dir.resolve("messages.yml"));
        messages.defineFrom(OneBlockModule.class.getResourceAsStream("messages.yml"));
        sidebar = new SidebarService(messages, null);
    }

    private static List<String> plain(Sidebar drawn) {
        return drawn.lines().stream().map(line -> PlainTextComponentSerializer.plainText().serialize(line).strip()).toList();
    }

    @Test
    @DisplayName("on your own island: whose it is, your rank, the phase with its bar and what comes next, team")
    void own() {
        IslandView view = new IslandView(true, true, "Raindancer118", 1000, 2, 4, 1, Optional.of(PLAINS));
        List<String> lines = plain(sidebar.draw(Optional.of(view), room -> List.of()));

        assertThat(lines).contains("Your island", "You: Owner", "Plains", "123 blocks broken", "Team 2/4 · 1 online");
        assertThat(lines).anyMatch(line -> line.startsWith("▮") && line.endsWith("64%"));
        assertThat(lines).contains("Next: Underground in 36");
        assertThat(lines.size()).isLessThanOrEqualTo(Sidebar.MAX_LINES);
    }

    @Test
    @DisplayName("on somebody else's island: theirs, and you as a visitor")
    void visiting() {
        IslandView view = new IslandView(true, false, "Ada", 0, 1, 4, 1, Optional.of(PLAINS));
        assertThat(plain(sidebar.draw(Optional.of(view), room -> List.of())))
                .contains("Ada's island", "You: Visitor");
    }

    @Test
    @DisplayName("away from any island but owning one: says so, and shows your own island's phase")
    void away() {
        IslandView view = new IslandView(false, true, "Raindancer118", 1000, 1, 4, 1, Optional.of(PLAINS));
        assertThat(plain(sidebar.draw(Optional.of(view), room -> List.of()))).contains("Your island (not here)");
    }

    @Test
    @DisplayName("no island at all: how to get one, and nothing pretending to be a phase")
    void none() {
        List<String> lines = plain(sidebar.draw(Optional.empty(), room -> List.of()));
        assertThat(lines).contains("No island yet", "/island to start one").noneMatch(line -> line.contains("Phase"));
    }

    @Test
    @DisplayName("other plugins' lines go last, and never past the client's fifteen")
    void sections() {
        IslandView view = new IslandView(true, true, "Raindancer118", 1000, 2, 4, 1, Optional.of(PLAINS));
        List<Component> extra = List.of(Component.empty(), Component.text("Guild"), Component.text(" Rain"));
        Sidebar drawn = sidebar.draw(Optional.of(view), room -> extra);
        assertThat(drawn.lines().size()).isLessThanOrEqualTo(Sidebar.MAX_LINES);
        assertThat(plain(drawn)).first().isEqualTo("Island");
    }
}
