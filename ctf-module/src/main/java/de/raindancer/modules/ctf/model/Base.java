package de.raindancer.modules.ctf.model;

import de.raindancer.core.social.team.TeamColour;

import java.util.List;

/** One side's home on a map: where its flag stands and where its players come back to life. */
public record Base(TeamColour colour, Point flag, List<Point> spawns) {

    public Base {
        spawns = spawns == null ? List.of() : List.copyOf(spawns);
    }

    public Base withFlag(Point at) {
        return new Base(colour, at, spawns);
    }

    public Base withSpawn(Point at) {
        java.util.List<Point> more = new java.util.ArrayList<>(spawns);
        more.add(at);
        return new Base(colour, flag, more);
    }

    public Base withoutSpawns() {
        return new Base(colour, flag, List.of());
    }

    public boolean isComplete() {
        return flag != null && !spawns.isEmpty();
    }
}
