package de.raindancer.modules.arena.service;

import de.raindancer.core.platform.proxy.ProxyTransfer;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.arena.ArenaSettings;
import de.raindancer.modules.arena.model.Box;
import de.raindancer.modules.arena.model.Layout;
import de.raindancer.modules.arena.model.Pit;
import de.raindancer.modules.arena.model.Place;
import de.raindancer.modules.arena.store.LayoutBook;
import de.raindancer.modules.arena.util.Places;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;

/**
 * Arriving, leaving, and what an admin sets up.
 *
 * <p>This server's inventories are its own: whatever somebody owns stays on the survival server, untouched, and
 * nothing here ever goes back. So arriving is simply being put in the hub with empty hands, and leaving is the proxy
 * sending them back.
 *
 * <p>Every admin action takes its places as values, so the same command works for somebody standing there and from
 * the console with coordinates.
 */
public final class ArenaService implements IArenaService {

    private final Plugin plugin;
    private final Server server;
    private final Messages messages;
    private final Effects effects;
    private final ArenaWorldService worlds;
    private final LayoutBook layout;
    private volatile ArenaSettings settings;
    private volatile java.util.function.Consumer<java.util.UUID> returning = player -> {
    };
    private final HoldBook held = new HoldBook();
    private volatile java.util.function.Predicate<java.util.UUID> fighting = player -> false;

    public ArenaService(Plugin plugin, Server server, Messages messages, Effects effects, ArenaWorldService worlds,
                        LayoutBook layout, ArenaSettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.messages = messages;
        this.effects = effects;
        this.worlds = worlds;
        this.layout = layout;
        this.settings = settings;
    }

    @Override
    public void settings(ArenaSettings settings) {
        this.settings = settings;
    }

    /** Told before somebody is sent back, so the survival server can welcome them back rather than announce a join. */
    public void returning(java.util.function.Consumer<java.util.UUID> announce) {
        this.returning = announce;
    }

    /** Who is in a running fight — not sent back in the middle of it. */
    public void fighting(java.util.function.Predicate<java.util.UUID> inFight) {
        this.fighting = inFight;
    }

    // ── survival restarting ────────────────────────────────────────────────────────────────────────

    /** Somebody waits here while survival restarts for an update, to be sent back when it is up. */
    public void hold(Player player) {
        held.hold(player.getUniqueId());
        messages.send(player, "arena.update.held");
    }

    public boolean isHeld(java.util.UUID player) {
        return held.isHeld(player);
    }

    public void release(java.util.UUID player) {
        held.release(player);
    }

    /**
     * Survival is back: everybody held goes back to it, a moment apart so the proxy is not asked for all of them at
     * once. Somebody in a fight stays held and goes when the fight is over.
     *
     * @return how many were sent
     */
    public int returnHeld() {
        List<java.util.UUID> leaving = held.takeAllBut(fighting);
        int sent = 0;
        for (java.util.UUID id : leaving) {
            Player player = server.getPlayer(id);
            if (player == null) {
                continue;
            }
            long delay = 5L + 5L * sent++;
            de.raindancer.core.platform.util.Scheduling.entityLater(plugin, player, delay, () -> {
                messages.send(player, "arena.update.back");
                returning.accept(player.getUniqueId());
                ProxyTransfer.send(plugin, player, settings.lobbyOrDefault());
            });
        }
        return sent;
    }

    /** After a fight: somebody held through it goes back now. */
    public void fightOver(java.util.UUID player) {
        if (held.isHeld(player) && !fighting.test(player)) {
            held.release(player);
            Player online = server.getPlayer(player);
            if (online != null) {
                messages.send(online, "arena.update.back");
                returning.accept(player);
                ProxyTransfer.send(plugin, online, settings.lobbyOrDefault());
            }
        }
    }

    public ArenaSettings config() {
        return settings;
    }

    public Layout layout() {
        return layout.current();
    }

    public boolean isArenaWorld(String world) {
        return layout.current().isArenaWorld(world);
    }

    public Optional<Location> hub() {
        return layout.current().hub().flatMap(place -> Places.location(server, place));
    }

    /** Somebody has come over from the survival server. On their own thread. */
    public void arrived(Player player) {
        HubState.apply(player, settings);
        hub().ifPresentOrElse(hub -> player.teleportAsync(hub).thenAccept(moved -> {
            effects.play(player.getUniqueId(), Cues.ENTER);
            messages.send(player, "arena.welcome");
        }), () -> messages.send(player, "arena.not-set-up"));
    }

