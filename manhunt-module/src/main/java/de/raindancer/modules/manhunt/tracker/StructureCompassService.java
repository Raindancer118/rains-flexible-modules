package de.raindancer.modules.manhunt.tracker;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.Hunt;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.StructureSearchResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String UNCHOSEN = "unchosen";
    private static final String CHOSEN = "chosen";
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
    private final NamespacedKey marker;
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
        this.marker = new NamespacedKey(plugin, "manhunt-structure-compass");
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

    // ------------------------------------------------------------------------ a hunt beginning and ending

    public void armFor(Hunt hunt) {
        destinations.clear();
        chosen.clear();
        if (!enabled()) {
            return;
        }
        for (UUID id : hunt.livingRunners()) {
            Player runner = plugin.getServer().getPlayer(id);
            if (runner != null) {
                give(runner);
            }
        }
    }

    public void disarm(Hunt hunt) {
        for (UUID id : hunt.everybody()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                Scheduling.entity(plugin, player, () -> takeBack(player));
            }
        }
        destinations.clear();
        chosen.clear();
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
                Scheduling.entity(plugin, runner, () -> takeBack(runner));
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
        Optional<Integer> slot = find(runner, UNCHOSEN);
        if (slot.isEmpty()) {
            return false;
        }
        Optional<Location> nearest = locator.nearest(runner.getLocation(), choice.structureKeys());
        if (nearest.isEmpty()) {
            say(runner, "manhunt.structure.none", "structure", choice.label());
            return false;
        }
        Location target = nearest.get();
        World world = target.getWorld() != null ? target.getWorld() : runner.getWorld();
        ItemStack stack = runner.getInventory().getItem(slot.get());
        if (stack != null && stack.getItemMeta() instanceof CompassMeta meta) {
            meta.setLodestoneTracked(false);
            meta.setLodestone(new Location(world, target.getBlockX(), target.getBlockY(), target.getBlockZ()));
            meta.displayName(line("<gold>Compass to the nearest " + choice.label().toLowerCase(java.util.Locale.ROOT)));
            meta.lore(List.of(line("<gray>Gone once you are within 20 blocks,"), line("<gray>or if you drop it.")));
            meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, CHOSEN);
            stack.setItemMeta(meta);
            runner.getInventory().setItem(slot.get(), stack);
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

    public void give(Player runner) {
        if (find(runner, UNCHOSEN).isPresent() || find(runner, CHOSEN).isPresent()) {
            return;
        }
        ItemStack stack = new ItemStack(Material.COMPASS);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(line("<gold>Structure compass"));
        meta.lore(List.of(line("<gray>Right-click to choose what it finds."),
                line("<dark_gray>Once per hunt. Never a stronghold.")));
        meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, UNCHOSEN);
        stack.setItemMeta(meta);
        for (ItemStack leftover : runner.getInventory().addItem(stack).values()) {
            runner.getWorld().dropItem(runner.getLocation(), leftover);
        }
        say(runner, "manhunt.structure.given");
    }

    private void takeBack(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isStructureCompass(contents[slot])) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private Optional<Integer> find(Player player, String state) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (state.equals(stateOf(contents[slot]))) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    private String stateOf(ItemStack stack) {
        if (stack == null || stack.getType() != Material.COMPASS || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.STRING);
    }

    public boolean isStructureCompass(ItemStack stack) {
        String state = stateOf(stack);
        return UNCHOSEN.equals(state) || CHOSEN.equals(state);
    }

    public boolean isBlank(ItemStack stack) {
        return UNCHOSEN.equals(stateOf(stack));
    }

    private void say(Player player, String key, String... placeholders) {
        if (messages != null) {
            messages.send(player, key, (Object[]) placeholders);
        }
    }

    private static Component line(String mini) {
        return MINI.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }
}
