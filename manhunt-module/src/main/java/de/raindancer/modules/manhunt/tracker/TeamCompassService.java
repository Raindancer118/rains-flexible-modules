package de.raindancer.modules.manhunt.tracker;

import de.raindancer.core.content.items.BoundItems;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.visual.PathTrail;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Aim;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Candidate;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Following;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Point;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The team compass ({@link ManhuntSettings#trackerTeamCompass()}): a second compass, for everybody in
 * a hunt, pointing at their own side — Runners at Runners, Hunters at Hunters.
 *
 * <h2>A recovery compass, and why</h2>
 * A recovery compass points at its holder's last death location, which the server sends to that one
 * client ({@link Player#setLastDeathLocation}) — so it can be aimed per player without the item ever
 * changing, and unlike a plain compass it works in the Nether and the End as well. The spot is
 * rewritten every sweep, riding on the tracking compass' timer, and cleared when the hunt ends.
 *
 * <p>The deciding reuses {@link TrackerCompass}: nearest teammate, or the one picked, followed through
 * the door they took ({@link PortalMemory} remembers everybody's crossings, not only the Runners').
 */
public final class TeamCompassService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String TAG = "team-compass";
    static final String DISTANCE_OWNER = "manhunt-team-compass";

    private final Plugin plugin;
    private final Supplier<Optional<Hunt>> liveHunt;
    private final TrackerCompass compass;
    private final Messages messages;
    private final ActionBars actionBars;
    private final NamespacedKey marker;
    private final Map<UUID, Following> picks = new ConcurrentHashMap<>();
    /** The last spot each holder's needle was set to, so an unchanged one is not sent again. */
    private final Map<UUID, Location> needles = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> showingDistance = ConcurrentHashMap.newKeySet();

    private volatile ManhuntSettings settings;
    private volatile Consumer<Player> pickerScreen;

    public TeamCompassService(Plugin plugin, Supplier<Optional<Hunt>> liveHunt, TrackerCompass compass,
                              Messages messages, ActionBars actionBars, ManhuntSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.liveHunt = Objects.requireNonNull(liveHunt, "liveHunt");
        this.compass = Objects.requireNonNull(compass, "compass");
        this.messages = messages;
        this.actionBars = actionBars;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.marker = new NamespacedKey(plugin, "manhunt-team-compass");
    }

    public void settings(ManhuntSettings fresh) {
        this.settings = fresh;
    }

    private boolean enabled() {
        return settings.trackerTeamCompass();
    }

    // ------------------------------------------------------------------------ a hunt beginning and ending

    /** Everybody still in the hunt gets one — if the owner has the team compass on. */
    public void armFor(Hunt hunt) {
        picks.clear();
        needles.clear();
        if (!enabled()) {
            return;
        }
        for (UUID id : hunt.everybody()) {
            if (hunt.isEliminated(id)) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                give(player);
            }
        }
    }

    /** Every team compass back, every needle's spot cleared — whatever the setting says now. */
    public void disarm(Hunt hunt) {
        for (UUID id : hunt.everybody()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                Scheduling.entity(plugin, player, () -> {
                    takeBack(player);
                    player.setLastDeathLocation(null);
                });
            }
            if (actionBars != null && showingDistance.remove(id)) {
                actionBars.clear(id, DISTANCE_OWNER);
            }
        }
        picks.clear();
        needles.clear();
    }

    /** A death took it — it never drops — so it is handed back on respawn, a tick later. */
    public void giveOnRespawn(Player player) {
        Hunt hunt = liveHunt.get().orElse(null);
        UUID id = player.getUniqueId();
        if (!enabled() || hunt == null || !hunt.everybody().contains(id) || hunt.isEliminated(id)) {
            return;
        }
        needles.remove(id);   // the server has just sent the real death spot; aim again
        Scheduling.entityLater(plugin, player, 1L, () -> give(player));
    }

    // ------------------------------------------------------------------------ the sweep

    /** Re-aims every team compass — called after each tracking-compass sweep, on the same beat. */
    public void sweep(Hunt hunt) {
        if (!enabled()) {
            return;
        }
        Map<UUID, String> names = new java.util.HashMap<>();
        for (UUID id : hunt.everybody()) {
            if (hunt.isEliminated(id)) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            names.putIfAbsent(id, player.getName());
            Aim aim = aimFor(TrackerCompassService.pointOf(player), teammatesOf(hunt, id), picks.get(id));
            Scheduling.entity(plugin, player, () -> apply(player, aim, names));
        }
    }

    /** Everybody on {@code player}'s side still worth pointing at, without them — stable order. */
    List<Candidate> teammatesOf(Hunt hunt, UUID player) {
        java.util.Set<UUID> side = hunt.isRunner(player) ? hunt.livingRunners() : hunt.hunters();
        List<Candidate> mates = new ArrayList<>();
        for (UUID id : side) {
            if (id.equals(player)) {
                continue;
            }
            Player mate = plugin.getServer().getPlayer(id);
            if (mate != null && mate.isOnline() && !mate.isDead()) {
                mates.add(new Candidate(id, TrackerCompassService.pointOf(mate)));
            }
        }
        mates.sort(Comparator.comparing(candidate -> candidate.id().toString()));
        return List.copyOf(mates);
    }

    /** The picked teammate if they are still there, otherwise the nearest one — through their door if need be. */
    Aim aimFor(Point from, List<Candidate> teammates, Following picked) {
        List<Candidate> candidates = teammates;
        if (picked != null && !picked.isNearest()) {
            Optional<Candidate> chosen = teammates.stream()
                    .filter(candidate -> candidate.id().equals(picked.runner())).findFirst();
            if (chosen.isPresent()) {
                candidates = List.of(chosen.get());
            }
        }
        return compass.aim(from, candidates, null);
    }

    private void apply(Player player, Aim aim, Map<UUID, String> names) {
        Optional<Integer> slot = find(player);
        if (slot.isEmpty()) {
            return;
        }
        String name = aim.target() == null ? null : names.getOrDefault(aim.target(), nameOf(aim.target()));
        ItemStack stack = player.getInventory().getItem(slot.get());
        if (stack != null) {
            ItemMeta meta = stack.getItemMeta();
            boolean changed = false;
            if (meta instanceof org.bukkit.inventory.meta.CompassMeta compassMeta
                    && stack.getType() == Material.COMPASS) {
                changed = aimNeedle(player, aim, compassMeta);
            } else {
                aimNeedle(player, aim);
            }
            Component title = line(aim.target() == null
                    ? "<aqua>Team compass"
                    : "<aqua>Team compass <white>→ " + safe(name));
            if (meta != null && !title.equals(meta.displayName())) {
                meta.displayName(title);
                changed = true;
            }
            // Written only when something the player can see changed — an unchanged item is never
            // re-sent, which is what keeps the recovery compass from ever redrawing.
            if (changed) {
                stack.setItemMeta(meta);
                player.getInventory().setItem(slot.get(), stack);
            }
        }
        boolean holding = holding(player);
        showDistance(player, aim, name, holding);
        if (holding && TrailPreference.shows(player, settings)) {
            List<PathTrail.Dot> dots = TrackerCompass.trail(TrackerCompassService.pointOf(player), aim);
            if (!dots.isEmpty()) {
                PathTrail.draw(player, player.getWorld(), dots, new Particle.DustOptions(
                        aim.kind() == Aim.Kind.PORTAL ? Color.PURPLE : Color.AQUA, 1.0f));
            }
        }
    }

    /**
     * Points the holder's recovery compass: their last-death spot, set to the block the aim is in,
     * in their own world. Only sent when it moved; cleared when there is nothing to point at.
     */
    void aimNeedle(Player player, Aim aim) {
        UUID id = player.getUniqueId();
        if (!aim.hasDirection() || aim.at() == null) {
            if (needles.remove(id) != null) {
                player.setLastDeathLocation(null);
            }
            return;
        }
        Location spot = new Location(player.getWorld(), Math.floor(aim.at().x()),
                Math.floor(aim.at().y()), Math.floor(aim.at().z()));
        if (!spot.equals(needles.put(id, spot))) {
            player.setLastDeathLocation(spot);
        }
    }

    /**
     * The plain-compass variant: the spot is kept in the item as an untracked lodestone, because the
     * client's compass target — the only other needle a plain compass has — already belongs to the
     * tracking compass. @return whether the item changed and has to be written back
     */
    boolean aimNeedle(Player player, Aim aim, org.bukkit.inventory.meta.CompassMeta meta) {
        Location spot = !aim.hasDirection() || aim.at() == null ? null
                : new Location(player.getWorld(), Math.floor(aim.at().x()), Math.floor(aim.at().y()),
                        Math.floor(aim.at().z()));
        if (Objects.equals(spot, meta.getLodestone())) {
            return false;
        }
        meta.setLodestoneTracked(false);
        meta.setLodestone(spot);
        return true;
    }

    static Material materialFor(ManhuntSettings.TeamCompassItem item) {
        return item == ManhuntSettings.TeamCompassItem.COMPASS ? Material.COMPASS : Material.RECOVERY_COMPASS;
    }

    private void showDistance(Player player, Aim aim, String name, boolean holding) {
        if (actionBars == null || messages == null) {
            return;
        }
        UUID id = player.getUniqueId();
        if (!holding || !aim.hasDirection()) {
            if (showingDistance.remove(id)) {
                actionBars.clear(id, DISTANCE_OWNER);
            }
            return;
        }
        actionBars.show(id, DISTANCE_OWNER, messages.get("manhunt.team-compass.distance",
                        "teammate", safe(name), "blocks", String.valueOf(Math.round(aim.distance()))),
                Duration.ofMillis(settings.trackerRefreshTicksClamped() * 50L + 1000L), ActionBarPriority.NORMAL);
        showingDistance.add(id);
    }

    // ------------------------------------------------------------------------ picking

    /** Right-click: the next teammate along, and back to the nearest after the last. */
    public void cycle(Player player) {
        Hunt hunt = liveHunt.get().orElse(null);
        if (hunt == null || !hunt.everybody().contains(player.getUniqueId())) {
            return;
        }
        List<Candidate> mates = teammatesOf(hunt, player.getUniqueId());
        Optional<Following> next = TrackerCompass.next(mates, picks.get(player.getUniqueId()));
        if (next.isEmpty()) {
            say(player, "manhunt.team-compass.alone");
            return;
        }
        follow(player, next.get());
    }

    /** A pick from the list — ignored when that player is not (or no longer) on their side. */
    public void pick(Player player, Following choice) {
        Hunt hunt = liveHunt.get().orElse(null);
        if (hunt == null || choice == null || !hunt.everybody().contains(player.getUniqueId())) {
            return;
        }
        if (!choice.isNearest() && teammatesOf(hunt, player.getUniqueId()).stream()
                .noneMatch(candidate -> candidate.id().equals(choice.runner()))) {
            return;
        }
        follow(player, choice);
    }

    private void follow(Player player, Following choice) {
        picks.put(player.getUniqueId(), choice);
        if (choice.isNearest()) {
            say(player, "manhunt.team-compass.now-nearest");
        } else {
            say(player, "manhunt.team-compass.now-following", "teammate", nameOf(choice.runner()));
        }
    }

    /** What the list offers: the nearest teammate, then each teammate. */
    public List<TrackerCompassService.Target> targetsFor(Player player) {
        Hunt hunt = liveHunt.get().orElse(null);
        if (hunt == null || !hunt.everybody().contains(player.getUniqueId())) {
            return List.of();
        }
        Following now = picks.get(player.getUniqueId());
        List<TrackerCompassService.Target> targets = new ArrayList<>();
        targets.add(new TrackerCompassService.Target(Following.NEAREST, "Nearest teammate", true,
                now == null || now.isNearest()));
        for (Candidate mate : teammatesOf(hunt, player.getUniqueId())) {
            targets.add(new TrackerCompassService.Target(Following.of(mate.id()), nameOf(mate.id()), true,
                    now != null && mate.id().equals(now.runner())));
        }
        return targets;
    }

    public void pickerScreen(Consumer<Player> opener) {
        this.pickerScreen = opener;
    }

    /** Sneak + right-click: the list, or a cycle where no screen is wired. */
    public void openPicker(Player player) {
        Consumer<Player> opener = pickerScreen;
        if (opener == null) {
            cycle(player);
            return;
        }
        opener.accept(player);
    }

    public Optional<Following> pickOf(UUID player) {
        return Optional.ofNullable(picks.get(player));
    }

    /** Somebody left the server: forget what they were following. */
    public void forget(UUID player) {
        picks.remove(player);
        needles.remove(player);
        showingDistance.remove(player);
    }

    // ------------------------------------------------------------------------ the item

    public void give(Player player) {
        if (find(player).isPresent()) {
            return;
        }
        ItemStack stack = new ItemStack(materialFor(settings.trackerTeamCompassItem()));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(line("<aqua>Team compass"));
        meta.lore(List.of(line("<gray>Points at your own side."),
                line("<dark_gray>Right-click for the next teammate."),
                line("<dark_gray>Sneak + right-click to pick from a list.")));
        meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, TAG);
        stack.setItemMeta(meta);
        BoundItems.bind(stack);
        for (ItemStack leftover : player.getInventory().addItem(stack).values()) {
            player.getWorld().dropItem(player.getLocation(), leftover);
        }
        say(player, "manhunt.team-compass.given");
    }

    private void takeBack(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isTeamCompass(contents[slot])) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private Optional<Integer> find(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isTeamCompass(contents[slot])) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    private boolean holding(Player player) {
        return isTeamCompass(player.getInventory().getItemInMainHand())
                || isTeamCompass(player.getInventory().getItemInOffHand());
    }

    public boolean isTeamCompass(ItemStack stack) {
        if (stack == null || (stack.getType() != Material.RECOVERY_COMPASS && stack.getType() != Material.COMPASS)
                || !stack.hasItemMeta()) {
            return false;
        }
        return TAG.equals(stack.getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.STRING));
    }

    private String nameOf(UUID id) {
        Player player = id == null ? null : plugin.getServer().getPlayer(id);
        return player != null ? player.getName() : "somebody";
    }

    private void say(Player player, String key, String... placeholders) {
        if (messages != null) {
            messages.send(player, key, (Object[]) placeholders);
        }
    }

    private static Component line(String mini) {
        return MINI.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    private static String safe(String raw) {
        return raw == null ? "somebody" : raw.replace("<", "").replace(">", "");
    }
}
