package de.raindancer.modules.wallsroads.model;

import de.raindancer.core.world.safety.Spot;

import java.util.List;

/**
 * One sign belonging to a road — at an endpoint, at a gate it passes through, or at a junction with
 * another road.
 *
 * <p>{@code replaced} is what stood where the sign now stands. Without it, taking a sign away meant
 * setting the block to air, which punched a hole in whatever the sign was standing on — usually the
 * road's own surface, since that is where the old code put them.
 */
public record RoadSign(String id, String roadId, Spot spot, int rotation, List<String> lines,
                       String replaced) {

    public RoadSign {
        lines = List.copyOf(lines);
        rotation = ((rotation % 16) + 16) % 16;
    }

    public RoadSign withLines(List<String> newLines) {
        return new RoadSign(id, roadId, spot, rotation, newLines, replaced);
    }
}
