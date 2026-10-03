package de.raindancer.modules.speedrun.manhunt.hud;

import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.manhunt.tracker.TrackerCompass.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("the sidebar")
class HudLinesTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("ben".getBytes());
    private static final UUID CARO = UUID.nameUUIDFromBytes("caro".getBytes());
    private static final UUID DAN = UUID.nameUUIDFromBytes("dan".getBytes());

    private final Hunt hunt = Hunt.of(Set.of(ANNA, BEN, CARO, DAN), Set.of(ANNA, BEN));
    private final Map<UUID, String> names = Map.of(ANNA, "Anna", BEN, "Ben", CARO, "Caro", DAN, "Dan");
    private final Map<UUID, HudLines.Where> where = new HashMap<>();

    {
        where.put(ANNA, new HudLines.Where(new Point("world", 100, 64, 0), "Overworld"));
        where.put(BEN, new HudLines.Where(new Point("world_nether", 0, 64, 0), "Nether"));
        where.put(CARO, new HudLines.Where(new Point("world", 0, 64, 0), "Overworld"));
    }

    private List<HudLines.Line> forViewer(UUID viewer, int lives) {
        return HudLines.build(viewer, hunt, names, where, lives);
    }

    @Test
    @DisplayName("a Hunter sees the clock, each Runner with where they are and how far, and the pack")
    void hunterView() {
        List<HudLines.Line> lines = forViewer(CARO, 1);

        // The clock is the lobby's sidebar title now — one clock, not two.
        assertThat(lines).noneMatch(line -> line.key().equals("clock"));
        assertThat(lines).contains(new HudLines.Line("runners", "left", "2", "total", "2"));
        assertThat(lines).contains(new HudLines.Line("runner-near", "name", "Anna", "where", "Overworld",
                "blocks", "100", "hearts", ""));
        assertThat(lines).as("no distance across dimensions")
                .contains(new HudLines.Line("runner", "name", "Ben", "where", "Nether", "hearts", ""));
        assertThat(lines).contains(new HudLines.Line("hunters", "online", "1", "total", "2"));
        assertThat(lines.getLast()).isEqualTo(new HudLines.Line("you-hunter"));
    }

    @Test
    @DisplayName("a Runner sees no distances to their own side, and their own lives")
    void runnerView() {
        hunt.recordDeath(ANNA);

        List<HudLines.Line> lines = forViewer(ANNA, 3);

        assertThat(lines).contains(new HudLines.Line("runner", "name", "Anna", "where", "Overworld",
                "hearts", " ❤2"));
        assertThat(lines.getLast()).isEqualTo(new HudLines.Line("you-runner", "lives", "2"));
    }

    @Test
    @DisplayName("a caught Runner is crossed off, an offline one says so, and the list never overflows the sidebar")
    void caughtOfflineAndLong() {
        hunt.eliminate(BEN);
        where.remove(ANNA);

        List<HudLines.Line> few = forViewer(CARO, 1);
        assertThat(few).contains(new HudLines.Line("runner-caught", "name", "Ben"));
        assertThat(few).contains(new HudLines.Line("runner-offline", "name", "Anna"));

        for (int i = 0; i < 20; i++) {
            hunt.join(UUID.randomUUID(), true);
        }
        List<HudLines.Line> lines = forViewer(CARO, 1);
        assertThat(lines).hasSizeLessThanOrEqualTo(15);
        assertThat(lines).anySatisfy(line -> assertThat(line.key()).isEqualTo("more"));
    }

    @Test
    @DisplayName("glows: warned ten seconds before, then the glow, every period — even across a skipped second")
    void glowSchedule() {
        assertThat(GlowSchedule.between(0, 589, 10)).isEmpty();
        assertThat(GlowSchedule.between(589, 590, 10)).containsExactly(GlowSchedule.Cue.WARN);
        assertThat(GlowSchedule.between(598, 601, 10)).containsExactly(GlowSchedule.Cue.GLOW);
        assertThat(GlowSchedule.between(1189, 1201, 10))
                .containsExactly(GlowSchedule.Cue.WARN, GlowSchedule.Cue.GLOW);
        assertThat(GlowSchedule.between(0, 100_000, 0)).as("off").isEmpty();
    }
}
