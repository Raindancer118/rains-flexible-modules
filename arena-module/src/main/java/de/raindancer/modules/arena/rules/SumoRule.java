package de.raindancer.modules.arena.rules;

/** Sumo: whoever is pushed further than the ring's radius from its middle, or below its floor, is out. */
public final class SumoRule implements IArenaRule {

    public boolean isOut(double dx, double dz, double dropBelowFloor, double radius) {
        return dx * dx + dz * dz > radius * radius || dropBelowFloor > 1.5;
    }

    @Override
    public String describe() {
        return "out of the ring is out";
    }
}
