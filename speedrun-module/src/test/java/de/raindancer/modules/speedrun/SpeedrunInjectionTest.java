package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.messages.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A player chooses their name, renames a sword on an anvil before dying to it, types a seed. None of
 * that may ever be read as markup: a line built from it shows the tags, it never obeys them.
 */
class SpeedrunInjectionTest {

    static final String CLICK = "<click:run_command:'/op Mallory'>free diamonds</click>";
    static final String RED = "<red>Mallory</red>";
    private static final UUID MALLORY = UUID.nameUUIDFromBytes("mallory".getBytes());

    @TempDir
    Path folder;

    private static Component render(String miniMessage) {
        return MiniMessage.miniMessage().deserialize(miniMessage);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Every click event and every colour anywhere in {@code component}'s tree. */
    private static void walk(Component component, List<ClickEvent> clicks, List<Object> colours) {
        if (component.clickEvent() != null) {
            clicks.add(component.clickEvent());
        }
        if (component.color() != null) {
            colours.add(component.color());
        }
        component.children().forEach(child -> walk(child, clicks, colours));
    }

    private static void obeysNothing(String line, String expectedText) {
        Component rendered = render(line);
        List<ClickEvent> clicks = new ArrayList<>();
        List<Object> colours = new ArrayList<>();
        walk(rendered, clicks, colours);
        assertThat(clicks).as("no click event from " + line).isEmpty();
        // The tags still being in the text a player reads is the proof they were not obeyed.
        assertThat(plain(rendered)).contains(expectedText);
    }

    @Test
    @DisplayName("text() keeps every tag as text")
    void text() {
        obeysNothing("<gray>" + SpeedrunScreens.text(CLICK), CLICK);
        obeysNothing("<gray>" + SpeedrunScreens.text(RED), RED);
    }

    @Test
    @DisplayName("a summary's death line shows a renamed sword's tags and the killer's name as text")
    void deathLine() {
        SpeedrunRunRecord run = new SpeedrunRunRecord("r", SpeedrunHistoryTest.DRAGON, 1, Duration.ofMinutes(1),
                "x", false, 1, Map.of(MALLORY, RED), List.of(), Map.of());
        SpeedrunTimeline.Entry death = new SpeedrunTimeline.Entry(SpeedrunTimeline.Kind.DEATH, Duration.ofSeconds(5),
                MALLORY, "Alice was slain by Mallory using [" + CLICK + "]");

        List<String> lines = SpeedrunRunSummaryMenu.lines(run, death);

        obeysNothing(lines.get(0), RED + " died");
        obeysNothing(lines.get(1), CLICK);
    }

    @Test
    @DisplayName("a mode's milestone label, a clock edit's old reading and a finish reason are text too")
    void otherEntries() {
        SpeedrunRunRecord run = new SpeedrunRunRecord("r", SpeedrunHistoryTest.DRAGON, 1, Duration.ofMinutes(1),
                "x", false, 1, Map.of(MALLORY, "Mallory"), List.of(), Map.of("evil", CLICK));
        obeysNothing(SpeedrunRunSummaryMenu.lines(run, new SpeedrunTimeline.Entry(SpeedrunTimeline.Kind.SPLIT,
                Duration.ZERO, MALLORY, "evil")).getFirst(), CLICK);
        obeysNothing(SpeedrunRunSummaryMenu.lines(run, new SpeedrunTimeline.Entry(SpeedrunTimeline.Kind.CLOCK_EDIT,
                Duration.ZERO, null, RED)).get(1), RED);
        obeysNothing(SpeedrunRunSummaryMenu.lines(run, new SpeedrunTimeline.Entry(SpeedrunTimeline.Kind.FINISH,
                Duration.ZERO, null, CLICK)).get(1), CLICK);
    }

    @Test
    @DisplayName("Core's Messages escapes every placeholder value — a name or seed sent through it is text")
    void coreMessagesEscapePlaceholders() {
        Messages messages = new Messages(folder.resolve("messages.yml"));
        messages.load(new ByteArrayInputStream("speedrun:\n  seed: \"<gray>Seed: <white><seed></white>\"\n"
                .getBytes(StandardCharsets.UTF_8)));

        obeysNothing(MiniMessage.miniMessage().serialize(messages.get("speedrun.seed", "seed", CLICK)), CLICK);
        Component sent = messages.get("speedrun.seed", "seed", RED);
        List<ClickEvent> clicks = new ArrayList<>();
        List<Object> colours = new ArrayList<>();
        walk(sent, clicks, colours);
        assertThat(colours).doesNotContain(NamedTextColor.RED);
        assertThat(plain(sent)).isEqualTo("Seed: " + RED);
    }
}
