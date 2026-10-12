package de.raindancer.modules.arena.rules;

import de.raindancer.modules.arena.model.Stats;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;

/** The leaderboard over the hub, as MiniMessage: champions by wins, then who got furthest in mob waves. */
public final class BoardRule implements IArenaRule {

    private static final String[] PLACES = {"<gold>①", "<gray>②", "<#cd7f32>③"};

    public String text(List<Stats> byWins, List<Stats> byWave) {
        StringBuilder out = new StringBuilder("<gold><bold>⚔ ARENA CHAMPIONS ⚔</bold></gold>\n");
        if (byWins.isEmpty()) {
            out.append("<gray>No champion yet — be the first!\n");
        }
        for (int index = 0; index < byWins.size(); index++) {
            Stats each = byWins.get(index);
            out.append(place(index)).append(" <white>").append(name(each)).append("</white> <gray>— <yellow>")
                    .append(each.wins()).append(each.wins() == 1 ? " win" : " wins").append("\n");
        }
        if (!byWave.isEmpty()) {
            out.append("\n<red><bold>☠ WAVE SURVIVORS ☠</bold></red>\n");
            for (int index = 0; index < byWave.size(); index++) {
                Stats each = byWave.get(index);
                out.append(place(index)).append(" <white>").append(name(each)).append("</white> <gray>— wave <yellow>")
                        .append(each.bestWave()).append("\n");
            }
        }
        return out.toString().stripTrailing();
    }

    private static String place(int index) {
        return index < PLACES.length ? PLACES[index] : "<dark_gray>" + (index + 1) + ".";
    }

    private static String name(Stats each) {
        return MiniMessage.miniMessage().escapeTags(each.name() == null ? "?" : each.name());
    }

    @Override
    public String describe() {
        return "the leaderboard over the hub";
    }
}
