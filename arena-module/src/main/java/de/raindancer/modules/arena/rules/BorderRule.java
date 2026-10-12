package de.raindancer.modules.arena.rules;

import de.raindancer.modules.arena.model.ArenaWorld;

/** Where an arena world's border goes: square round the map, a margin past its longer side. */
public final class BorderRule implements IArenaRule {

    /** @param size the full width, as Bukkit's border takes it */
    public record Border(double centreX, double centreZ, double size) {
    }

    public Border border(ArenaWorld world, int margin) {
        int side = Math.max(world.mapBox().width(), world.mapBox().length());
        return new Border(world.mapBox().centreX(), world.mapBox().centreZ(), side + 2.0 * Math.max(0, margin));
    }

    @Override
    public String describe() {
        return "the border is the map's longer side plus a margin, centred on the map";
    }
}
