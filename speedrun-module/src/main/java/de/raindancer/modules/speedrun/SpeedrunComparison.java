package de.raindancer.modules.speedrun;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * How a split stands against what came before it, for one viewer: against their own personal best,
 * against the server record, and whether it is the best time anybody ever reached that point in —
 * a gold split.
 *
 * @param vsPersonalBest this split minus the same split of the viewer's personal best run
 * @param vsRecord       this split minus the same split of the record run
 * @param gold           faster than any ranked run of the category ever reached this milestone
 */
public record SpeedrunComparison(Optional<Duration> vsPersonalBest, Optional<Duration> vsRecord, boolean gold) {

    public static final SpeedrunComparison NOTHING_TO_COMPARE =
            new SpeedrunComparison(Optional.empty(), Optional.empty(), false);

    /** {@code at} — the time {@code milestoneId} was just reached — against {@code history}. */
    public static SpeedrunComparison of(SpeedrunHistory history, SpeedrunCategory category, UUID viewer,
                                        String milestoneId, Duration at) {
        if (history == null || category == null || at == null) {
            return NOTHING_TO_COMPARE;
        }
        Optional<Duration> personal = viewer == null ? Optional.empty()
                : history.personalBest(viewer, category).flatMap(run -> run.splitAt(milestoneId)).map(at::minus);
        Optional<Duration> record = history.record(category).flatMap(run -> run.splitAt(milestoneId)).map(at::minus);
        boolean gold = history.bestSplit(category, milestoneId).map(best -> at.compareTo(best) < 0).orElse(false);
        return new SpeedrunComparison(personal, record, gold);
    }

    /** {@code -0:12} green when ahead, {@code +0:04} red when behind, {@code ±0:00} grey when level. */
    public static Component delta(Duration difference) {
        long seconds = difference.toSeconds();
        String sign = seconds < 0 ? "-" : seconds > 0 ? "+" : "±";
        NamedTextColor colour = seconds < 0 ? NamedTextColor.GREEN : seconds > 0 ? NamedTextColor.RED
                : NamedTextColor.GRAY;
        return Component.text(sign + SpeedrunTimerDisplay.plain(difference.abs()), colour);
    }

    /** The deltas worth showing, as one short component — empty when there is nothing to compare. */
    public Component describe() {
        Component text = Component.empty();
        boolean any = false;
        if (vsPersonalBest.isPresent()) {
            text = text.append(Component.text("PB ", NamedTextColor.GRAY)).append(delta(vsPersonalBest.get()));
            any = true;
        }
        if (vsRecord.isPresent()) {
            if (any) {
                text = text.append(Component.text("  "));
            }
            text = text.append(Component.text("WR ", NamedTextColor.GRAY)).append(delta(vsRecord.get()));
        }
        return text;
    }
}
