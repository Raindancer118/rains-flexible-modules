package de.raindancer.modules.farmworld.store;

import de.raindancer.core.world.farm.FarmWorlds;
import de.raindancer.core.world.farm.WorldSet;
import de.raindancer.modules.farmworld.model.FarmWorldView;
import de.raindancer.modules.farmworld.rules.FarmAccessRule;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The module's door to the farm worlds, which are RainsCore's.
 *
 * <h2>Why there is no store of its own</h2>
 * Because a farm world is three linked worlds with a schedule, and RainsCore already keeps those:
 * {@code farmworlds.yml} for what an owner wrote, the database for when each was last made, the portal
 * linking, and — the part that matters most — {@code FarmWorldState.mayDelete}, the one pure function
 * standing between a typed command and a deleted server. A second record of which farm worlds exist
 * would be two files to keep in step, and the one that disagreed would be deciding what gets deleted.
 *
 * <p>So what is here is the two things the module adds: reading a {@link FarmWorldView} — the set plus
 * whether it is loaded and how long it has left — and filtering by who is looking.
 *
 * <h2>Why every change flushes</h2>
 * Because a farm world's schedule decides when three worlds are deleted. A period changed from a day to
 * a week now and back to a day after the next restart deletes somebody's base six days early, and by
 * then nobody remembers which restart it was.
 */
public final class FarmWorldCatalogue {

    private final FarmWorlds farms;

    public FarmWorldCatalogue(FarmWorlds farms) {
        this.farms = farms;
    }

    // ------------------------------------------------------------------------ looking

    /** Every farm world defined on this server, in the order somebody reads a list. */
    public List<FarmWorldView> all() {
        return farms.state().all().stream()
                .map(this::view)
                .sorted(Comparator.comparing(FarmWorldView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Optional<FarmWorldView> byName(String name) {
        return farms.state().byName(name).map(this::view);
    }

    public int count() {
        return farms.state().all().size();
    }

    /** Whether a name is already taken, which is a different refusal from a name being unusable. */
    public boolean exists(String name) {
        return farms.state().byName(name).isPresent();
    }

    /**
     * The farm worlds this player is shown.
     *
     * <p>Not the ones they may enter: a farm world they cannot enter is shown greyed, with the reason.
     * See {@link FarmAccessRule} for why this module hides nothing where the warps module hides
     * everything.
     */
    public List<FarmWorldView> visibleTo(Predicate<String> hasPermission, FarmAccessRule rule) {
        return all().stream()
                .filter(view -> rule.maySee(view.name(), hasPermission))
                .toList();
    }

    /**
     * One farm world, with the two facts that come from outside Core's value type.
     *
     * <p>Both read here, together, so that a screen and a command cannot come to disagree about whether
     * a farm world is loaded — which they could easily do, since one of them asks in the render loop and
     * the other once.
     */
    public FarmWorldView view(WorldSet set) {
        boolean loaded = Bukkit.getWorld(set.overworld()) != null;
        Duration left = farms.state().lastRegenerated(set.name())
                .flatMap(madeAt -> set.until(madeAt, Instant.now()))
                .orElse(null);
        return new FarmWorldView(set, loaded, left);
    }

    /** The set itself, for the one caller that has to hand it back to Core. */
    public Optional<WorldSet> setOf(String name) {
        return farms.state().byName(name);
    }

    /**
     * The overworld of a farm world, when it is loaded.
     *
     * <p>The overworld and never the nether: somebody sent to a farm world is sent to the part they can
     * arrive in safely and walk out of. Arriving in a farm nether means arriving in a random point of
     * the nether, where "somewhere safe to stand" is often a ledge over lava — and where the way home
     * is a portal that has to be built first.
     */
    public Optional<World> overworldOf(String name) {
        return setOf(name).map(set -> Bukkit.getWorld(set.overworld()));
    }

    /**
     * Whether one of a farm world's worlds is loaded right now.
     *
     * <p>By world name rather than by farm world, because the manage page lists all three and a farm world
     * whose overworld is up and whose nether never came back is exactly the state somebody opened that page
     * to see. Asking about the overworld and drawing the answer against every part — which is what this
     * replaced — reported the nether as loaded when it was not there at all.
     */
    public boolean isLoaded(String world) {
        return world != null && Bukkit.getWorld(world) != null;
    }

    /**
     * Everybody standing in any of a farm world's worlds, right now.
     *
     * <p>What the countdown bar is shown to. All three parts rather than the overworld alone: somebody mining
     * quartz in the farm nether loses it in exactly the same second, and they are the one least likely to have
     * been watching chat.
     *
     * <p>Read off the server every time it is asked rather than kept: whoever has walked out since stops seeing
     * the bar, which is what makes a shared bar the right shape for this.
     */
    public List<UUID> playersIn(FarmWorldView farm) {
        if (farm == null) {
            return List.of();
        }
        List<UUID> inside = new ArrayList<>();
        for (String name : farm.worlds()) {
            World world = Bukkit.getWorld(name);
            if (world == null) {
                continue;
            }
            for (Player player : world.getPlayers()) {
                inside.add(player.getUniqueId());
            }
        }
        return List.copyOf(inside);
    }

    /** The names, for the permission nodes and for tab completion. */
    public List<String> names() {
        return farms.state().all().stream().map(WorldSet::name).sorted().toList();
    }

    // ------------------------------------------------------------------------ changing

    /**
     * Defines a farm world and makes its worlds.
     *
     * <p>Both, in that order, and never one without the other: a definition with no worlds is a farm
     * world on the list that nobody can enter, and worlds with no definition are three folders nothing
     * will ever tidy up.
     *
     * @return how many of its worlds are now loaded — three, or fewer when the server refused one
     */
    public int define(WorldSet set) {
        farms.state().define(set);
        List<World> made = farms.ensure(set);
        return made.size();
    }

    /**
     * Forgets a farm world, leaving its worlds exactly where they are.
     *
     * <p>Deliberately not "and delete them". Undefining is how an owner stops a farm world being thrown
     * away on a schedule — usually because they have decided to keep it — and a command that deleted
     * three worlds as a side effect of taking them off a list would be the single worst button in this
     * repository.
     */
    public boolean undefine(String name) {
        return farms.state().undefine(name);
    }

    /**
     * Throws a farm world away and makes it again. Main thread only; stops the server for a moment.
     *
     * <p>Straight through to Core, which owns every part of this: moving people out first, unloading
     * without saving, asking {@code mayDelete} before it removes anything, and refusing to recreate a
     * folder it only half deleted.
     */
    public boolean regenerate(WorldSet set) {
        return farms.regenerate(set);
    }

    /** Writes the definitions and the recorded times. Off the server's threads. */
    public void flush() {
        farms.state().flush();
    }

    /** Whether anything is waiting to be written, for the diagnostic that says so. */
    public boolean isDirty() {
        return farms.state().isDirty();
    }
}
