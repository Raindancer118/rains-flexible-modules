package de.raindancer.modules.guilds.store;

import de.raindancer.modules.guilds.model.Boost;
import de.raindancer.modules.guilds.model.Contract;
import de.raindancer.modules.guilds.model.XpSource;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Where experience by source, the weekly contracts and the boost live between restarts. */
public interface ProgressStorage {

    /** Everything, or empty when it cannot be read at all. */
    Optional<Loaded> load();

    /** One transaction: these guilds' totals and contracts replaced, the forgotten guilds' rows gone, the boost set. */
    boolean write(Map<String, Map<XpSource, Long>> totals, Map<String, List<Contract>> contracts,
                  Set<String> forgotten, Optional<Boost> boost);

    record Loaded(Map<String, Map<XpSource, Long>> totals, Collection<Contract> contracts, Boost boost) {
    }
}
