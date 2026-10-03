package de.raindancer.modules.speedrun.manhunt.hud;

import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.manhunt.tracker.TrackerCompass.Point;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What one player's sidebar says — as wording keys under {@code manhunt.hud} and their placeholders,
 * so the decisions are tested here and the words live in {@code messages.yml}.
 *
 * <p>Distances are a Hunter's, and only to Runners in their own dimension: a Runner is not told how
 * far their teammates are, and a distance across a portal is a number that means nothing.
 */
public final class HudLines {

    /**
     * Lines Manhunt takes of the lobby's sidebar — the rest is the run's last splits and what is
     * next, which the lobby draws above these.
     */
    static final int MAX = 12;

    /** One line: a key under {@code manhunt.hud}, and placeholder name/value pairs. */
    public record Line(String key, String... placeholders) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Line line && line.key.equals(key)
                    && java.util.Arrays.equals(line.placeholders, placeholders);
        }

        @Override
        public int hashCode() {
            return key.hashCode() * 31 + java.util.Arrays.hashCode(placeholders);
        }

        @Override
        public String toString() {
            return key + java.util.Arrays.toString(placeholders);
        }
    }

    /** Where somebody online is, and the dimension's name as players say it. */
    public record Where(Point at, String dimension) {
    }

    private HudLines() {
    }

    /**
     * @param where everybody online, by id — absent is offline
     * @param lives the hunt's lives per Runner
     */
    public static List<Line> build(UUID viewer, Hunt hunt, Map<UUID, String> names,
                                   Map<UUID, Where> where, int lives) {
        List<Line> lines = new ArrayList<>();
        List<UUID> runners = new ArrayList<>(hunt.runners());
        runners.sort(Comparator.comparing((UUID id) -> hunt.isEliminated(id))
                .thenComparing(id -> names.getOrDefault(id, "")));
        lines.add(new Line("runners", "left", String.valueOf(hunt.livingRunners().size()),
                "total", String.valueOf(runners.size())));
        boolean viewerHunts = hunt.isHunter(viewer);
        Where mine = where.get(viewer);
        // Clock, Runners heading, Hunters line, "you" line: four that are always there.
        int room = MAX - 3;
        int shown = 0;
        for (UUID runner : runners) {
            if (shown == room - 1 && runners.size() > room) {
                lines.add(new Line("more", "count", String.valueOf(runners.size() - shown)));
                break;
            }
            lines.add(runnerLine(runner, hunt, names, where, viewerHunts ? mine : null, lives));
            shown++;
        }
        long huntersOnline = hunt.hunters().stream().filter(where::containsKey).count();
        lines.add(new Line("hunters", "online", String.valueOf(huntersOnline),
                "total", String.valueOf(hunt.hunters().size())));
        if (hunt.isRunner(viewer)) {
            lines.add(new Line("you-runner", "lives",
                    String.valueOf(Math.max(0, lives - hunt.deathsOf(viewer)))));
        } else {
            lines.add(new Line("you-hunter"));
        }
        return List.copyOf(lines);
    }

    private static Line runnerLine(UUID runner, Hunt hunt, Map<UUID, String> names, Map<UUID, Where> where,
                                   Where hunter, int lives) {
        String name = names.getOrDefault(runner, "somebody");
        if (hunt.isEliminated(runner)) {
            return new Line("runner-caught", "name", name);
        }
        Where at = where.get(runner);
        if (at == null) {
            return new Line("runner-offline", "name", name);
        }
        String hearts = lives > 1 ? " ❤" + Math.max(0, lives - hunt.deathsOf(runner)) : "";
        if (hunter != null && hunter.at().worldName().equals(at.at().worldName())) {
            long blocks = Math.round(Math.hypot(at.at().x() - hunter.at().x(), at.at().z() - hunter.at().z()));
            return new Line("runner-near", "name", name, "where", at.dimension(), "blocks", String.valueOf(blocks),
                    "hearts", hearts);
        }
        return new Line("runner", "name", name, "where", at.dimension(), "hearts", hearts);
    }
}
