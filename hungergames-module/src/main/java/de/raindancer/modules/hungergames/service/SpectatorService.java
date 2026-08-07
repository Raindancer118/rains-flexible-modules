package de.raindancer.modules.hungergames.service;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.items.ItemAbilities;
import de.raindancer.core.content.items.ItemAbility;
import de.raindancer.core.content.items.ItemFactory;
import de.raindancer.core.content.items.ItemTrigger;
import de.raindancer.core.content.items.ItemUse;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.store.GameSession;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Eliminated tributes as spectators — vanished, not switched to {@code GameMode.SPECTATOR}. The same
 * mechanism is also what a gamemaster's own "Watch without being seen" mode uses — see
 * {@link #enterVanishSpectator} — since the two want exactly the same guarantees for exactly the same
 * reason: invisible, untouchable, still in whatever game mode they actually hold.
 *
 * <h2>Why not real spectator mode</h2>
 * The Hypixel shape this was asked to match: a tribute who is out stays in survival, invisible to
 * everybody who is still playing, unable to be hurt, unable to hurt anyone, unable to touch a block —
 * and still holding their hotbar, because vanilla spectator mode replaces it with nothing to hold at
 * all. Real spectator mode gives every one of those guarantees at the cost of the one thing this
 * module was asked to keep.
 *
 * <p>{@link de.raindancer.core.moderation.vanish.Vanish} already owns "properly not here" — the
 * tablist, the collision, the fake departure. What it does not own is "cannot mine the cornucopia
 * while nobody can see them", which is why {@link SpectatorProtectionListener} exists beside this
 * class rather than folded into it.
 *
 * <h2>Why the departure is never faked here</h2>
 * An eliminated tribute has not left in any sense a "left the game" line would be honest about — they
 * are still connected, still watching, still on this server. {@link Vanish}'s three-argument
 * {@code vanish} exists for exactly this call.
 */
public final class SpectatorService implements IHungerGamesService, AdminEndpoints.Spectator {

    /** Who this module's items belong to, in Core's registry — see {@link ArenaItemService#PLUGIN}. */
    public static final String PLUGIN = "hungergames";

    /** The item a spectator holds to reach {@link de.raindancer.modules.hungergames.screen.SpectateMenu}. */
    public static final String SPECTATOR_COMPASS = "spectator-compass";

    /** Where {@link #giveTheCompass} puts it — the first hotbar slot, so it is never lost in a search. */
    private static final int COMPASS_SLOT = 0;

    /** Vanilla's own flying speed, and what a returning tribute is put back to. */
    private static final float NORMAL_FLY_SPEED = 0.1F;

    /** Twice vanilla — a spectator watching a round from above should not be crawling across the arena. */
    private static final float SPECTATOR_FLY_SPEED = 0.2F;

    /** Resolving a UUID to an online {@link Player} — Bukkit's job, seamed so this class needs no server. */
    @FunctionalInterface
    public interface OnlinePlayers {
        Optional<Player> byUuid(UUID uuid);
    }

    /** Actually moving a spectator to stand where a target is — Bukkit's teleport, not this class's decision. */
    @FunctionalInterface
    public interface Teleport {
        void go(Player spectator, Player target);
    }

    private final GameSession session;
    private final OnlinePlayers online;
    private final Teleport teleport;
    private final Vanish vanish;
    private final ItemAbilities abilities;
    private final CustomItems items;
    private final ItemFactory itemFactory;
    private final Consumer<Player> openSpectateMenu;

    /**
     * Where a tribute stood the instant they were eliminated — read by {@link SpectatorProtectionListener}
     * to keep a respawn from moving them to the world's spawn point instead.
     */
    private final Map<UUID, Location> lastStandingAt = new ConcurrentHashMap<>();

    /**
     * Everybody currently in this vanish-based spectator state — an eliminated tribute or a gamemaster who
     * picked "Watch without being seen". One flag for both, read by {@link SpectatorProtectionListener}:
     * neither may break, place, use another item, deal or take damage, or go hungry, and the reason is the
     * same for both of them — they are here to watch, not to touch.
     */
    private final Set<UUID> vanishSpectators = ConcurrentHashMap.newKeySet();

    public SpectatorService(GameSession session, OnlinePlayers online, Teleport teleport, Vanish vanish,
                            ItemAbilities abilities, CustomItems items, ItemFactory itemFactory,
                            Consumer<Player> openSpectateMenu) {
        this.session = session;
        this.online = online;
        this.teleport = teleport;
        this.vanish = vanish;
        this.abilities = abilities;
        this.items = items;
        this.itemFactory = itemFactory;
        this.openSpectateMenu = openSpectateMenu;
    }

    /** Nothing here reads a setting — see {@link IHungerGamesService}'s note on implementing this empty. */
    @Override
    public void settings(HungerGamesSettings settings) {
        // intentionally empty
    }

    /**
     * Tells Core about the spectator compass and its ability — see {@code ArenaItemService.register}.
     *
     * <p>A recovery compass, not a plain one — the same fix as {@code AdminHotbarListener.adminItem()},
     * and for the same reason: WorldEdit is a required dependency of this module and binds its navigation
     * wand to a plain compass for anybody holding {@code worldedit.navigation}, which every operator has
     * by default. Core's own {@code CustomItemListener} dispatches at {@code NORMAL}, a priority WorldEdit's
     * own listener can easily run ahead of, so the material is what actually has to change rather than the
     * priority — there is no priority this module could pick for a plain compass that WorldEdit could not
     * still win.
     */
    public void register() {
        items.defineIfAbsent(CustomItem.builder(PLUGIN, SPECTATOR_COMPASS)
                .material(Material.RECOVERY_COMPASS)
                .name("<aqua>Spectate")
                .lore(List.of("<gray>Right-click to watch a living tribute."))
                .glowing(true)
                .ability(SPECTATOR_COMPASS)
                .build());
        abilities.register(ItemAbility.builder(PLUGIN, SPECTATOR_COMPASS)
                .on(ItemTrigger.RIGHT_CLICK)
                .describedAs("Opens the page for watching a living tribute")
                .attempts(this::openTheSpectateMenu)
                .build());
    }

    /** @return whether there was somebody online to show the page to */
    private boolean openTheSpectateMenu(ItemUse use) {
        Optional<Player> player = online.byUuid(use.player());
        player.ifPresent(openSpectateMenu);
        return player.isPresent();
    }

    /**
     * Whether that item is the spectator's own compass — the one thing
     * {@link SpectatorProtectionListener} lets an eliminated tribute still use.
     */
    public boolean isTheSpectatorCompass(org.bukkit.inventory.ItemStack stack) {
        return stack != null && itemFactory.keyOf(stack)
                .map(key -> key.equals(PLUGIN + ":" + SPECTATOR_COMPASS))
                .orElse(false);
    }

    /**
     * Turns a freshly eliminated tribute into a spectator: vanished, flying, holding the compass —
     * and remembered, so a respawn a moment later does not undo the "stay where you were" half of it.
     */
    public void makeSpectator(Player player) {
        lastStandingAt.put(player.getUniqueId(), player.getLocation().clone());
        enterVanishSpectator(player);
    }

    /**
     * The same vanish, flight and compass a fresh spectator gets — without the death bookkeeping, for
     * anybody entering it by their own choice rather than by being eliminated. A gamemaster picking
     * "Watch without being seen" is exactly this: nothing died, there is nowhere they need to be put back
     * to, and {@link #leaveVanishSpectator} is the plain reverse rather than a revive.
     */
    public void enterVanishSpectator(Player player) {
        UUID uuid = player.getUniqueId();
        vanishSpectators.add(uuid);
        vanish.vanish(uuid, player.getAllowFlight(), false);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFlySpeed(SPECTATOR_FLY_SPEED);
        giveTheCompass(player);
    }

    private void giveTheCompass(Player player) {
        items.byKey(PLUGIN + ":" + SPECTATOR_COMPASS)
                .flatMap(itemFactory::create)
                .ifPresent(compass -> player.getInventory().setItem(COMPASS_SLOT, compass));
    }

    /**
     * Undoes {@link #makeSpectator} — a revive, whether typed at the console, clicked in
     * {@code TributesMenu}, or called through the HTTP API. One door, so a fourth way to bring somebody
     * back cannot forget to take the compass out of their hand.
     */
    public void restoreFromElimination(Player player) {
        leaveVanishSpectator(player);
        lastStandingAt.remove(player.getUniqueId());
    }

    /** The plain reverse of {@link #enterVanishSpectator} — reveals, grounds, and takes the compass back. */
    public void leaveVanishSpectator(Player player) {
        UUID uuid = player.getUniqueId();
        vanishSpectators.remove(uuid);
        vanish.reveal(uuid);
        player.setFlying(false);
        player.setFlySpeed(NORMAL_FLY_SPEED);
        takeTheCompassBack(player);
    }

    /** Whether that player is currently in this vanish-based spectator state — see {@link #vanishSpectators}. */
    public boolean isVanishSpectator(UUID uuid) {
        return vanishSpectators.contains(uuid);
    }

    private void takeTheCompassBack(Player player) {
        org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isTheSpectatorCompass(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
            }
        }
    }

    /** Where a spectator stood the moment they were eliminated — for {@link SpectatorProtectionListener}. */
    public Optional<Location> lastKnownLocation(UUID uuid) {
        return Optional.ofNullable(lastStandingAt.get(uuid));
    }

    /**
     * Teleports a spectator to a target — only when the target is a living tribute who is actually online.
     *
     * @return whether the teleport happened
     */
    @Override
    public boolean teleportTo(Player spectator, UUID target) {
        if (!session.participants().isAlive(target)) {
            return false;
        }
        Optional<Player> targetPlayer = online.byUuid(target);
        if (targetPlayer.isEmpty()) {
            return false;
        }
        teleport.go(spectator, targetPlayer.get());
        return true;
    }

    /**
     * The tribute a fresh spectator is pointed at first: a living teammate who is online, if there is one,
     * otherwise any living tribute who is online, otherwise nobody.
     *
     * <p>Pure given {@link GameSession} and {@link OnlinePlayers} — the reason
     * {@code SpectatorServiceTest} can check this preference without a server. Still used by the
     * {@code /hg spectate} page and the spectator compass to choose who a fresh spectator sees first;
     * {@link #makeSpectator} itself no longer teleports anywhere on its own — see its own note.
     */
    public Optional<UUID> firstTarget(UUID spectatorUuid) {
        Optional<UUID> teammate = session.teams().teamOf(spectatorUuid)
                .flatMap(team -> team.members().stream()
                        .filter(member -> !member.equals(spectatorUuid))
                        .filter(session.participants()::isAlive)
                        .filter(member -> online.byUuid(member).isPresent())
                        .findFirst());
        if (teammate.isPresent()) {
            return teammate;
        }
        // Excludes the spectator themselves — real usage always calls this the moment somebody is
        // eliminated, when they are already not "alive" any more and this filter is redundant, but a
        // defensive check that never points a spectator at themselves is one that also survives being
        // called a tick early, or from a test that has not modelled the elimination itself.
        return session.participants().alive().stream()
                .filter(uuid -> !uuid.equals(spectatorUuid))
                .filter(uuid -> online.byUuid(uuid).isPresent())
                .findFirst();
    }

    @Override
    public String describe() {
        return "eliminated tributes as spectators — vanished, flying, and still holding a hotbar";
    }
}
