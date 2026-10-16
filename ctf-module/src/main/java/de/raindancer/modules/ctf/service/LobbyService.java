package de.raindancer.modules.ctf.service;

import de.raindancer.core.data.loadout.Loadout;
import de.raindancer.core.data.loadout.LoadoutStore;
import de.raindancer.core.data.loadout.Loadouts;
import de.raindancer.core.data.runs.Run;
import de.raindancer.core.data.runs.RunHistory;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.ctf.CtfSettings;
import de.raindancer.modules.ctf.model.Point;
import de.raindancer.modules.ctf.store.LobbyStore;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The lobby: who is in Capture the Flag at all, where they wait, what they hold while waiting, and the two things to do
 * meanwhile — the parkour and the throwing range.
 *
 * <p>Somebody coming in from elsewhere on the server has what they carried kept ({@link LoadoutStore}, profile
 * {@code before-ctf}) and given back, with where they stood, when they leave — also after a crash, at their next join.
 * The store is a row in Core's database, so it is only ever touched off the server thread; while they are here the
 * copy in memory is the one given back.
 */
public final class LobbyService implements ICtfService {

    /** Marks the lobby's own items; the value says which. */
    public static final String ITEM_KEY = "lobby-item";
    public static final String MENU = "menu";
    public static final String VOTE = "vote";
    public static final String CLASS = "class";
    public static final String LEAVE = "leave";
    public static final String PRACTICE_FLAG = "practice-flag";

    private static final String BEFORE = "before-ctf";

    /** A parkour run in progress. */
    public record Running(long startedAt, int checkpoint) {
    }

    private final Plugin plugin;
    private final LobbyStore store;
    private final LoadoutStore loadouts;
    private final Loadouts codec;
    private final RunHistory runs;
    private final LongSupplier clock;
    private final NamespacedKey itemKey;
    private final Set<UUID> inside = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Loadout> kept = new ConcurrentHashMap<>();
    private final Map<UUID, Running> parkour = new ConcurrentHashMap<>();
    private volatile LobbyStore.Lobby lobby;
    private volatile CtfSettings settings;

    public LobbyService(Plugin plugin, LobbyStore store, LoadoutStore loadouts, Loadouts codec, RunHistory runs,
                        LongSupplier clock, CtfSettings settings) {
        this.plugin = plugin;
        this.store = store;
        this.loadouts = loadouts;
        this.codec = codec;
        this.runs = runs;
        this.clock = clock;
        this.settings = settings;
        this.itemKey = new NamespacedKey(plugin, ITEM_KEY);
        this.lobby = store.load();
    }

    @Override
    public void settings(CtfSettings settings) {
        this.settings = settings;
    }

    public LobbyStore.Lobby lobby() {
        return lobby;
    }

    /** Changes the lobby now; the store is written off the server thread. */
    public void change(java.util.function.UnaryOperator<LobbyStore.Lobby> change) {
        LobbyStore.Lobby next = change.apply(lobby);
        lobby = next;
        Scheduling.async(plugin, () -> store.save(next));
    }

    public RunHistory runs() {
        return runs;
    }

    public boolean isInside(UUID player) {
        return inside.contains(player);
    }

    public Set<UUID> everybody() {
        return Set.copyOf(inside);
    }

    /** Where the lobby is: the set spawn, or the main world's spawn. */
    public Location spawn() {
        LobbyStore.Place place = lobby.spawn();
        World world = place == null ? null : Bukkit.getWorld(place.world());
        if (world == null) {
            World main = Bukkit.getWorlds().getFirst();
            return main.getSpawnLocation().add(0.5, 0, 0.5);
        }
        return location(world, place.point());
    }

    public Optional<Location> place(LobbyStore.Place place) {
        World world = place == null ? null : Bukkit.getWorld(place.world());
        return world == null ? Optional.empty() : Optional.of(location(world, place.point()));
    }

    private static Location location(World world, Point point) {
        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    /**
     * Into the lobby. What they carried is kept first unless this is a Capture the Flag server ({@code lobby-on-join})
     * where there is nothing else to come from. Player's own thread.
     */
    public void enter(Player player) {
        boolean fresh = inside.add(player.getUniqueId());
        if (fresh && !settings.lobbyOnJoin() && !kept.containsKey(player.getUniqueId())) {
            Location at = player.getLocation();
            Loadout before = codec.capture(player).withPlace(new Loadout.Place(at.getWorld().getName(), at.getX(),
                    at.getY(), at.getZ(), at.getYaw(), at.getPitch()));
            kept.put(player.getUniqueId(), before);
            UUID id = player.getUniqueId();
            Scheduling.async(plugin, () -> loadouts.save(id, BEFORE, before));
        }
        reset(player);
        player.teleportAsync(spawn());
    }

    /** Healthy, fed, empty-handed but for the lobby's items, in adventure — between rounds, and on entering. */
    public void reset(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.getInventory().clear();
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth == null ? 20 : maxHealth.getValue());
        player.setFoodLevel(20);
        player.setSaturation(10);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.setGlowing(false);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setLevel(0);
        player.setExp(0);
        if (settings.lobbyItems()) {
            giveItems(player);
        }
    }