    /** Back to the survival server, through the proxy. */
    public void leave(Player player) {
        messages.send(player, "arena.leaving", "server", settings.lobbyOrDefault());
        effects.play(player.getUniqueId(), Cues.LEAVE);
        returning.accept(player.getUniqueId());
        ProxyTransfer.send(plugin, player, settings.lobbyOrDefault());
    }

    /** Back to the hub — somebody beaten, fallen off a map, or tired of the fight. */
    public void toHub(Player player) {
        hub().ifPresentOrElse(player::teleportAsync, () -> player.teleportAsync(player.getWorld().getSpawnLocation()));
    }

    // ── setting up ─────────────────────────────────────────────────────────────────────────────────

    public void build(CommandSender admin, String world, String mapFile) {
        if (server.getWorld(world) != null && !isArenaWorld(world)) {
            messages.send(admin, "arena.admin.not-an-arena-world", "world", world);
            return;
        }
        messages.send(admin, "arena.admin.building", "world", world, "map", mapFile);
        worlds.build(world, mapFile, built -> {
            boolean saved = layout.update(current -> current.withWorld(built.world()));
            messages.send(admin, "arena.admin.built", "world", built.world().name(), "blocks", built.blocks());
            if (!built.leftOut().isEmpty()) {
                messages.send(admin, "arena.admin.left-out", "blocks", String.join(", ", built.leftOut()));
            }
            if (!saved) {
                messages.send(admin, "arena.admin.not-saved");
            }
        }, reason -> messages.send(admin, reason, "map", mapFile, "world", world));
    }

    public void setHub(CommandSender admin, Place hub) {
        if (!isArenaWorld(hub.world())) {
            messages.send(admin, "arena.admin.not-an-arena-world", "world", hub.world());
            return;
        }
        boolean saved = layout.update(current -> current.withHub(hub));
        messages.send(admin, saved ? "arena.admin.hub-set" : "arena.admin.not-saved");
    }

    public void pitFloor(CommandSender admin, String name, Box floor) {
        pit(admin, name, floor.world()).ifPresent(pit -> save(admin, pit.withFloor(floor), "arena.admin.floor-set"));
    }

    public void pitSpawn(CommandSender admin, String name, Place spawn) {
        pit(admin, name, spawn.world()).ifPresent(pit -> save(admin, pit.withSpawn(spawn), "arena.admin.spawn-added"));
    }

    public void pitClearSpawns(CommandSender admin, String name) {
        Optional<Pit> pit = layout.current().pit(name);
        if (pit.isEmpty()) {
            messages.send(admin, "arena.admin.no-such-pit", "pit", name);
            return;
        }
        save(admin, pit.get().withoutSpawns(), "arena.admin.spawns-cleared");
    }

    public void pitRemove(CommandSender admin, String name) {
        if (layout.current().pit(name).isEmpty()) {
            messages.send(admin, "arena.admin.no-such-pit", "pit", name);
            return;
        }
        boolean saved = layout.update(current -> current.withoutPit(name));
        messages.send(admin, saved ? "arena.admin.pit-removed" : "arena.admin.not-saved", "pit", name);
    }

    /** The pit to change: the one of that name, or a new one in {@code world}; empty, after saying why, otherwise. */
    private Optional<Pit> pit(CommandSender admin, String name, String world) {
        if (!isArenaWorld(world)) {
            messages.send(admin, "arena.admin.not-an-arena-world", "world", world);
            return Optional.empty();
        }
        Optional<Pit> existing = layout.current().pit(name);
        if (existing.isPresent() && !existing.get().world().equalsIgnoreCase(world)) {
            messages.send(admin, "arena.admin.pit-elsewhere", "pit", name, "world", existing.get().world());
            return Optional.empty();
        }
        return Optional.of(existing.orElse(Pit.named(name, world)));
    }

    private void save(CommandSender admin, Pit pit, String key) {
        boolean saved = layout.update(current -> current.withPit(pit));
        messages.send(admin, saved ? key : "arena.admin.not-saved", "pit", pit.id(), "spawns", pit.spawns().size());
    }

    public List<String> mapFiles() {
        return worlds.mapFiles();
    }
}
