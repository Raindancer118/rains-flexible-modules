package de.raindancer.modules.arena.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Everything an admin has set up: the arena's worlds, the hub, the pits. A value — every change is a new one. */
public record Layout(List<ArenaWorld> worlds, Place hubPlace, List<Pit> pits) {

    public static final Layout EMPTY = new Layout(List.of(), null, List.of());

    public Layout {
        worlds = List.copyOf(worlds);
        pits = List.copyOf(pits);
    }

    public Optional<Place> hub() {
        return Optional.ofNullable(hubPlace);
    }

    public boolean isArenaWorld(String world) {
        return world != null && worlds.stream().anyMatch(each -> each.is(world));
    }

    public Optional<ArenaWorld> world(String name) {
        return worlds.stream().filter(each -> each.is(name)).findFirst();
    }

    public Optional<Pit> pit(String id) {
        return pits.stream().filter(each -> each.id().equalsIgnoreCase(id)).findFirst();
    }

    public List<Pit> pitsIn(String world) {
        return pits.stream().filter(each -> each.world().equalsIgnoreCase(world)).toList();
    }

    public Optional<Pit> pitAt(String world, double x, double y, double z) {
        return pits.stream().filter(each -> each.floor() != null && each.floor().contains(world, x, y, z)).findFirst();
    }

    public Layout withWorld(ArenaWorld world) {
        List<ArenaWorld> changed = new ArrayList<>(worlds);
        int at = indexOfWorld(world.name());
        if (at >= 0) {
            changed.set(at, world);
        } else {
            changed.add(world);
        }
        return new Layout(changed, hubPlace, pits);
    }

    public Layout withHub(Place hub) {
        return new Layout(worlds, hub, pits);
    }

    public Layout withPit(Pit pit) {
        List<Pit> changed = new ArrayList<>(pits);
        int at = -1;
        for (int index = 0; index < changed.size(); index++) {
            if (changed.get(index).id().equals(pit.id())) {
                at = index;
            }
        }
        if (at >= 0) {
            changed.set(at, pit);
        } else {
            changed.add(pit);
        }
        return new Layout(worlds, hubPlace, changed);
    }

    public Layout withoutPit(String id) {
        return new Layout(worlds, hubPlace, pits.stream().filter(each -> !each.id().equalsIgnoreCase(id)).toList());
    }

    private int indexOfWorld(String name) {
        for (int index = 0; index < worlds.size(); index++) {
            if (worlds.get(index).is(name)) {
                return index;
            }
        }
        return -1;
    }
}
