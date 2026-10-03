package de.raindancer.modules.speedrun;

import de.raindancer.modules.api.ReuseContract;

import java.nio.file.Path;
import java.util.Map;

/**
 * That this module reaches for RainsCore rather than growing its own — see {@link ReuseContract}.
 *
 * <p>This module keeps its screens beside everything else rather than in a {@code screen} package, so
 * the screen-only rules are pointed at the module root. That makes {@code new ItemStack(} reachable by
 * them, and {@link SpeedrunLobbyItems} legitimately builds one: the compass and the green block a
 * racer is handed are real items in a real inventory, not buttons on a page. Named here rather than
 * dropped from the rule, so the next one has to be argued for too.
 */
class ReuseTest implements ReuseContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/speedrun");
    }

    @Override
    public Path screenSource() {
        return moduleSource();
    }

    @Override
    public Map<String, String> allowed() {
        return Map.of("SpeedrunLobbyItems.java :: new ItemStack(",
                "the lobby items are real items handed to a racer, not menu buttons",
                "SpeedrunPreparation.java :: new ItemStack(",
                "a practice kit is real blaze rods and pearls in a racer's inventory, not a menu button",
                "manhunt/tracker/TrackerCompassService.java :: new ItemStack(",
                "Manhunt's tracking compass is a real item in a Hunter's hand",
                "manhunt/tracker/TeamCompassService.java :: new ItemStack(",
                "Manhunt's team compass is a real item in a racer's hand",
                "manhunt/tracker/StructureCompassService.java :: new ItemStack(",
                "the Runners' structure compass is a real item in their hand");
    }
}
