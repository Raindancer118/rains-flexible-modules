package de.raindancer.modules.manhunt.tracker;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.util.Threads;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.StructureSearchResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static de.raindancer.modules.manhunt.tracker.CompassItems.line;

/**
 * The Runners' structure compass ({@link ManhuntSettings#runnerStructureCompass()}): every Runner is
 * handed one blank compass per hunt; right-clicking it offers the structures of the dimension they
 * stand in ({@link StructureChoices}, never a stronghold), and the choice points it at the nearest
 * one. No distance is shown. It is gone when dropped — the item vanishes rather than landing — and
 * once they are within {@link StructureChoices#REACHED_WITHIN} blocks.
 *
 * <p>The needle is an untracked lodestone in the item: the target never moves, so the item is
 * written once, and it works in every dimension.
 */
public final class StructureCompassService {

    private static final String UNCHOSEN = "unchosen";
    private static final String CHOSEN = "chosen";
    private static final Predicate<String> BLANK = UNCHOSEN::equals;
    private static final Predicate<String> EITHER = state -> UNCHOSEN.equals(state) || CHOSEN.equals(state);
    /** How far the search reaches, in chunks — a mansion can be a long way off, and that is the Runner's call. */
    private static final int SEARCH_RADIUS_CHUNKS = 100;

    /** Finds the nearest of any of these structures; a seam so everything else is testable. */
    @FunctionalInterface
    public interface Locator {
        Optional<Location> nearest(Location origin, List<String> structureKeys);
    }

    /** Where a Runner's compass points. */
    public record Destination(String world, double x, double z, String label) {
    }

    private final Plugin plugin;
    private final Supplier<Optional<Hunt>> liveHunt;
    private final Messages messages;
    private final Supplier<ManhuntSettings> settings;
    private final Locator locator;
    private final CompassItems items;
    private final Map<UUID, Destination> destinations = new ConcurrentHashMap<>();
    /** Runners who have used their one choice this hunt. */
    private final Set<UUID> chosen = ConcurrentHashMap.newKeySet();
    private volatile Consumer<Player> chooserScreen;

