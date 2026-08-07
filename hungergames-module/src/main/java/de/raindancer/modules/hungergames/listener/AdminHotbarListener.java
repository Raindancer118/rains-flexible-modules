package de.raindancer.modules.hungergames.listener;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The three items a gamemaster's hotbar carries, so a tournament is run by clicking.
 *
 * <h2>Why this exists at all</h2>
 * This module has five commands and twenty-four screens, on purpose: a gamemaster with forty people waiting
 * should be clicking rather than spelling a subcommand. Which leaves one question the design had no answer
 * for — how do they open the first page? Typing {@code /hg admin} is exactly the thing everything else was
 * arranged to avoid.
 *
 * <p>The plugin this is ported from answered it and the answer was left behind: three items in the hotbar,
 * given on join and on a game-mode change. That was reported as missing by an audit and confirmed by a
 * gamemaster running {@code /init} on the live server and finding an empty hotbar.
 *
 * <h2>Why the items are recognised by their tag and never by their material</h2>
 * A compass is a compass. A gamemaster who happens to be carrying one — and every one of them is, because a
 * compass is how you find your way back to a cornucopia — would otherwise open the admin suite by
 * right-clicking it, and their real compass would be swallowed by the tidy-up that takes these away again.
 * So every one is stamped in its persistent data, and only a stamped item counts.
 *
 * <h2>Why they are taken away rather than left</h2>
 * A tribute must not be holding one. The items are handed out to whoever holds the gamemaster permission and
 * removed from everybody else on every join — because staff get promoted and demoted between rounds, and an
 * item that was correct last week is a page a tribute can open during a fight.
 */
public final class AdminHotbarListener implements IHungerGamesListener {

    /** Where each item sits. The last three slots, so nothing displaces what somebody is actually holding. */
    public static final int SLOT_START = 6;
    public static final int SLOT_QUICK = 7;
    public static final int SLOT_ADMIN = 8;

    /** What each item is, in its own persistent data. Never the material — see the class note. */
    public static final String ADMIN = "admin";
    public static final String QUICK = "quick";
    public static final String START = "start";

    /** Which page an item opens. */
    public interface Pages {

        void admin(Player viewer);

        /** The round-control page: init, the launch sequence, start. */
        void control(Player viewer);
    }

    /** Starting the countdown, which the green item does directly rather than through a page. */
    @FunctionalInterface
    public interface StartTheRound {
        void now(Player who);
    }

    private final NamespacedKey key;
    private final Pages pages;
    private final StartTheRound start;
    private final java.util.function.Supplier<GamePhase> phase;

    private volatile HungerGamesSettings settings;

