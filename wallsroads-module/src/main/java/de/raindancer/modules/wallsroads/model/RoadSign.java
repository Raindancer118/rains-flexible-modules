package de.raindancer.modules.wallsroads.model;

import de.raindancer.core.world.safety.Spot;

import java.util.List;

/**
 * One auto-placed sign belonging to a road — at an endpoint, or at a gate it passes through.
 * Immutable; renaming produces a new instance via {@link #withLines(List)}.
 */
public record RoadSign(String id, String roadId, Spot spot, String facing, List<String> lines) {

    public RoadSign {
        lines = List.copyOf(lines);
    }

    public RoadSign withLines(List<String> newLines) {
        return new RoadSign(id, roadId, spot, facing, newLines);
    }
}
