package de.raindancer.modules.wallsroads.store;

import de.raindancer.core.world.build.BatchBuilder;
import de.raindancer.core.world.build.BuildSnapshot;
import de.raindancer.core.world.safety.Spot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which structure owns which block.
 *
 * <h2>Why this exists</h2>
 * Without it, a second road crossing the first paves over the crossing and — this is the damaging
 * half — records the <em>first road's paving</em> as what was there before. Tear the second road up
 * afterwards and it dutifully "restores" gravel where the first road's surface used to be, leaving
 * a hole through a road nobody touched. The same for a wall built across a standing road.
 *
 * <p>The rule is therefore: a structure may only place into blocks that are free or already its
 * own. A crossing belongs to whichever got there first, and looks like a crossing either way.
 *
 * <p>Rebuilt at load from what each structure's snapshot says it covered, so it never needs a file
 * of its own to fall out of step with.
 */
public final class Occupancy {

    private final Map<Spot, String> ownerBySpot = new HashMap<>();
    private final Map<String, Set<Spot>> spotsByOwner = new HashMap<>();

    /** Records everything this structure covered, replacing whatever it held before. */
    public void claim(String structureId, BuildSnapshot snapshot) {
        release(structureId);
        Set<Spot> mine = new HashSet<>();
        for (BuildSnapshot.Placement placement : snapshot.placements()) {
            ownerBySpot.put(placement.spot(), structureId);
            mine.add(placement.spot());
        }
        spotsByOwner.put(structureId, mine);
    }

    public void release(String structureId) {
        Set<Spot> mine = spotsByOwner.remove(structureId);
        if (mine == null) {
            return;
        }
        for (Spot spot : mine) {
            ownerBySpot.remove(spot, structureId);
        }
    }

    public Optional<String> ownerOf(Spot spot) {
        return Optional.ofNullable(ownerBySpot.get(spot));
    }

    /** Whether this structure may build here: nobody else has. */
    public boolean isFreeFor(Spot spot, String structureId) {
        String owner = ownerBySpot.get(spot);
        return owner == null || owner.equals(structureId);
    }

    /** The same queue, minus anything that would build over somebody else's structure. */
    public List<BatchBuilder.Placement> filter(List<BatchBuilder.Placement> placements, String structureId) {
        List<BatchBuilder.Placement> allowed = new ArrayList<>(placements.size());
        for (BatchBuilder.Placement placement : placements) {
            if (isFreeFor(placement.spot(), structureId)) {
                allowed.add(placement);
            }
        }
        return allowed;
    }

    public int count() {
        return ownerBySpot.size();
    }
}