    public AdminHotbarListener(Plugin plugin, Pages pages, StartTheRound start,
                               java.util.function.Supplier<GamePhase> phase,
                               HungerGamesSettings settings) {
        this.key = new NamespacedKey(plugin, "hungergames-hotbar");
        this.pages = pages;
        this.start = start;
        this.phase = phase;
        this.settings = settings;
    }

    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered. Whether somebody should have these is read from their permissions at the
        // moment they join, which is what makes a promotion between rounds take effect without bookkeeping.
    }

    /**
     * Hands them out, or takes them away.
     *
     * <p>At {@link EventPriority#MONITOR}, after anything else that wants a say in somebody's inventory on
     * join — a kit plugin, a restore-on-death handler. Going first would mean writing into an inventory that
     * is about to be replaced.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    /**
     * Gives a gamemaster their items and takes them off anybody else.
     *
     * <p>Public so the moment somebody is promoted — or turns gamemaster mode on — can be reflected without
     * waiting for them to rejoin; also the reason the module's own phase watcher calls this again for
     * every online player on every phase change, rather than only at join.
     *
     * <h2>Why none of the three survives into {@code RUNNING}</h2>
     * A gamemaster who is also playing must not be carrying the tournament's own controls once they are a
     * tribute — an admin suite reachable mid-fight is not a small convenience, it is a way to end the round
     * they are losing. So all three are withheld the moment the round actually starts, the same moment this
     * module clears every tribute's inventory for a clean start — see
     * {@code HungerGamesWiring.phaseWatcher()}. They come back once the round is {@code FINISHED} or reset,
     * because running the next one is exactly the gamemaster work these items exist for.
     */
    public void apply(Player player) {
        clear(player);
        if (!PermissionNodes.mayOpenTheAdminSuite(player) || phase.get() == GamePhase.RUNNING) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(SLOT_ADMIN, tagged(adminItem(), ADMIN));
        inventory.setItem(SLOT_QUICK, tagged(quickItem(), QUICK));
        // The green one only while a round can actually be started. A button that starts nothing is worse
        // than a missing one: it is pressed, and then explained.
        if (phase.get() == GamePhase.READY) {
            inventory.setItem(SLOT_START, tagged(startItem(), START));
        }
    }

    /**
     * Takes every one of ours out of an inventory.
     *
     * <p>By tag rather than by slot, because somebody will have moved one — and clearing by slot would take
     * whatever they put there instead.
     */
    public void clear(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (tagOf(inventory.getItem(slot)) != null) {
                inventory.setItem(slot, null);
            }
        }
    }

    /**
     * Right-clicking one opens what it says.
     *
     * <h2>Why {@code LOWEST}</h2>
     * WorldEdit is a required dependency of this module — see {@code HungerGamesModule}'s own note on why —
     * and it binds its navigation wand to a plain compass for anybody holding
     * {@code worldedit.navigation}, which every operator has by default. At any later priority WorldEdit's
     * own listener has already teleported the holder before this ever sees the click; cancelling here,
     * first, is what stops that race rather than merely making it rarer. See also {@link #adminItem()} —
     * a recovery compass rather than a plain one, so the two tools are not fighting over the same material
     * even when something runs before {@code LOWEST} some day.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        String tag = tagOf(event.getItem());
        if (tag == null) {
            return;
        }
        // Cancelled whatever the action was: these must not also place a block, eat, or till a field. A
        // left-click is included so a misclick does nothing rather than mining the floor of the lobby.
        event.setCancelled(true);
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player who = event.getPlayer();
        if (!PermissionNodes.mayOpenTheAdminSuite(who)) {
            // Somebody kept an item across a demotion, or picked one up off the floor. Taken away rather
            // than merely refused, so it does not sit there being tried again.
            clear(who);
            return;
        }
        switch (tag) {
            case ADMIN -> pages.admin(who);
            case QUICK -> pages.control(who);
            case START -> start.now(who);
            default -> { }
        }
    }

    /**
     * These are never dropped.
     *
     * <p>Otherwise a tribute picks one up off the floor of the lobby and has the admin suite. The interact
     * handler refuses them anyway, so this is the second of two locks on the same door — and it is the one
     * that stops the item existing where it should not.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (tagOf(event.getItemDrop().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }

    // ==================== the items ====================

    /**
     * A recovery compass, not a plain one — see {@link #onUse}'s note. WorldEdit's navigation wand is a
     * plain compass, and an operator holding one right-clicks WorldEdit's own teleport-to-target before this
     * item is ever recognised as ours.
     */
    private ItemStack adminItem() {
        return Icons.of(Material.RECOVERY_COMPASS, "<aqua>The tournament",
                List.of("<gray>Right-click: everything.", "<dark_gray>The same page as /hg admin."));
    }

    private ItemStack quickItem() {
        return Icons.of(Material.NETHER_STAR, "<gold>Run the round",
                List.of("<gray>Right-click: the run-up.",
                        "<dark_gray>Build the arena · launch · start."));
    }

    private ItemStack startItem() {
        return Icons.of(Material.LIME_CONCRETE, "<green>Start the Games",
                List.of("<gray>Right-click: countdown, then release.",
                        "<dark_gray>The same as /start."));
    }

    /** Stamps an item so it is recognised by what it is rather than by what it looks like. */
    private ItemStack tagged(ItemStack item, String what) {
        item.editMeta(meta -> meta.getPersistentDataContainer()
                .set(key, PersistentDataType.STRING, what));
        return item;
    }

    /** What that item is, or {@code null} for anything that is not one of ours. */
    private String tagOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(key, PersistentDataType.STRING);
    }

    @Override
    public String describe() {
        return "the three items a gamemaster runs a tournament from";
    }
}