    public StructureCompassService(Plugin plugin, Supplier<Optional<Hunt>> liveHunt, Messages messages,
                                   Supplier<ManhuntSettings> settings, Locator locator) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.liveHunt = Objects.requireNonNull(liveHunt, "liveHunt");
        this.messages = messages;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.locator = Objects.requireNonNull(locator, "locator");
        this.items = new CompassItems(plugin, "manhunt-structure-compass", Material.COMPASS);
    }

    /** The real search: every variant, the nearest of all. Runs on the caller's (the player's) thread. */
    public static Locator worldSearch() {
        return (origin, keys) -> {
            World world = origin.getWorld();
            if (world == null) {
                return Optional.empty();
            }
            Location best = null;
            double bestDistance = Double.MAX_VALUE;
            for (String key : keys) {
                Structure structure = Registry.STRUCTURE.get(NamespacedKey.minecraft(key));
                if (structure == null) {
                    continue;
                }
                StructureSearchResult result = world.locateNearestStructure(origin, structure,
                        SEARCH_RADIUS_CHUNKS, false);
                if (result != null && result.getLocation().distanceSquared(origin) < bestDistance) {
                    best = result.getLocation();
                    bestDistance = best.distanceSquared(origin);
                }
            }
            return Optional.ofNullable(best);
        };
    }

    public void chooserScreen(Consumer<Player> opener) {
        this.chooserScreen = opener;
    }

    private boolean enabled() {
        return settings.get().runnerStructureCompass();
    }

    /** Whether {@code player} should be carrying one: a Runner still in it, with the setting on. */
    boolean owes(Hunt hunt, UUID player) {
        return enabled() && hunt.isRunner(player) && !hunt.isEliminated(player);
    }

    // ------------------------------------------------------------------------ a hunt beginning and ending

    /** A new hunt: every Runner has their one choice again. Handing out is {@link HuntCompasses}'. */
    public void arm() {
        destinations.clear();
        chosen.clear();
    }

    public void disarm(Hunt hunt) {
        for (UUID id : hunt.everybody()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                Threads.entity(plugin, player, () -> takeBack(player));
            }
        }
        destinations.clear();
        chosen.clear();
    }

    /**
     * Brings one player's structure compass in line with the side they are on now — on their own
     * thread. A Runner who has not used their choice yet gets one; anybody else has theirs taken,
     * and where it pointed is forgotten.
     */
    public void fit(Hunt hunt, Player player) {
        UUID id = player.getUniqueId();
        if (owes(hunt, id)) {
            if (!chosen.contains(id)) {
                give(player);
            }
            return;
        }
        takeBack(player);
        destinations.remove(id);
    }

    /** Takes the compass off anybody who has got within reach of where it points. */
    public void sweep(Hunt hunt) {
        for (Map.Entry<UUID, Destination> entry : Map.copyOf(destinations).entrySet()) {
            Player runner = plugin.getServer().getPlayer(entry.getKey());
            if (runner == null || !runner.isOnline()) {
                continue;
            }
            Destination goal = entry.getValue();
            Location at = runner.getLocation();
            String world = at.getWorld() == null ? null : at.getWorld().getName();
            if (StructureChoices.reached(world, at.getX(), at.getZ(), goal.world(), goal.x(), goal.z())) {
                destinations.remove(entry.getKey());
                Threads.entity(plugin, runner, () -> takeBack(runner));
                say(runner, "manhunt.structure.reached", "structure", goal.label());
            }
        }
    }

    // ------------------------------------------------------------------------ choosing

    /** A right-click on the blank compass: the list of what it can point at, here. */
    public void openChooser(Player runner) {
        Consumer<Player> opener = chooserScreen;
        if (opener != null) {
            opener.accept(runner);
        }
    }

    /**
     * Points {@code runner}'s blank compass at the nearest {@code choice}.
     *
     * @return whether it now points somewhere — false when refused, or nothing of that kind was found
     */
    public boolean choose(Player runner, StructureChoices.Choice choice) {
        Hunt hunt = liveHunt.get().orElse(null);
        UUID id = runner.getUniqueId();
        if (!enabled() || hunt == null || !hunt.isRunner(id) || hunt.isEliminated(id) || chosen.contains(id)) {
            return false;
        }
        int slot = items.slotOf(runner, BLANK);
        if (slot < 0) {
            return false;
        }
        Optional<Location> nearest = locator.nearest(runner.getLocation(), choice.structureKeys());
        if (nearest.isEmpty()) {
            say(runner, "manhunt.structure.none", "structure", choice.label());
            return false;
        }
        Location target = nearest.get();
        World world = target.getWorld() != null ? target.getWorld() : runner.getWorld();
        ItemStack stack = runner.getInventory().getItem(slot);
        if (stack != null && stack.getItemMeta() instanceof CompassMeta meta) {
            meta.setLodestoneTracked(false);
            meta.setLodestone(new Location(world, target.getBlockX(), target.getBlockY(), target.getBlockZ()));
            meta.displayName(line("<gold>Compass to the nearest " + choice.label().toLowerCase(Locale.ROOT)));
            meta.lore(List.of(line("<gray>Gone once you are within 20 blocks,"), line("<gray>or if you drop it.")));
            items.tag(meta, CHOSEN);
            stack.setItemMeta(meta);
            runner.getInventory().setItem(slot, stack);
        }
        chosen.add(id);
        destinations.put(id, new Destination(world.getName(), target.getX(), target.getZ(), choice.label()));
        say(runner, "manhunt.structure.chosen", "structure", choice.label());
        return true;
    }

    public Optional<Destination> destinationOf(UUID runner) {
        return Optional.ofNullable(destinations.get(runner));
    }

    // ------------------------------------------------------------------------ the item

    /** Dropped, it is gone: the item entity is removed instead of landing, and the choice with it. */
    public void onDrop(PlayerDropItemEvent event) {
        if (!isStructureCompass(event.getItemDrop().getItemStack())) {
            return;
        }
        event.getItemDrop().remove();
        destinations.remove(event.getPlayer().getUniqueId());
        chosen.add(event.getPlayer().getUniqueId());
        say(event.getPlayer(), "manhunt.structure.dropped");
    }

    public boolean enabledNow() {
        return enabled();
    }

    public boolean carries(Player runner) {
        return items.slotOf(runner, EITHER) >= 0;
    }

    /**
     * A fresh blank compass from an admin, after the first was dropped or used up — so the
     * once-per-hunt choice is handed back with it, or the new compass could never be pointed anywhere.
     */
    public void giveAgain(Player runner) {
        if (carries(runner)) {
            return;
        }
        chosen.remove(runner.getUniqueId());
        destinations.remove(runner.getUniqueId());
        give(runner);
    }

    public void give(Player runner) {
        if (carries(runner)) {
            return;
        }
        ItemStack stack = new ItemStack(Material.COMPASS);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(line("<gold>Structure compass"));
        meta.lore(List.of(line("<gray>Right-click to choose what it finds."),
                line("<dark_gray>Once per hunt. Never a stronghold.")));
        items.tag(meta, UNCHOSEN);
        stack.setItemMeta(meta);
        CompassItems.handTo(runner, stack);
        say(runner, "manhunt.structure.given");
    }

    /** Somebody who left the hunt: the compass back, its destination forgotten. */
    public void takeFrom(Player player) {
        Threads.entity(plugin, player, () -> takeBack(player));
        destinations.remove(player.getUniqueId());
    }

    /** Every structure compass off {@code player} — on their own thread. */
    public void takeBack(Player player) {
        items.removeAll(player, EITHER);
    }

    public boolean isStructureCompass(ItemStack stack) {
        return items.is(stack, EITHER);
    }

    public boolean isBlank(ItemStack stack) {
        return items.is(stack, BLANK);
    }

    private void say(Player player, String key, String... placeholders) {
        if (messages != null) {
            messages.send(player, key, (Object[]) placeholders);
        }
    }
}
