package de.raindancer.modules.guilds.store;

import de.raindancer.modules.guilds.model.Boost;
import de.raindancer.modules.guilds.model.Contract;
import de.raindancer.modules.guilds.model.Standing;
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

    /**
     * The honours half, in one transaction: these guilds' standings and perks replaced, their week totals set,
     * the forgotten guilds' rows gone, the crown set.
     */
    boolean writeHonours(Map<String, Standing> standings, Map<String, Map<Long, Long>> weekXp, Set<String> forgotten,
                         Optional<CrownState> crown);

    /** Who wears the crown, for which week, and the last week whose winner was settled. */
    record CrownState(String guild, long week, long settledWeek) {

        public static final CrownState NONE = new CrownState("", -1, -1);
    }

    record Loaded(Map<String, Map<XpSource, Long>> totals, Collection<Contract> contracts, Boost boost,
                  Map<String, Standing> standings, Map<String, Map<Long, Long>> weekXp, CrownState crown) {
    }
}
