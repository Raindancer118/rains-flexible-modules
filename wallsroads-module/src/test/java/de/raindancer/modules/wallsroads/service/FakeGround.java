package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.Ground;
import de.raindancer.core.world.safety.Spot;

import java.util.HashMap;
import java.util.Map;

/** A world as a map — the same test double {@code RainsCore}'s own {@code VeinsTest} uses. */
final class FakeGround implements Ground {

    private final Map<Spot, String> blocks = new HashMap<>();
    private String everywhereElse = "GRASS_BLOCK";

    FakeGround put(Spot spot, String material) {
        blocks.put(spot, material);
        return this;
    }

    FakeGround fillWith(String material) {
        everywhereElse = material;
        return this;
    }

    @Override
    public String materialAt(Spot spot) {
        return blocks.getOrDefault(spot, everywhereElse);
    }

    @Override
    public boolean set(Spot spot, String material) {
        blocks.put(spot, material);
        return true;
    }

    Map<Spot, String> copyOfBlocks() {
        return new HashMap<>(blocks);
    }

    long countOf(String material) {
        return blocks.values().stream().filter(material::equals).count();
    }
}
