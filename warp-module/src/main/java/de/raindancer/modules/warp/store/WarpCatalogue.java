package de.raindancer.modules.warp.store;

import de.raindancer.modules.warp.model.Warp;
import de.raindancer.modules.warp.store.WarpRegistry;
import de.raindancer.modules.warp.model.WarpAccess;
import de.raindancer.modules.warp.rules.WarpAccessRule;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The module's door to the warps, which are RainsCore's.
 *
 * <h2>Why there is no store of its own</h2>
 * Because a warp is a place with a name, a world and coordinates, and RainsCore already keeps those
 * — persistence, atomic writes, worlds that are not loaded and "is this reachable" are solved there
 * and tested there. A second store that happened to look the same would mean a ghast line could not
 * fly to a warp, a menu could not list warps beside homes, and deleting a world would leave its
 * warps behind pointing at nothing.
 *
 * <p>So what is here is the two things the module adds on top: the {@link WarpAccess} reading of the
 * permission Core keeps, and writing straight through to disk.
 *
 * <h2>Why every change flushes</h2>
 * Because these are access decisions. A warp made staff-only now and public again after the next
 * restart is a hole found by somebody walking into the staff room, and by then nobody remembers
 * which restart it was.
 */
public final class WarpCatalogue {

    private final WarpRegistry warps;
    /** Writing the places out. Core's {@code PoiStore::flush}, behind an interface for the tests. */
    private final Runnable flush;

    public WarpCatalogue(WarpRegistry warps, Runnable flush) {
        this.warps = warps;
        this.flush = flush == null ? () -> {
        } : flush;
    }

    /**
     * Lets go of a player who has left. Called on quit — see {@code WarpSessionListener} and
     * {@link WarpRegistry#leaves}: what is over is dropped, what is still running is not.
     */
    public void leaves(java.util.UUID who) {
        warps.leaves(who);
    }

    // ------------------------------------------------------------------------ looking

    public List<Warp> all() {
        return warps.all();
    }

    public Optional<Warp> byName(String name) {
        return warps.byName(name);
    }

    public int count() {
        return warps.all().size();
    }

    /** What the permission on a warp means. */
    public WarpAccess accessOf(Warp warp) {
        return warp == null ? WarpAccess.EVERYONE : WarpAccess.from(warp.permission().orElse(null));
    }

    /**
     * The warps this player is shown, in alphabetical order.
     *
     * <p>Filtered by the rule rather than by Core's own {@code visibleTo}, because the rule is where
     * "an admin sees everything" lives and Core has no idea what an admin of this module is.
     */
    public List<Warp> visibleTo(Predicate<String> hasPermission, WarpAccessRule rule) {
        return visibleTo(null, hasPermission, rule);
    }

