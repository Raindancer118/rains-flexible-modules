package de.raindancer.modules.speedrun;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpeedrunSplitTrackerTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private static SpeedrunSplitTracker running() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE, BOB));
        session.start();
        return new SpeedrunSplitTracker(session);
    }

    @Test
    @DisplayName("the first to reach a milestone splits the run; listeners hear it once")
    void firstSplitOnly() {
        SpeedrunSplitTracker tracker = running();
        List<SpeedrunSplitTracker.Split> heard = new ArrayList<>();
        tracker.onSplit(heard::add);

        assertThat(tracker.reach("enter-nether", ALICE)).isTrue();
        assertThat(tracker.reach("enter-nether", BOB)).isFalse();

        assertThat(heard).singleElement().satisfies(split -> {
            assertThat(split.who()).isEqualTo(ALICE);
            assertThat(split.milestone()).isEqualTo(SpeedrunMilestones.ENTER_NETHER);
        });
    }

    @Test
    @DisplayName("a milestone nobody declared splits nothing")
    void undeclared() {
        assertThat(running().reach("made-up", ALICE)).isFalse();
    }

    @Test
    @DisplayName("a mode's own milestone sorts after the built-ins and before the finish")
    void modeMilestones() {
        SpeedrunSplitTracker tracker = running();
        tracker.declare("first-catch", "First catch", Material.IRON_SWORD);

        List<String> order = tracker.milestones().stream().map(SpeedrunMilestone::id).toList();

        assertThat(order.getLast()).isEqualTo("finish");
        assertThat(order.get(order.size() - 2)).isEqualTo("first-catch");
        assertThat(tracker.reach("first-catch", BOB)).isTrue();
        assertThat(tracker.declare("enter-nether", "Hijacked", Material.DIRT).label())
                .as("a built-in cannot be redeclared").isEqualTo("Nether");
    }

    @Test
    @DisplayName("pearls are counted across the team, and the split comes when the target is reached")
    void pearls() {
        SpeedrunSplitTracker tracker = running();

        tracker.pearlsPickedUp(ALICE, 7, 12);
        assertThat(tracker.splits()).isEmpty();
        tracker.pearlsPickedUp(BOB, 5, 12);

        assertThat(tracker.pearls()).isEqualTo(12);
        assertThat(tracker.splits()).singleElement()
                .satisfies(split -> assertThat(split.who()).isEqualTo(BOB));
    }

    @Test
    @DisplayName("next is the first milestone not reached yet, in order")
    void next() {
        SpeedrunSplitTracker tracker = running();
        tracker.reach("enter-nether", ALICE);
        tracker.reach("fortress", ALICE);

        assertThat(tracker.next()).contains(SpeedrunMilestones.BASTION);
        assertThat(tracker.last().orElseThrow().milestone()).isEqualTo(SpeedrunMilestones.FORTRESS);
    }

    @Test
    @DisplayName("nothing splits after the run is over — except the finish itself")
    void afterTheFinish() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        SpeedrunSplitTracker tracker = new SpeedrunSplitTracker(session);
        session.finish("advancement:minecraft:end/kill_dragon");

        assertThat(tracker.reach("enter-nether", ALICE)).isFalse();
        assertThat(tracker.reach("finish", ALICE)).isTrue();
    }

    @Test
    @DisplayName("a mode's HUD lines are asked per viewer, and one that throws costs only its own lines")
    void hudLines() {
        SpeedrunSplitTracker tracker = running();
        tracker.addHudLines(viewer -> List.of(Component.text("Runners left: 2")));
        tracker.addHudLines(viewer -> {
            throw new IllegalStateException("broken");
        });

        assertThat(tracker.hudLinesFor(ALICE)).containsExactly(Component.text("Runners left: 2"));
    }
}