    public void giveItems(Player player) {
        player.getInventory().setItem(0, item(Material.RED_BANNER, "<gold>Capture the Flag <gray>(right click)", MENU));
        player.getInventory().setItem(2, item(Material.IRON_SWORD, "<aqua>Pick your class <gray>(right click)", CLASS));
        player.getInventory().setItem(4, item(Material.COMPASS, "<yellow>Vote <gray>(right click)", VOTE));
        if (settings.throwRange() && lobby.throwLine() != null) {
            player.getInventory().setItem(6, item(Material.WHITE_BANNER, "<white>Practice flag <gray>(right click: "
                    + "throw)", PRACTICE_FLAG));
        }
        if (!settings.lobbyOnJoin()) {
            player.getInventory().setItem(8, item(Material.RED_BED, "<red>Leave <gray>(right click)", LEAVE));
        }
    }

    private ItemStack item(Material material, String name, String purpose) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.itemName(de.raindancer.core.ui.menu.Icons.name(name));
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, purpose);
        });
        return stack;
    }

    /** Which lobby item this is, if it is one. */
    public Optional<String> purposeOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        return Optional.ofNullable(stack.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING));
    }

    /** Out of Capture the Flag altogether: what they carried before, where they stood before. */
    public void leave(Player player) {
        inside.remove(player.getUniqueId());
        parkour.remove(player.getUniqueId());
        if (settings.lobbyOnJoin()) {
            return;
        }
        restore(player);
    }

    /** Gives back what was kept for them, if anything was. Player's own thread; the disk is let go of off it. */
    public boolean restore(Player player) {
        Loadout before = kept.remove(player.getUniqueId());
        if (before == null) {
            return false;
        }
        player.getInventory().clear();
        player.getActivePotionEffects().stream().map(PotionEffect::getType).forEach(player::removePotionEffect);
        player.setGlowing(false);
        if (!codec.apply(player, before)) {
            // Kept on disk: an item this server cannot read is not thrown away by trying again later.
            return false;
        }
        UUID id = player.getUniqueId();
        Scheduling.async(plugin, () -> loadouts.delete(id, BEFORE));
        Loadout.Place place = before.place();
        World world = place == null ? null : Bukkit.getWorld(place.world());
        if (world != null) {
            player.teleportAsync(new Location(world, place.x(), place.y(), place.z(), place.yaw(), place.pitch()));
        }
        return true;
    }

    /**
     * After a crash: whether something was left kept on disk for somebody joining, read off the server thread and
     * handed back to {@code found} on their own thread.
     */
    public void recover(Player player, Runnable found) {
        UUID id = player.getUniqueId();
        Scheduling.async(plugin, () -> loadouts.load(id, BEFORE).ifPresent(before -> {
            kept.putIfAbsent(id, before);
            Scheduling.entity(plugin, player, found);
        }));
    }

    /** They left the server: what is kept stays on disk for their next join, and goes from memory. */
    public void forget(UUID player) {
        inside.remove(player);
        parkour.remove(player);
        kept.remove(player);
    }

    // ---------------------------------------------------------------- parkour

    public Optional<Running> parkourOf(UUID player) {
        return Optional.ofNullable(parkour.get(player));
    }

    /** A run counts at the finish only with every checkpoint reached on the way. */
    public static boolean mayFinish(Running run, int checkpoints) {
        return run != null && run.checkpoint() >= checkpoints;
    }

    public void startParkour(UUID player) {
        parkour.put(player, new Running(clock.getAsLong(), 0));
    }

    public void checkpoint(UUID player, int index) {
        parkour.computeIfPresent(player, (id, run) -> index > run.checkpoint()
                ? new Running(run.startedAt(), index) : run);
    }

    public void stopParkour(UUID player) {
        parkour.remove(player);
    }

    /** Where somebody falling off goes back to: their last checkpoint, else the start. */
    public Optional<Location> parkourRespawn(UUID player) {
        Running run = parkour.get(player);
        if (run == null) {
            return Optional.empty();
        }
        LobbyStore.Place at = run.checkpoint() > 0 && run.checkpoint() <= lobby.checkpoints().size()
                ? lobby.checkpoints().get(run.checkpoint() - 1) : lobby.parkourStart();
        return place(at);
    }

    /** A finished run, ranked. Off the server thread, as the history writes. */
    public RunHistory.Standing recordParkour(Player player, long startedAt, long tookMillis) {
        Run finished = Run.timed("ctf/parkour", Duration.ofMillis(tookMillis))
                .startedAt(Instant.ofEpochMilli(startedAt))
                .player(player.getUniqueId(), player.getName())
                .build();
        return runs.add(finished);
    }

    /** A practice throw landed this far from the line; the best ones are kept. */
    public RunHistory.Standing thrown(Player player, double blocks) {
        Run throwRun = Run.scored("ctf/throw", Math.round(blocks * 100), false)
                .player(player.getUniqueId(), player.getName()).build();
        return runs.add(throwRun);
    }

    public void runLater(Player player, Runnable task) {
        Scheduling.entity(plugin, player, task);
    }
}