    /**
     * The warps {@code who} is shown — their own, the private ones they were added to, and whatever
     * their permissions open. Null is somebody with no identity, the console, who sees by node alone.
     */
    public List<Warp> visibleTo(UUID who, Predicate<String> hasPermission, WarpAccessRule rule) {
        return warps.all().stream()
                .filter(warp -> rule.maySee(accessOf(warp), hasPermission, who, warp.owner().orElse(null),
                        warp.members()))
                .sorted(Comparator.comparing(Warp::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * The warps in one category that this player is shown.
     *
     * @param category null gives the ones filed under nothing
     */
    public List<Warp> inCategory(String category, Predicate<String> hasPermission,
                                 WarpAccessRule rule) {
        return inCategory(category, null, hasPermission, rule);
    }

    public List<Warp> inCategory(String category, UUID who, Predicate<String> hasPermission,
                                 WarpAccessRule rule) {
        return visibleTo(who, hasPermission, rule).stream()
                .filter(warp -> category == null
                        ? warp.category().isEmpty()
                        : warp.category().map(category::equalsIgnoreCase).orElse(false))
                .toList();
    }

    /**
     * The categories this player would find something in.
     *
     * <p>Only the ones with a warp they can see: a category page listing "Staff" with nothing behind
     * it tells an ordinary player exactly what they were not meant to be told.
     */
    public Set<String> categoriesVisibleTo(Predicate<String> hasPermission, WarpAccessRule rule) {
        return categoriesVisibleTo(null, hasPermission, rule);
    }

    public Set<String> categoriesVisibleTo(UUID who, Predicate<String> hasPermission, WarpAccessRule rule) {
        Set<String> found = new LinkedHashSet<>();
        for (Warp warp : visibleTo(who, hasPermission, rule)) {
            warp.category().ifPresent(found::add);
        }
        return found;
    }

    /** Whether any visible warp is filed under nothing, so the menu knows to offer that page. */
    public boolean hasUncategorised(Predicate<String> hasPermission, WarpAccessRule rule) {
        return hasUncategorised(null, hasPermission, rule);
    }

    public boolean hasUncategorised(UUID who, Predicate<String> hasPermission, WarpAccessRule rule) {
        return visibleTo(who, hasPermission, rule).stream().anyMatch(warp -> warp.category().isEmpty());
    }

    /** Every warp this player owns, in alphabetical order. */
    public List<Warp> ownedBy(UUID owner) {
        return owner == null ? List.of() : warps.ownedBy(owner);
    }

    // ------------------------------------------------------------------------ changing

    /** Makes one from plain values — for a test, or anything that is not a player standing somewhere. */
    public Optional<Warp> create(String name, String world, double x, double y, double z, UUID creator) {
        Optional<Warp> made = warps.create(name, world, x, y, z, creator);
        made.ifPresent(ignored -> flush.run());
        return made;
    }

    /** Moves one from plain values; see {@link #move(String, Location)}. */
    public boolean move(String name, String world, double x, double y, double z, float yaw, float pitch) {
        return written(warps.move(name, world, x, y, z, yaw, pitch));
    }

    /** Hands a warp to somebody else, keeping its people and everything else. */
    public boolean setOwner(String name, UUID owner) {
        return written(warps.setOwner(name, owner));
    }

    /** Lets somebody into a private warp. */
    public boolean addMember(String name, UUID member) {
        return byName(name).map(warp -> {
            Set<UUID> next = new LinkedHashSet<>(warp.members());
            next.add(member);
            return written(warps.setMembers(name, next));
        }).orElse(false);
    }

    /** Takes somebody off a private warp's list. False when there was no such warp. */
    public boolean removeMember(String name, UUID member) {
        return byName(name).map(warp -> {
            Set<UUID> next = new LinkedHashSet<>(warp.members());
            next.remove(member);
            return written(warps.setMembers(name, next));
        }).orElse(false);
    }

    /** Makes one where somebody is standing. */
    public Optional<Warp> create(String name, Location where, UUID creator) {
        Optional<Warp> made = warps.create(name, where, creator);
        made.ifPresent(ignored -> flush.run());
        return made;
    }

    /** Moves one, keeping its access, its category and its icon — see {@code WarpRegistry.move}. */
    public boolean move(String name, Location where) {
        return written(warps.move(name, where));
    }

    public boolean delete(String name) {
        return written(warps.delete(name));
    }

    /** What the owner charges to visit it. */
    public boolean setVisitFee(String name, de.raindancer.core.social.economy.Money fee) {
        return written(warps.setVisitFee(name, fee));
    }

    /** Its rent state; see {@link WarpRegistry#setRent}. */
    public boolean setRent(String name, Long paidUntil, boolean closed) {
        return written(warps.setRent(name, paidUntil, closed));
    }

    /** Who a warp is for. */
    public boolean setAccess(String name, WarpAccess access) {
        if (access == null) {
            return false;
        }
        return written(warps.setPermission(name, access.permission().orElse(null)));
    }

    /** What it is filed under; null takes it out of every category. */
    public boolean setCategory(String name, String category) {
        return written(warps.setCategory(name, category));
    }

    /** What a menu calls it; null puts it back to being called by its name. */
    public boolean setLabel(String name, String label) {
        return written(warps.setLabel(name, label));
    }

    public boolean setIcon(String name, Material icon) {
        return written(warps.setIcon(name, icon));
    }

    private boolean written(boolean changed) {
        if (changed) {
            flush.run();
        }
        return changed;
    }
}
