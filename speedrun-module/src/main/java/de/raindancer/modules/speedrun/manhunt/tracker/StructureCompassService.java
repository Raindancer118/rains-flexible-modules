package de.raindancer.modules.speedrun.manhunt.tracker;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.manhunt.util.Threads;
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

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static de.raindancer.modules.speedrun.manhunt.tracker.CompassItems.line;

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
    /**
     * How far the search reaches, in chunks — vanilla's own {@code /locate}. Not a bound on the time:
     * measured on 26.2, radius 50 took as long as 100 (one desert pyramid 8.4 s at 50), because the
     * search widens until it finds one whatever the radius says.
     */
    private static final int SEARCH_RADIUS_CHUNKS = 100;

    /**
     * How long after a search the same Runner may search again. Measured on 26.2: a single search
     * holds the Runner's region from a few ms to several seconds, and "none found" invites a retry.
     */
    static final long SEARCH_COOLDOWN_MILLIS = 10_000;

    /** How one choice is searched — always one pass where the server can do it in one. */
    enum SearchPlan {
        /** A single structure. */
        ONE,
        /** Every variant shares one structure type of its own (shipwrecks, ruined portals): one pass by type. */
        BY_TYPE,
        /** Villages: their type, jigsaw, is shared with half the game, but the server knows "village". */
        VILLAGES,
        /** Nothing in common: one search per variant — none of the offered choices is this. */
        EACH
    }

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
    private final LongSupplier clockMillis;
    private final Map<UUID, Long> lastSearch = new ConcurrentHashMap<>();
    private final Map<UUID, Destination> destinations = new ConcurrentHashMap<>();
    /** Runners who have used their one choice this hunt. */
    private final Set<UUID> chosen = ConcurrentHashMap.newKeySet();
    private volatile Consumer<Player> chooserScreen;

    public StructureCompassService(Plugin plugin, Supplier<Optional<Hunt>> liveHunt, Messages messages,
                                   Supplier<ManhuntSettings> settings, Locator locator) {
        this(plugin, liveHunt, messages, settings, locator, System::currentTimeMillis);
    }

    StructureCompassService(Plugin plugin, Supplier<Optional<Hunt>> liveHunt, Messages messages,
                            Supplier<ManhuntSettings> settings, Locator locator, LongSupplier clockMillis) {
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.liveHunt = Objects.requireNonNull(liveHunt, "liveHunt");
        this.messages = messages;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.locator = Objects.requireNonNull(locator, "locator");
        this.items = new CompassItems(plugin, "manhunt-structure-compass", Material.COMPASS);
    }

    /**
     * The real search, on the caller's (the Runner's) thread — Paper has no asynchronous one. Every
     * variant of a choice in one pass: measured on 26.2, the five villages one after another took
     * 3.3 s where one pass over all five took 0.6 s.
     */
    @SuppressWarnings("deprecation")   // the legacy VILLAGE type is the API's only one-pass village search
    public static Locator worldSearch() {
        return (origin, keys) -> {
            World world = origin.getWorld();
            if (world == null) {
                return Optional.empty();
            }
            List<Structure> structures = keys.stream()
                    .map(key -> Registry.STRUCTURE.get(NamespacedKey.minecraft(key)))
                    .filter(Objects::nonNull).toList();
            if (structures.isEmpty()) {
                return Optional.empty();
            }
            SearchPlan plan = plan(keys, key -> {
                Structure structure = Registry.STRUCTURE.get(NamespacedKey.minecraft(key));
                return structure == null ? key : structure.getStructureType().key().value();
            });
            return switch (plan) {
                case ONE -> found(world.locateNearestStructure(origin, structures.getFirst(), SEARCH_RADIUS_CHUNKS, false));
                case BY_TYPE -> found(world.locateNearestStructure(origin,
                        structures.getFirst().getStructureType(), SEARCH_RADIUS_CHUNKS, false));
                case VILLAGES -> Optional.ofNullable(world.locateNearestStructure(origin,
                        org.bukkit.StructureType.VILLAGE, SEARCH_RADIUS_CHUNKS, false));
                case EACH -> structures.stream()
                        .map(structure -> world.locateNearestStructure(origin, structure, SEARCH_RADIUS_CHUNKS, false))
                        .filter(Objects::nonNull).map(StructureSearchResult::getLocation)
                        .min(Comparator.comparingDouble(at -> at.distanceSquared(origin)));
            };
        };
    }

    private static Optional<Location> found(StructureSearchResult result) {
        return result == null ? Optional.empty() : Optional.of(result.getLocation());
    }

    /** @param typeOf each structure id's own structure type id */
    static SearchPlan plan(List<String> keys, Function<String, String> typeOf) {
        if (keys.size() == 1) {
            return SearchPlan.ONE;
        }
        if (keys.stream().allMatch(key -> key.startsWith("village_"))) {
            return SearchPlan.VILLAGES;
        }
        Set<String> types = keys.stream().map(typeOf).collect(Collectors.toSet());
        if (types.size() == 1 && !types.contains("jigsaw")) {
            return SearchPlan.BY_TYPE;
        }
        return SearchPlan.EACH;
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
        lastSearch.clear();
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
        long now = clockMillis.getAsLong();
        Long previous = lastSearch.get(id);
        if (previous != null && now - previous < SEARCH_COOLDOWN_MILLIS) {
            say(runner, "manhunt.structure.wait",
                    "seconds", String.valueOf((SEARCH_COOLDOWN_MILLIS - (now - previous) + 999) / 1000));
            return false;
        }
        lastSearch.put(id, now);
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
