package de.raindancer.modules.arena.rules;

import de.raindancer.modules.arena.model.Pit;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Where the next fight goes: the first pit that is set up and nobody is fighting in. */
public final class PitRule implements IArenaRule {

    public Optional<Pit> free(List<Pit> pits, Set<String> busy) {
        return pits.stream().filter(Pit::isReady).filter(pit -> !busy.contains(pit.id())).findFirst();
    }

    @Override
    public String describe() {
        return "the first free pit that is set up";
    }
}
