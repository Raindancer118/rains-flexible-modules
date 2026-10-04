package de.raindancer.modules.speedrun.manhunt.tracker;

import de.raindancer.core.content.items.TaggedItems;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.text.Text;
import de.raindancer.core.world.locate.StructureLocator;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.manhunt.util.Threads;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The Runners' structure compass ({@link ManhuntSettings#runnerStructureCompass()}): every Runner is
 * handed one blank compass per hunt; right-clicking it offers the structures of the dimension they
 * stand in ({@link StructureChoices}, never a stronghold), and the choice points it at the nearest
 * one — found by Core's {@code StructureLocator}, a few cells a tick, so no search ever holds the
 * server. No distance is shown. It is gone when dropped — the item vanishes rather than landing — and
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
     * How long after a search that found nothing the same Runner may search again — a search is
     * cheap for the server now, but "none found" invites a click-storm through every choice.
     */
    static final long SEARCH_COOLDOWN_MILLIS = 10_000;
    /** Longer than any search within vanilla's reach takes at Core's pace. */
    static final long SEARCH_GIVEN_UP_MILLIS = 120_000;

    /**
     * Finds the nearest of any of these structures, over as many ticks as it takes; a seam so
     * everything else is testable without a world.
     */
    @FunctionalInterface
    public interface Locator {
        CompletableFuture<Optional<Location>> nearest(Location origin, List<String> structureKeys);
    }

    /** Where a Runner's compass points. */
    public record Destination(String world, double x, double z, String label) {
    }

    private final Plugin plugin;
    private final Supplier<Optional<Hunt>> liveHunt;
    private final Messages messages;
    private final Supplier<ManhuntSettings> settings;
    private final Locator locator;
    private final TaggedItems items;
    private final LongSupplier clockMillis;
    private final Map<UUID, Long> lastSearch = new ConcurrentHashMap<>();
    private final Map<UUID, Destination> destinations = new ConcurrentHashMap<>();
    /** Runners who have used their one choice this hunt. */
    private final Set<UUID> chosen = ConcurrentHashMap.newKeySet();
    /**
     * Runners whose search is still under way, and since when. An answer for somebody who logged out
     * meanwhile is dropped with them, so a search older than {@link #SEARCH_GIVEN_UP_MILLIS} no longer
     * blocks the next one.
     */
    private final Map<UUID, Long> searching = new ConcurrentHashMap<>();
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
        this.items = TaggedItems.of(plugin, "manhunt-structure-compass").onlyOn(Material.COMPASS);
    }

    /**
     * Core's search: the same one vanilla's {@code /locate} does, cut into small cells and spread over
     * ticks, so even a desert pyramid far away never holds the Runner's region — which Paper's own
     * one-call search did for seconds.
     */
    public static Locator coreSearch(StructureLocator structures) {
        Objects.requireNonNull(structures, "structures");
        return (origin, keys) -> structures.nearest(origin, keys.stream().map(key -> "minecraft:" + key).toList());
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
        searching.clear();
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
     * Points {@code runner}'s blank compass at the nearest {@code choice} — searched over as many
     * ticks as it takes, and written on the Runner's own thread once it is found.
     *
     * @return whether it now points somewhere: false when refused, when nothing of that kind was
     *         found, or when the compass was gone by the time the answer came
     */
    public CompletableFuture<Boolean> choose(Player runner, StructureChoices.Choice choice) {
        Hunt hunt = liveHunt.get().orElse(null);
        UUID id = runner.getUniqueId();
        if (!enabled() || hunt == null || !hunt.isRunner(id) || hunt.isEliminated(id) || chosen.contains(id)
                || items.slotOf(runner.getInventory(), BLANK).isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        long now = clockMillis.getAsLong();
        Long since = searching.get(id);
        if (since != null && now - since < SEARCH_GIVEN_UP_MILLIS) {
            say(runner, "manhunt.structure.still-searching");
            return CompletableFuture.completedFuture(false);
        }
        Long previous = lastSearch.get(id);
        if (previous != null && now - previous < SEARCH_COOLDOWN_MILLIS) {
            say(runner, "manhunt.structure.wait",
                    "seconds", String.valueOf((SEARCH_COOLDOWN_MILLIS - (now - previous) + 999) / 1000));
            return CompletableFuture.completedFuture(false);
        }
        searching.put(id, now);
        say(runner, "manhunt.structure.searching", "structure", choice.label());
        CompletableFuture<Boolean> pointed = new CompletableFuture<>();
        CompletableFuture<Optional<Location>> search;
        try {
            search = locator.nearest(runner.getLocation(), choice.structureKeys());
        } catch (RuntimeException failed) {
            search = CompletableFuture.failedFuture(failed);
        }
        search.whenComplete((nearest, failed) -> Scheduling.entity(plugin, runner, () -> {
            searching.remove(id);
            Optional<Location> found = failed == null && nearest != null ? nearest : Optional.empty();
            pointed.complete(found.isPresent() ? pointAt(runner, choice, found.get())
                    : nothingFound(runner, choice));
        }));
        return pointed;
    }

    private boolean nothingFound(Player runner, StructureChoices.Choice choice) {
        // Only an empty search counts toward the wait: a refusal or a found one never needs it.
        lastSearch.put(runner.getUniqueId(), clockMillis.getAsLong());
        say(runner, "manhunt.structure.none", "structure", choice.label());
        return false;
    }

    /** On the Runner's own thread, with the answer in: the compass written, if they still have it. */
    private boolean pointAt(Player runner, StructureChoices.Choice choice, Location target) {
        UUID id = runner.getUniqueId();
        Hunt hunt = liveHunt.get().orElse(null);
        java.util.OptionalInt slot = items.slotOf(runner.getInventory(), BLANK);
        if (hunt == null || !owes(hunt, id) || chosen.contains(id) || slot.isEmpty()) {
            return false;
        }
        World world = target.getWorld() != null ? target.getWorld() : runner.getWorld();
        ItemStack stack = runner.getInventory().getItem(slot.getAsInt());
        if (stack == null || !(stack.getItemMeta() instanceof CompassMeta meta)) {
            return false;
        }
        meta.setLodestoneTracked(false);
        meta.setLodestone(new Location(world, target.getBlockX(), target.getBlockY(), target.getBlockZ()));
        meta.displayName(Icons.name("<gold>Compass to the nearest "
                + Text.literal(choice.label().toLowerCase(Locale.ROOT))));
        meta.lore(List.of(Icons.loreLine("<gray>Gone once you are within 20 blocks,"),
                Icons.loreLine("<gray>or if you drop it.")));
        items.tag(meta, CHOSEN);
        stack.setItemMeta(meta);
        runner.getInventory().setItem(slot.getAsInt(), stack);
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

    /** Whether they have one — anywhere, the cursor of an open window included. */
    public boolean carries(Player runner) {
        return items.carries(runner, EITHER);
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
        meta.displayName(Icons.name("<gold>Structure compass"));
        meta.lore(List.of(Icons.loreLine("<gray>Right-click to choose what it finds."),
                Icons.loreLine("<dark_gray>Once per hunt. Never a stronghold.")));
        stack.setItemMeta(meta);
        TaggedItems.handTo(runner, items.tag(stack, UNCHOSEN));
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
