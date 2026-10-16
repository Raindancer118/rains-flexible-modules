package de.raindancer.modules.oneblock.store;

import de.raindancer.core.world.protection.LandAction;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.core.world.protection.LandFlag;
import de.raindancer.core.world.protection.LandProvider;
import de.raindancer.core.world.protection.ProtectedArea;
import de.raindancer.modules.oneblock.model.Island;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Islands as Core's protected ground: every world of an island is the island's, whole. The owner and the team may do
 * everything there; a guest may look round, use a crafting table and pick things up, and nothing else. Core's
 * listeners enforce it — this only answers who is who.
 */
public final class IslandLand implements LandProvider {

    private final IslandBook book;

    public IslandLand(IslandBook book) {
        this.book = book;
    }

    @Override
    public String name() {
        return "OneBlock islands";
    }

    @Override
    public Optional<ProtectedArea> at(Location location) {
        World world = location == null ? null : location.getWorld();
        return world == null ? Optional.empty() : book.ofWorld(world.getName()).map(Area::new);
    }

    @Override
    public boolean hasAnyIn(World world) {
        return world != null && Island.isIslandWorld(world.getName());
    }

    /** One island, as a protected area. Reads the island it was made from; a lookup is cheap, so that is current. */
    record Area(Island island) implements ProtectedArea {

        @Override
        public String id() {
            return "island:" + island.id();
        }

        @Override
        public String name() {
            return island.ownerName() + "'s island";
        }

        @Override
        public List<UUID> owners() {
            return List.of(island.owner());
        }

        /** The server's flag defaults hold on every island; there is no page for a team to change them. */
        @Override
        public Optional<Boolean> flagOverride(LandFlag flag, LandAudience audience) {
            return Optional.empty();
        }

        @Override
        public LandAudience audienceOf(UUID who) {
            if (island.owner().equals(who)) {
                return LandAudience.OWNER;
            }
            return island.isMember(who) ? LandAudience.TRUSTED : LandAudience.VISITOR;
        }

        @Override
        public boolean may(UUID who, LandAction action) {
            if (island.isMember(who)) {
                return true;
            }
            return action.publicByDefault();
        }
    }
}
