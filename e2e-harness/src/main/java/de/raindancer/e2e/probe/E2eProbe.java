package de.raindancer.e2e.probe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/**
 * The harness's eyes on the server side: every menu opened (which class, which title, which buttons),
 * every click in one, and every command anybody ran — one JSON line each in {@code events.log}. The
 * coverage check reads that file to say which commands, pages and buttons a run exercised.
 *
 * <p>Also {@code /e2e as <player> <command>} for the console: runs a command as that player, which is
 * how a menu is opened on a client that cannot type — the screenshot client.
 *
 * <p>Only ever on a test server: the harness stages it, nothing ships it.
 */
public final class E2eProbe extends JavaPlugin implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private Writer events;

    @Override
    public void onEnable() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            events = Files.newBufferedWriter(getDataFolder().toPath().resolve("events.log"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException cannot) {
            throw new IllegalStateException("the probe cannot write its events", cannot);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("E2E probe is listening.");
    }

    @Override
    public void onDisable() {
        try {
            if (events != null) {
                events.close();
            }
        } catch (IOException ignored) {
            // the server is stopping; what was written is flushed already
        }
    }

    private synchronized void write(JsonObject event) {
        event.addProperty("at", System.currentTimeMillis());
        try {
            events.write(event.toString());
            events.write('\n');
            events.flush();
        } catch (IOException failed) {
            getLogger().warning("could not write an event: " + failed.getMessage());
        }
    }

    private static String plain(Component component) {
        return component == null ? "" : PLAIN.serialize(component);
    }

    private static String holderOf(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        return holder == null ? "" : holder.getClass().getName();
    }

    private static JsonObject item(int slot, ItemStack stack) {
        JsonObject item = new JsonObject();
        item.addProperty("slot", slot);
        item.addProperty("material", stack.getType().name());
        item.addProperty("amount", stack.getAmount());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            item.addProperty("name", meta.hasDisplayName() ? plain(meta.displayName()) : "");
            JsonArray lore = new JsonArray();
            if (meta.hasLore() && meta.lore() != null) {
                meta.lore().forEach(line -> lore.add(plain(line)));
            }
            item.add("lore", lore);
            JsonObject tags = new JsonObject();
            PersistentDataContainer data = meta.getPersistentDataContainer();
            for (var key : data.getKeys()) {
                String value = data.has(key, PersistentDataType.STRING) ? data.get(key, PersistentDataType.STRING) : "?";
                tags.addProperty(key.toString(), value);
            }
            item.add("tags", tags);
        }
        return item;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent event) {
        // A tick later: a menu fills its window as it opens, and some of it only after. The window is the
        // one this event opened, not whatever is open a tick on — a fast client may have shut it by then.
        Player player = (Player) event.getPlayer();
        Inventory top = event.getInventory();
        net.kyori.adventure.text.Component title = event.getView().title();
        // The player's own scheduler, not Bukkit's: Folia has no main thread to put it on.
        player.getScheduler().runDelayed(this, task -> {
            JsonObject open = new JsonObject();
            open.addProperty("event", "open");
            open.addProperty("player", player.getName());
            open.addProperty("holder", holderOf(top));
            open.addProperty("title", plain(title));
            open.addProperty("size", top.getSize());
            JsonArray items = new JsonArray();
            ItemStack[] contents = top.getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                if (contents[slot] != null && !contents[slot].getType().isAir()) {
                    items.add(item(slot, contents[slot]));
                }
            }
            open.add("items", items);
            JsonArray clickable = new JsonArray();
            java.util.Set<Integer> buttons = buttonsOf(top.getHolder(false));
            buttons.forEach(clickable::add);
            open.add("buttons", clickable);
            // A list's own entries — one handler, many rows of data — are told apart from its controls.
            JsonArray entries = new JsonArray();
            if (isList(top.getHolder(false))) {
                buttons.stream().filter(slot -> slot < LIST_ENTRY_SLOTS).forEach(entries::add);
            }
            open.add("entries", entries);
            open.addProperty("list", isList(top.getHolder(false)));
            write(open);
        }, null, 1L);
    }

    /** Core's PaginatedMenu puts its entries in the first four rows; the toolbar below is controls. */
    private static final int LIST_ENTRY_SLOTS = 36;

    private static boolean isList(InventoryHolder holder) {
        for (Class<?> type = holder == null ? null : holder.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getSimpleName().equals("PaginatedMenu")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The slots of a menu that do something when clicked — read from the menu itself (a Core menu
     * keeps a handler per button), so "every button" means exactly the ones a player can press.
     */
    @SuppressWarnings("unchecked")
    private static java.util.Set<Integer> buttonsOf(InventoryHolder holder) {
        if (holder == null) {
            return java.util.Set.of();
        }
        for (Class<?> type = holder.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field handlers = type.getDeclaredField("handlers");
                handlers.setAccessible(true);
                Object map = handlers.get(holder);
                if (map instanceof java.util.Map<?, ?> found) {
                    return new java.util.TreeSet<>((java.util.Set<Integer>) found.keySet());
                }
            } catch (NoSuchFieldException notHere) {
                // further up
            } catch (IllegalAccessException | ClassCastException unreadable) {
                return java.util.Set.of();
            }
        }
        return java.util.Set.of();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        JsonObject click = new JsonObject();
        click.addProperty("event", "click");
        click.addProperty("player", event.getWhoClicked().getName());
        click.addProperty("holder", holderOf(event.getView().getTopInventory()));
        click.addProperty("title", plain(event.getView().title()));
        click.addProperty("slot", event.getRawSlot());
        click.addProperty("top", event.getRawSlot() < event.getView().getTopInventory().getSize());
        click.addProperty("click", event.getClick().name());
        ItemStack stack = event.getCurrentItem();
        click.addProperty("material", stack == null ? "AIR" : stack.getType().name());
        click.addProperty("name", stack == null || stack.getItemMeta() == null || !stack.getItemMeta().hasDisplayName()
                ? "" : plain(stack.getItemMeta().displayName()));
        click.addProperty("cancelled", event.isCancelled());
        write(click);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoaded(io.papermc.paper.event.player.PlayerClientLoadedWorldEvent event) {
        JsonObject loaded = new JsonObject();
        loaded.addProperty("event", "loaded");
        loaded.addProperty("player", event.getPlayer().getName());
        loaded.addProperty("timeout", event.isTimeout());
        write(loaded);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        JsonObject moved = new JsonObject();
        moved.addProperty("event", "teleport");
        moved.addProperty("player", event.getPlayer().getName());
        moved.addProperty("cause", event.getCause().name());
        moved.addProperty("to", event.getTo().getWorld().getName());
        moved.addProperty("cancelled", event.isCancelled());
        write(moved);
    }

    /** A right- or left-click with an item — what a failed "use the compass" step is diagnosed from. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL) {
            return;
        }
        JsonObject used = new JsonObject();
        used.addProperty("event", "interact");
        used.addProperty("player", event.getPlayer().getName());
        used.addProperty("action", event.getAction().name());
        used.addProperty("sneaking", event.getPlayer().isSneaking());
        used.addProperty("hand", String.valueOf(event.getHand()));
        if (event.getItem() != null) {
            used.add("item", item(-1, event.getItem()));
        }
        used.addProperty("cancelled", event.useItemInHand() == org.bukkit.event.Event.Result.DENY);
        write(used);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        JsonObject closed = new JsonObject();
        closed.addProperty("event", "close");
        closed.addProperty("player", event.getPlayer().getName());
        closed.addProperty("reason", event.getReason().name());
        write(closed);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        command(event.getPlayer().getName(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsoleCommand(ServerCommandEvent event) {
        command(event.getSender() instanceof ConsoleCommandSender ? "console" : event.getSender().getName(),
                event.getCommand());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemoteCommand(RemoteServerCommandEvent event) {
        command("rcon", event.getCommand());
    }

    private void command(String who, String line) {
        JsonObject command = new JsonObject();
        command.addProperty("event", "command");
        command.addProperty("sender", who);
        command.addProperty("line", line.startsWith("/") ? line.substring(1) : line);
        write(command);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender)) {
            sender.sendMessage("Only the console drives the e2e probe.");
            return true;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("as")) {
            Player player = Bukkit.getPlayerExact(args[1]);
            if (player == null) {
                sender.sendMessage("No player " + args[1]);
                return true;
            }
            String line = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            player.getScheduler().run(this, task -> player.performCommand(line), null);
            sender.sendMessage("ran as " + player.getName() + ": " + line);
            return true;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("click")) {
            Player player = Bukkit.getPlayerExact(args[1]);
            int slot = Integer.parseInt(args[2]);
            if (player == null) {
                sender.sendMessage("No player " + args[1]);
                return true;
            }
            // A left click as the client would send it, for a client that cannot be scripted — the
            // screenshot client. The menu answers it exactly as it answers a real one.
            player.getScheduler().run(this, task -> {
                org.bukkit.inventory.InventoryView view = player.getOpenInventory();
                Bukkit.getPluginManager().callEvent(new InventoryClickEvent(view,
                        org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, slot,
                        org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL));
            }, null);
            sender.sendMessage("clicked " + slot + " for " + player.getName());
            return true;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("use")) {
            Player player = Bukkit.getPlayerExact(args[1]);
            if (player == null) {
                sender.sendMessage("No player " + args[1]);
                return true;
            }
            String tag = args[2];
            boolean sneak = args.length > 3 && args[3].equalsIgnoreCase("sneak");
            // A right-click into the air with the first item carrying that tag, sneaking or not.
            player.getScheduler().run(this, task -> {
                ItemStack[] contents = player.getInventory().getContents();
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack stack = contents[slot];
                    if (stack != null && stack.getItemMeta() != null && stack.getItemMeta().getPersistentDataContainer()
                            .getKeys().stream().anyMatch(key -> key.getKey().equals(tag))) {
                        player.getInventory().setHeldItemSlot(slot);
                        player.setSneaking(sneak);
                        Bukkit.getPluginManager().callEvent(new org.bukkit.event.player.PlayerInteractEvent(player,
                                org.bukkit.event.block.Action.RIGHT_CLICK_AIR, stack, null,
                                org.bukkit.block.BlockFace.SELF, org.bukkit.inventory.EquipmentSlot.HAND));
                        player.setSneaking(false);
                        return;
                    }
                }
            }, null);
            sender.sendMessage("used " + tag + " for " + player.getName());
            return true;
        }
        sender.sendMessage("e2e as <player> <command...> | e2e click <player> <slot> | e2e use <player> <tag> [sneak]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    /** Where this plugin writes its events in a server folder. */
    public static Path eventsIn(Path serverFolder) {
        return serverFolder.resolve("plugins").resolve("E2eProbe").resolve("events.log");
    }
}
