package de.raindancer.modules.arena.rules;

import de.raindancer.modules.arena.model.Box;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where a pit's water goes for a naval battle: layer by layer, the open cells inside the floor box that are reachable
 * from where the fight starts — and every open cell next to that water that is not itself water (a gate, a gap, a hole
 * underneath), which gets sealed so not one block of water ever leaves the pit. No server in here: what is open is
 * asked of a {@link Probe}.
 */
public final class BasinRule implements IArenaRule {

    public interface Probe {
        /** Whether water may stand here: air, or something it would wash away. */
        boolean open(int x, int y, int z);
    }

    public record Cell(int x, int y, int z) {
    }

    public record Layer(int y, List<Cell> water, List<Cell> seals) {
    }

    /** @param tooBig the water would have been more than allowed, so there is none */
    public record Basin(List<Layer> layers, boolean tooBig) {

        public int cells() {
            return layers.stream().mapToInt(layer -> layer.water().size() + layer.seals().size()).sum();
        }

        public Set<Cell> water() {
            Set<Cell> all = new HashSet<>();
            layers.forEach(layer -> all.addAll(layer.water()));
            return all;
        }
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * @param fromY  the lowest layer (where the fighters' feet are)
     * @param toY    the waterline, included
     * @param starts x/z columns the water spreads from
     * @param most   the most cells of water and seals together; past it nothing is flooded
     */
    public Basin plan(Box box, int fromY, int toY, List<int[]> starts, Probe probe, int most) {
        List<Layer> layers = new ArrayList<>();
        Set<Cell> below = Set.of();
        int total = 0;
        for (int y = fromY; y <= toY; y++) {
            Set<Cell> water = spread(box, y, starts, probe);
            Set<Cell> seals = new LinkedHashSet<>();
            for (Cell cell : water) {
                for (int[] side : SIDES) {
                    Cell next = new Cell(cell.x() + side[0], y, cell.z() + side[1]);
                    if (!water.contains(next) && probe.open(next.x(), next.y(), next.z())) {
                        seals.add(next);
                    }
                }
                Cell under = new Cell(cell.x(), y - 1, cell.z());
                if (!below.contains(under) && probe.open(under.x(), under.y(), under.z())) {
                    seals.add(under);
                }
            }
            total += water.size() + seals.size();
            if (total > most) {
                return new Basin(List.of(), true);
            }
            layers.add(new Layer(y, List.copyOf(water), List.copyOf(seals)));
            below = water;
        }
        return new Basin(List.copyOf(layers), false);
    }

    private static Set<Cell> spread(Box box, int y, List<int[]> starts, Probe probe) {
        Set<Cell> water = new LinkedHashSet<>();
        Deque<Cell> next = new ArrayDeque<>();
        for (int[] start : starts) {
            Cell cell = new Cell(start[0], y, start[1]);
            if (inside(box, cell) && probe.open(cell.x(), y, cell.z()) && water.add(cell)) {
                next.add(cell);
            }
        }
        while (!next.isEmpty()) {
            Cell cell = next.poll();
            for (int[] side : SIDES) {
                Cell neighbour = new Cell(cell.x() + side[0], y, cell.z() + side[1]);
                if (inside(box, neighbour) && !water.contains(neighbour)
                        && probe.open(neighbour.x(), y, neighbour.z())) {
                    water.add(neighbour);
                    next.add(neighbour);
                }
            }
        }
        return water;
    }

    private static boolean inside(Box box, Cell cell) {
        return cell.x() >= box.minX() && cell.x() <= box.maxX() && cell.z() >= box.minZ() && cell.z() <= box.maxZ();
    }

    @Override
    public String describe() {
        return "where a naval battle's water stands, and what is sealed so it stays there";
    }
}
