package de.raindancer.modules.arena.rules;

import de.raindancer.modules.arena.model.Box;

/**
 * How much of the world a pit's fight may change when its kit builds and blows things up: the floor box and a margin
 * round it for what an explosion reaches, from a little under the floor to well above it for what is stacked or flung
 * up, held inside the world's height. Everything inside is put back after each round.
 */
public final class BuildAreaRule implements IArenaRule {

    /** Blocks past the floor on every side: a TNT blast or an end crystal reaches this far past where it went off. */
    public static final int MARGIN = 10;
    public static final int BELOW = 6;
    public static final int ABOVE = 30;

    public Box around(Box floor, int lowestY, int highestY) {
        int minY = Math.max(lowestY, floor.minY() - BELOW);
        int maxY = Math.min(highestY, floor.maxY() + ABOVE);
        return new Box(floor.world(), floor.minX() - MARGIN, minY, floor.minZ() - MARGIN,
                floor.maxX() + MARGIN, Math.max(minY, maxY), floor.maxZ() + MARGIN);
    }

    @Override
    public String describe() {
        return "the part of the world a building fight may change, and so has to put back";
    }
}
