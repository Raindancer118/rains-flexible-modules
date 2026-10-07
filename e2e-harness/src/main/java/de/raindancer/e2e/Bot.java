package de.raindancer.e2e;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ObjectiveAction;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ScoreboardPosition;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.TeamAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundBossEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDisguisedChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerCombatKillPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetCursorItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetPlayerInventoryPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelParticlesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSoundPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundResetScorePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetDisplayObjectivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetObjectivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetPlayerTeamPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetScorePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetActionBarTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * One headless player, in offline mode, that sees what a real client is sent and does what a real
 * player does — typed commands, clicks in a window, right-clicks with the held item, the respawn
 * button — and nothing a client could not.
 *
 * <p>Everything it sees is kept: its inventory (with each item's name, lore and plugin tags), the
 * window it has open, its game mode, where it stands and in which world, every chat line and title,
 * every boss bar, the sidebar. A scenario asks and waits through {@link Await}.
 */
public final class Bot {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(60);
    /** How a click that copies to the clipboard is listed among a line's clicks — the client's own business. */
    public static final String COPY = "copy:";
    /** The player inventory as a window: 0 crafting result, 1–4 crafting, 5–8 armour, 9–35 main, 36–44 hotbar, 45 off hand. */
    private static final int PLAYER_WINDOW = 0;

    /** One item as the client sees it. */
    public record Item(String material, int amount, String name, List<String> lore, Map<String, String> tags) {

        /** The value of a plugin's tag — {@code manhunt-tracker} matches {@code anyplugin:manhunt-tracker}. */
        public Optional<String> tag(String key) {
            return tags.entrySet().stream().filter(entry -> entry.getKey().equals(key)
                    || entry.getKey().endsWith(":" + key)).map(Map.Entry::getValue).findFirst();
        }

        public boolean is(String wanted) {
            return material.equalsIgnoreCase(wanted);
        }

        @Override
        public String toString() {
            return amount + "x " + material + (name.isEmpty() ? "" : " \"" + name + "\"") + (tags.isEmpty() ? "" : " " + tags);
        }
    }

    /** A window the server opened. */
    public record Window(int id, String type, String title, int size, Map<Integer, Item> items) {

        /** The slots of the window itself, not the player's own inventory under it. */
        public Map<Integer, Item> top() {
            Map<Integer, Item> top = new LinkedHashMap<>();
            items.forEach((slot, item) -> {
                if (slot < size) {
                    top.put(slot, item);
                }
            });
            return top;
        }

        /** The first slot of the window whose item's name contains {@code text}. */
        public Optional<Integer> slotNamed(String text) {
            return top().entrySet().stream().filter(entry -> entry.getValue().name().contains(text))
                    .map(Map.Entry::getKey).findFirst();
        }
    }

    /** One chat line — its text, and the commands its click events would run. */
    public record Line(String text, List<String> clicks) {
    }

    private final PaperServer server;
    private final String name;
    private final UUID id;
    private final String version;
    private volatile ClientSession session;
    private volatile boolean joined;
    private volatile String disconnectReason = "";
    private volatile GameMode gameMode = GameMode.SURVIVAL;
    private volatile String world = "";
    private volatile Vector3d position = Vector3d.ZERO;
    private volatile float health = 20;
    private volatile int heldSlot;
    private volatile int windowState;
    private volatile Window window;
    private volatile boolean dead;
    /** Set once the client said it has loaded the world after a respawn — until then the server keeps it invulnerable. */
    private volatile boolean loaded;
    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<Integer, Item> inventory = new ConcurrentHashMap<>();
    private final List<Line> chat = new CopyOnWriteArrayList<>();
    private final List<String> titles = new CopyOnWriteArrayList<>();
    private final List<String> subtitles = new CopyOnWriteArrayList<>();
    private final List<String> actionBars = new CopyOnWriteArrayList<>();
    private final Map<UUID, String> bossBars = new ConcurrentHashMap<>();
    private final List<String> bossBarsSeen = new CopyOnWriteArrayList<>();
    /** Sound keys heard and particle types seen, since the last {@link #forgetEffects}. */
    private final List<String> soundsHeard = new CopyOnWriteArrayList<>();
    private final List<String> particlesSeen = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, Integer>> scores = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> scoreTexts = new ConcurrentHashMap<>();
    private final Map<String, String[]> teams = new ConcurrentHashMap<>();
    private final Map<String, String> objectiveTitles = new ConcurrentHashMap<>();
    private volatile String sidebarObjective = "";
    private final List<Window> windowsSeen = new CopyOnWriteArrayList<>();

    Bot(PaperServer server, String name) {
        this.server = server;
        this.name = name;
        // Paper's own offline-mode id for a name, so the server and the scenario agree on who this is.
        this.id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        this.version = "26.2";
    }

    public String name() {
        return name;
    }

    public UUID id() {
        return id;
    }

    // ---------------------------------------------------------------------------- connecting

    /**
     * Connects and waits until it is in the world. Retried only here — a connection refused because
     * the server is not listening yet is the one failure a scenario should not see.
     */
    public Bot join() {
        AssertionError last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                connect();
                Await.until(name + " is in the world", JOIN_TIMEOUT, () -> {
                    if (!disconnectReason.isEmpty()) {
                        throw new IllegalStateException(name + " was disconnected: " + disconnectReason);
                    }
                    return joined && !world.isEmpty();
                });
                return this;
            } catch (AssertionError | IllegalStateException failed) {
                if (failed instanceof IllegalStateException refused && refused.getMessage().contains("disconnected")
                        && !refused.getMessage().contains("Connection refused")) {
                    throw refused;   // turned away on purpose — a closed whitelist, a ban — is an answer
                }
                last = failed instanceof AssertionError assertion ? assertion : new AssertionError(failed);
                leave();
                Await.ticks(20 * attempt);
            }
        }
        throw last;
    }

    /** Connects and waits for the server to turn it away; returns why. */
    public String joinRefused() {
        connect();
        Await.until(name + " is turned away", JOIN_TIMEOUT, () -> !disconnectReason.isEmpty() || joined);
        if (joined) {
            throw new AssertionError(name + " got in, and was expected to be turned away");
        }
        return disconnectReason;
    }

    private void connect() {
        reset();
        MinecraftProtocol protocol = new MinecraftProtocol(new GameProfile(id, name), null);
        ClientSession client = ClientNetworkSessionFactory.factory()
                .setAddress("127.0.0.1", server.port())
                .setProtocol(protocol)
                .create();
        client.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session from, Packet packet) {
                try {
                    handle(from, packet);
                } catch (RuntimeException broken) {
                    System.err.println("[" + name + "] could not read " + packet.getClass().getSimpleName() + ": " + broken);
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                disconnectReason = PLAIN.serialize(event.getReason());
                joined = false;
            }
        });
        session = client;
        client.connect(true);
    }

    private void reset() {
        joined = false;
        disconnectReason = "";
        world = "";
        window = null;
        inventory.clear();
        bossBars.clear();
        scores.clear();
        scoreTexts.clear();
        teams.clear();
        objectiveTitles.clear();
        sidebarObjective = "";
    }

    /** Leaves the server, as closing the game does. */
    public void leave() {
        ClientSession open = session;
        if (open != null && open.isConnected()) {
            open.disconnect(Component.text("bye"));
            Await.until(name + " is gone", Duration.ofSeconds(10), () -> !open.isConnected());
        }
        joined = false;
        server.forget(this);
    }

    /** Leaves and comes back — what a reconnecting player is. */
    public Bot rejoin() {
        leave();
        Await.ticks(10);
        server.bot(name);   // registers again for the next restart
        return join();
    }

    public boolean isOnline() {
        ClientSession open = session;
        return open != null && open.isConnected() && joined;
    }

    public String disconnectReason() {
        return disconnectReason;
    }

    // ---------------------------------------------------------------------------- what it is sent

    private void handle(Session from, Packet packet) {
        MinecraftProtocol protocol = (MinecraftProtocol) from.getPacketProtocol();
        if (protocol.getInboundState() != ProtocolState.GAME) {
            return;
        }
        switch (packet) {
            case ClientboundLoginPacket login -> {
                spawnInfo(login.getCommonPlayerSpawnInfo());
                joined = true;
            }
            case ClientboundRespawnPacket respawn -> {
                spawnInfo(respawn.getCommonPlayerSpawnInfo());
                dead = false;
                loaded = false;
            }
            case ClientboundPlayerPositionPacket moved -> {
                Vector3d to = moved.getPosition();
                Vector3d was = position;
                List<PositionElement> relative = moved.getRelatives();
                position = Vector3d.from(
                        relative.contains(PositionElement.X) ? was.getX() + to.getX() : to.getX(),
                        relative.contains(PositionElement.Y) ? was.getY() + to.getY() : to.getY(),
                        relative.contains(PositionElement.Z) ? was.getZ() + to.getZ() : to.getZ());
                from.send(new ServerboundAcceptTeleportationPacket(moved.getId()));
                from.send(new ServerboundMovePlayerPosPacket(true, false, position.getX(), position.getY(), position.getZ()));
                from.send(ServerboundPlayerLoadedPacket.INSTANCE);
                loaded = true;
            }
            case ClientboundChunkBatchFinishedPacket ignored -> from.send(new ServerboundChunkBatchReceivedPacket(64f));
            case ClientboundGameEventPacket event -> {
                if (event.getNotification() == GameEvent.CHANGE_GAME_MODE && event.getValue() instanceof GameMode mode) {
                    gameMode = mode;
                }
            }
            case ClientboundSetHealthPacket set -> {
                health = set.getHealth();
                dead = health <= 0;
            }
            case ClientboundPlayerCombatKillPacket ignored -> dead = true;
            case ClientboundSetHeldSlotPacket held -> heldSlot = held.getSlot();
            case ClientboundSoundPacket sound -> soundsHeard.add(sound.getSound().getName().replace("minecraft:", ""));
            case ClientboundLevelParticlesPacket particles ->
                    particlesSeen.add(particles.getParticle().getType().name());
            case ClientboundSystemChatPacket line -> {
                if (line.isOverlay()) {
                    actionBars.add(PLAIN.serialize(line.getContent()));
                } else {
                    chat.add(lineOf(line.getContent()));
                }
            }
            case ClientboundPlayerChatPacket line -> chat.add(new Line(
                    PLAIN.serialize(line.getName()) + ": " + (line.getUnsignedContent() != null
                            ? PLAIN.serialize(line.getUnsignedContent()) : line.getContent()), List.of()));
            case ClientboundDisguisedChatPacket line -> chat.add(lineOf(line.getMessage()));
            case ClientboundSetTitleTextPacket title -> titles.add(PLAIN.serialize(Objects.requireNonNullElse(title.getText(), Component.empty())));
            case ClientboundSetSubtitleTextPacket subtitle -> subtitles.add(PLAIN.serialize(Objects.requireNonNullElse(subtitle.getText(), Component.empty())));
            case ClientboundSetActionBarTextPacket bar -> actionBars.add(PLAIN.serialize(Objects.requireNonNullElse(bar.getText(), Component.empty())));
            case ClientboundBossEventPacket bar -> {
                switch (bar.getAction()) {
                    case ADD, UPDATE_TITLE -> {
                        String title = PLAIN.serialize(bar.getTitle());
                        bossBars.put(bar.getUuid(), title);
                        bossBarsSeen.add(title);
                    }
                    case REMOVE -> bossBars.remove(bar.getUuid());
                    default -> { }
                }
            }
            case ClientboundOpenScreenPacket open -> {
                String type = open.getType().name();
                window = new Window(open.getContainerId(), type, PLAIN.serialize(open.getTitle()), sizeOf(type), new ConcurrentHashMap<>());
            }
            case ClientboundContainerSetContentPacket content -> {
                windowState = content.getStateId();
                Map<Integer, Item> target = itemsOf(content.getContainerId());
                if (target == null) {
                    return;
                }
                target.clear();
                ItemStack[] items = content.getItems();
                for (int slot = 0; slot < items.length; slot++) {
                    Item item = item(items[slot]);
                    if (item != null) {
                        target.put(slot, item);
                    }
                }
                Window open = window;
                if (open != null && content.getContainerId() == open.id()) {
                    windowsSeen.add(new Window(open.id(), open.type(), open.title(), open.size(), Map.copyOf(open.items())));
                    mirrorPlayerSlots(open);
                }
            }
            case ClientboundContainerSetSlotPacket set -> {
                windowState = set.getStateId();
                Map<Integer, Item> target = itemsOf(set.getContainerId());
                if (target == null) {
                    return;
                }
                Item item = item(set.getItem());
                if (item == null) {
                    target.remove(set.getSlot());
                } else {
                    target.put(set.getSlot(), item);
                }
            }
            case ClientboundSetPlayerInventoryPacket set -> {
                int slot = windowSlotOfInventoryIndex(set.getSlot());
                Item item = item(set.getContents());
                if (item == null) {
                    inventory.remove(slot);
                } else {
                    inventory.put(slot, item);
                }
            }
            case ClientboundSetCursorItemPacket ignored -> { }
            case ClientboundContainerClosePacket closed -> {
                // Only the window it names: a late close of the last page must not forget the next one.
                Window open = window;
                if (open != null && open.id() == closed.getContainerId()) {
                    window = null;
                }
            }
            case ClientboundSetObjectivePacket objective -> {
                if (objective.getAction() == ObjectiveAction.REMOVE) {
                    objectiveTitles.remove(objective.getName());
                    scores.remove(objective.getName());
                    scoreTexts.remove(objective.getName());
                } else {
                    objectiveTitles.put(objective.getName(), PLAIN.serialize(Objects.requireNonNullElse(objective.getDisplayName(), Component.empty())));
                }
            }
            case ClientboundSetDisplayObjectivePacket display -> {
                if (display.getPosition() == ScoreboardPosition.SIDEBAR) {
                    sidebarObjective = display.getName();
                }
            }
            case ClientboundSetScorePacket score -> {
                scores.computeIfAbsent(score.getObjective(), key -> new ConcurrentHashMap<>()).put(score.getOwner(), score.getValue());
                if (score.getDisplay() != null) {
                    scoreTexts.computeIfAbsent(score.getObjective(), key -> new ConcurrentHashMap<>())
                            .put(score.getOwner(), PLAIN.serialize(score.getDisplay()));
                }
            }
            case ClientboundResetScorePacket reset -> {
                if (reset.getObjective() == null) {
                    scores.values().forEach(table -> table.remove(reset.getOwner()));
                } else {
                    Optional.ofNullable(scores.get(reset.getObjective())).ifPresent(table -> table.remove(reset.getOwner()));
                }
            }
            case ClientboundSetPlayerTeamPacket team -> {
                if (team.getAction() == TeamAction.REMOVE) {
                    teams.remove(team.getTeamName());
                } else if (team.getAction() == TeamAction.CREATE || team.getAction() == TeamAction.UPDATE) {
                    String[] known = teams.getOrDefault(team.getTeamName(), new String[]{"", "", ""});
                    String[] players = team.getPlayers() == null ? new String[0] : team.getPlayers();
                    teams.put(team.getTeamName(), new String[]{
                            PLAIN.serialize(Objects.requireNonNullElse(team.getPlayerPrefix(), Component.empty())),
                            PLAIN.serialize(Objects.requireNonNullElse(team.getPlayerSuffix(), Component.empty())),
                            players.length > 0 ? String.join(",", players) : known[2]});
                } else if (team.getAction() == TeamAction.ADD_PLAYER && team.getPlayers() != null) {
                    String[] known = teams.getOrDefault(team.getTeamName(), new String[]{"", "", ""});
                    teams.put(team.getTeamName(), new String[]{known[0], known[1],
                            (known[2].isEmpty() ? "" : known[2] + ",") + String.join(",", team.getPlayers())});
                }
            }
            default -> { }
        }
    }

    private void spawnInfo(PlayerSpawnInfo info) {
        gameMode = info.getGameMode();
        world = info.getWorldName().asString();
    }

    private Map<Integer, Item> itemsOf(int containerId) {
        if (containerId == PLAYER_WINDOW) {
            return inventory;
        }
        Window open = window;
        return open != null && open.id() == containerId ? open.items() : null;
    }

    /** The bottom of an open window is the player's own inventory: kept in step with it. */
    private void mirrorPlayerSlots(Window open) {
        Map<Integer, Item> mirrored = new HashMap<>();
        open.items().forEach((slot, item) -> {
            if (slot >= open.size()) {
                int offset = slot - open.size();
                mirrored.put(offset < 27 ? 9 + offset : 36 + (offset - 27), item);
            }
        });
        inventory.keySet().removeIf(slot -> slot >= 9 && slot <= 44);
        inventory.putAll(mirrored);
    }

    private static int windowSlotOfInventoryIndex(int index) {
        if (index >= 0 && index <= 8) {
            return 36 + index;
        }
        if (index >= 36 && index <= 39) {
            return 8 - (index - 36);
        }
        if (index == 40) {
            return 45;
        }
        return index;
    }

    private static int sizeOf(String type) {
        return switch (type) {
            case "GENERIC_9X1" -> 9;
            case "GENERIC_9X2" -> 18;
            case "GENERIC_9X3", "SHULKER_BOX" -> 27;
            case "GENERIC_9X4" -> 36;
            case "GENERIC_9X5" -> 45;
            case "GENERIC_9X6" -> 54;
            case "GENERIC_3X3", "CRAFTER_3X3" -> 9;
            case "ANVIL", "FURNACE", "SMOKER", "BLAST_FURNACE", "GRINDSTONE", "MERCHANT" -> 3;
            case "HOPPER" -> 5;
            default -> 0;
        };
    }

    private Item item(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0 || stack.getId() == 0) {
            return null;
        }
        String customName = "";
        List<String> lore = List.of();
        Map<String, String> tags = new LinkedHashMap<>();
        if (stack.getDataComponentsPatch() != null) {
            Component named = stack.getDataComponentsPatch().get(DataComponentTypes.CUSTOM_NAME);
            if (named == null) {
                named = stack.getDataComponentsPatch().get(DataComponentTypes.ITEM_NAME);
            }
            customName = named == null ? "" : PLAIN.serialize(named);
            List<Component> lines = stack.getDataComponentsPatch().get(DataComponentTypes.LORE);
            lore = lines == null ? List.of() : lines.stream().map(PLAIN::serialize).toList();
            NbtMap custom = stack.getDataComponentsPatch().get(DataComponentTypes.CUSTOM_DATA);
            if (custom != null) {
                NbtMap bukkit = custom.getCompound("PublicBukkitValues");
                if (bukkit != null) {
                    bukkit.forEach((key, value) -> tags.put(key, String.valueOf(value)));
                }
            }
        }
        return new Item(ItemIds.name(stack.getId(), version), stack.getAmount(), customName, lore, Map.copyOf(tags));
    }

    private static Line lineOf(Component component) {
        List<String> clicks = new ArrayList<>();
        collectClicks(component, clicks);
        return new Line(PLAIN.serialize(component), List.copyOf(clicks));
    }

    private static void collectClicks(Component component, List<String> into) {
        ClickEvent<?> click = component.clickEvent();
        if (click != null && click.payload() instanceof ClickEvent.Payload.Text text) {
            if (click.action() == ClickEvent.Action.RUN_COMMAND || click.action() == ClickEvent.Action.SUGGEST_COMMAND) {
                into.add(text.value());
            } else if (click.action() == ClickEvent.Action.COPY_TO_CLIPBOARD) {
                into.add(COPY + text.value());
            }
        }
        component.children().forEach(child -> collectClicks(child, into));
    }

    // ---------------------------------------------------------------------------- what it does

    /**
     * Types a command, slash or not, the way a player does — and no faster than a player may: the
     * server counts 20 for every chat line or command and forgets one a tick, kicking at 200. The bot
     * keeps the same count and waits rather than be kicked for spam.
     */
    public Bot run(String command) {
        pace();
        session.send(new ServerboundChatCommandPacket(command.startsWith("/") ? command.substring(1) : command));
        return this;
    }

    /**
     * Says {@code text} in chat, unsigned — as an offline-mode server accepts from any client. Paced like
     * {@link #run}, since the server counts chat lines and commands alike.
     */
    public Bot say(String text) {
        pace();
        session.send(new ServerboundChatPacket(text, System.currentTimeMillis(), 0L, null, 0,
                new java.util.BitSet(20), 0));
        return this;
    }

    private long spamCount;
    private long spamAt = System.nanoTime();

    private synchronized void pace() {
        long now = System.nanoTime();
        spamCount = Math.max(0, spamCount - (now - spamAt) / 50_000_000L);
        spamAt = now;
        if (spamCount + 20 > 160) {
            long waitTicks = spamCount + 20 - 160;
            Await.ticks((int) waitTicks);
            spamCount -= waitTicks;
            spamAt = System.nanoTime();
        }
        spamCount += 20;
    }

    /** Clicks {@code slot} of the open window with the left button. */
    public Bot clickSlot(int slot) {
        Window open = Objects.requireNonNull(window, name + " has no window open to click in");
        session.send(new ServerboundContainerClickPacket(open.id(), windowState, slot, ContainerActionType.CLICK_ITEM,
                ClickItemAction.LEFT_CLICK, null, Map.of()));
        return this;
    }

    /** Right-clicks {@code slot} of the open window. */
    public Bot rightClickSlot(int slot) {
        Window open = Objects.requireNonNull(window, name + " has no window open to click in");
        session.send(new ServerboundContainerClickPacket(open.id(), windowState, slot, ContainerActionType.CLICK_ITEM,
                ClickItemAction.RIGHT_CLICK, null, Map.of()));
        return this;
    }

    /** Shift-clicks {@code slot} of the open window — what moves an item into a chest beside it. */
    public Bot shiftClickSlot(int slot) {
        Window open = Objects.requireNonNull(window, name + " has no window open to click in");
        session.send(new ServerboundContainerClickPacket(open.id(), windowState, slot, ContainerActionType.SHIFT_CLICK_ITEM,
                ShiftClickItemAction.LEFT_CLICK, null, Map.of()));
        return this;
    }

    /** Clicks the button whose name contains {@code text}; fails when the window has none. */
    public Bot click(String text) {
        Window open = Await.value(() -> name + " has a window with \"" + text + "\" (has " + (window == null ? "none" : window.top().values().stream().map(Item::name).toList()) + ")", Duration.ofSeconds(10),
                () -> window != null && window.slotNamed(text).isPresent() ? window : null);
        return clickSlot(open.slotNamed(text).orElseThrow());
    }

    /** Closes the open window, as Escape does. */
    public Bot closeWindow() {
        Window open = window;
        if (open != null) {
            session.send(new ServerboundContainerClosePacket(open.id()));
            window = null;
        }
        return this;
    }

    /** Holds hotbar slot {@code index} (0–8). */
    public Bot hold(int index) {
        heldSlot = index;
        session.send(new ServerboundSetCarriedItemPacket(index));
        return this;
    }

    /** Holds the first hotbar item matching, and right-clicks with it. */
    public Bot use(Predicate<Item> which) {
        for (int index = 0; index < 9; index++) {
            Item item = inventory.get(36 + index);
            if (item != null && which.test(item)) {
                hold(index);
                return useHeld();
            }
        }
        throw new AssertionError(name + " has no such item in the hotbar: " + hotbar());
    }

    /** Right-clicks into the air with whatever is held. */
    public Bot useHeld() {
        session.send(new ServerboundUseItemPacket(Hand.MAIN_HAND, sequence.incrementAndGet(), 0f, 0f));
        return this;
    }

    /** Right-clicks the top of the block at {@code x y z} with whatever is held — opens a chest, presses a button. */
    public Bot useOn(int x, int y, int z) {
        session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket(
                org.cloudburstmc.math.vector.Vector3i.from(x, y, z),
                org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction.UP, Hand.MAIN_HAND,
                0.5f, 1f, 0.5f, false, false, sequence.incrementAndGet()));
        return this;
    }

    /** Holds the sneak key down, or lets it go. */
    public Bot sneak(boolean down) {
        session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket(
                false, false, false, false, false, down, false));
        Await.ticks(2);
        return this;
    }

    /** Throws the held item away — the Q key. */
    public Bot dropHeld() {
        session.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket(
                org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction.DROP_ITEM,
                org.cloudburstmc.math.vector.Vector3i.ZERO,
                org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction.DOWN, 0));
        return this;
    }

    /** The hotbar slot (0–8) holding the first item matching, or fails. */
    public int hotbarSlotOf(Predicate<Item> which) {
        for (int index = 0; index < 9; index++) {
            Item item = inventory.get(36 + index);
            if (item != null && which.test(item)) {
                return index;
            }
        }
        throw new AssertionError(name + " has no such item in the hotbar: " + hotbar());
    }

    /** Clicks the respawn button, and waits until it is back in the world. */
    public Bot respawn() {
        session.send(new ServerboundClientCommandPacket(ClientCommand.PERFORM_RESPAWN));
        Await.until(name + " is back after respawning", Duration.ofSeconds(15), () -> !dead && health > 0 && loaded);
        Await.ticks(5);   // the server reads "loaded" on its next tick
        return this;
    }

    /** Takes a step — what a frozen player is held back from. */
    public Bot step(double dx, double dz) {
        Vector3d now = position;
        session.send(new ServerboundMovePlayerPosPacket(true, false, now.getX() + dx, now.getY(), now.getZ() + dz));
        position = Vector3d.from(now.getX() + dx, now.getY(), now.getZ() + dz);
        return this;
    }

    /** Clicks the first chat button whose command contains {@code fragment} — a click event, run as the client would. */
    public Bot clickChat(String fragment) {
        String command = Await.value(name + " was sent a chat button running \"" + fragment + "\"", Duration.ofSeconds(10),
                () -> chat.stream().flatMap(line -> line.clicks().stream()).filter(click -> click.contains(fragment))
                        .reduce((first, second) -> second).orElse(null));
        return run(command);
    }

    /**
     * Clicks button {@code index} (0 for the first) of the newest chat line containing {@code lineText}
     * — a Core chat button runs a callback command whose name is the server's business, so a button is
     * found by the line it sits on.
     */
    public Bot clickButtonOn(String lineText, int index) {
        String command = Await.value(() -> name + " was sent a line \"" + lineText + "\" with " + (index + 1)
                        + " button(s) (was sent " + chatText() + ")", Duration.ofSeconds(10),
                () -> {
                    for (int at = chat.size() - 1; at >= 0; at--) {
                        Line line = chat.get(at);
                        if (line.text().contains(lineText) && line.clicks().size() > index) {
                            return line.clicks().get(index);
                        }
                    }
                    return null;
                });
        // Copying happens on the client alone: nothing goes to the server.
        return command.startsWith(COPY) ? this : run(command);
    }

    /** The clicks of the newest line containing {@code lineText} — waited for. */
    public List<String> clicksOn(String lineText) {
        return Await.value(() -> name + " was sent a line \"" + lineText + "\" with buttons (was sent " + chatText() + ")",
                Duration.ofSeconds(10), () -> {
                    for (int at = chat.size() - 1; at >= 0; at--) {
                        if (chat.get(at).text().contains(lineText) && !chat.get(at).clicks().isEmpty()) {
                            return chat.get(at).clicks();
                        }
                    }
                    return null;
                });
    }

    // ---------------------------------------------------------------------------- what it can be asked

    public GameMode gameMode() {
        return gameMode;
    }

    public String world() {
        return world;
    }

    public Vector3d position() {
        return position;
    }

    public float health() {
        return health;
    }

    public boolean isDead() {
        return dead;
    }

    /** Every item it carries: slot (player window numbering) to item. */
    public Map<Integer, Item> inventory() {
        return Map.copyOf(inventory);
    }

    /** Main inventory and hotbar only — what a player thinks of as "carrying". */
    public List<Item> items() {
        return inventory.entrySet().stream().filter(entry -> entry.getKey() >= 9 && entry.getKey() <= 45)
                .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
    }

    public List<Item> hotbar() {
        List<Item> hotbar = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            hotbar.add(inventory.get(36 + index));
        }
        return Collections.unmodifiableList(hotbar);
    }

    public Optional<Item> carrying(Predicate<Item> which) {
        return items().stream().filter(which).findFirst();
    }

    /** The window open right now, if any. */
    public Optional<Window> window() {
        return Optional.ofNullable(window);
    }

    /** Waits for a window whose title contains {@code text}. */
    public Window awaitWindow(String text) {
        return Await.value(() -> name + " sees a window titled \"" + text + "\" (sees " + (window == null ? "none" : "\"" + window.title() + "\"") + ")", Duration.ofSeconds(15),
                () -> window != null && window.title().contains(text) && !window.items().isEmpty() ? window : null);
    }

    /** Every window it was shown, filled, in order. */
    public List<Window> windowsSeen() {
        return List.copyOf(windowsSeen);
    }

    public List<Line> chat() {
        return List.copyOf(chat);
    }

    /** Every chat line so far, as text. */
    public List<String> chatText() {
        return chat.stream().map(Line::text).toList();
    }

    /** Forgets what it was told so far, so the next expectation reads only what comes after. */
    public Bot forgetChat() {
        chat.clear();
        titles.clear();
        subtitles.clear();
        actionBars.clear();
        bossBarsSeen.clear();
        return this;
    }

    public List<String> titles() {
        return List.copyOf(titles);
    }

    public List<String> subtitles() {
        return List.copyOf(subtitles);
    }

    public List<String> actionBars() {
        return List.copyOf(actionBars);
    }

    /** The boss bars showing now. */
    /** Every sound it was played since the last {@link #forgetEffects}, as keys like entity.enderman.teleport. */
    public List<String> soundsHeard() {
        return List.copyOf(soundsHeard);
    }

    /** Every particle type it was shown since the last {@link #forgetEffects}, like PORTAL. */
    public List<String> particlesSeen() {
        return List.copyOf(particlesSeen);
    }

    public Bot forgetEffects() {
        soundsHeard.clear();
        particlesSeen.clear();
        return this;
    }

    public List<String> bossBars() {
        return List.copyOf(bossBars.values());
    }

    /** Every boss bar title it was shown since the last {@link #forgetChat}. */
    public List<String> bossBarsSeen() {
        return List.copyOf(bossBarsSeen);
    }

    /** The sidebar now: its title, then its lines top to bottom. Empty when there is none. */
    public List<String> sidebar() {
        String objective = sidebarObjective;
        if (objective.isEmpty() || !objectiveTitles.containsKey(objective)) {
            return List.of();
        }
        Map<String, Integer> table = scores.getOrDefault(objective, Map.of());
        Map<String, String> texts = scoreTexts.getOrDefault(objective, Map.of());
        List<String> lines = new ArrayList<>();
        lines.add(objectiveTitles.get(objective));
        table.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> lines.add(texts.getOrDefault(entry.getKey(), teamText(entry.getKey()))));
        return lines;
    }

    private String teamText(String owner) {
        for (String[] team : teams.values()) {
            for (String member : team[2].split(",")) {
                if (member.equals(owner)) {
                    return team[0] + owner + team[1];
                }
            }
        }
        return owner;
    }

    // ---------------------------------------------------------------------------- expecting

    /** What the server answered to one action of this bot: the chat lines and the window it was sent. */
    public record Answer(List<String> chat, Optional<Window> window) {

        public boolean says(String text) {
            return chat.stream().anyMatch(line -> line.contains(text));
        }

        public boolean opened(String title) {
            return window.map(open -> open.title().contains(title)).orElse(false);
        }

        @Override
        public String toString() {
            return "chat " + chat + ", window " + window.map(Window::title).orElse("none");
        }
    }

    /**
     * Does {@code action} and waits until the server answered it in a way {@code accepted} takes — a
     * line in chat, a window — and returns the answer. Fails with what it did answer.
     */
    public Answer answer(Runnable action, Predicate<Answer> accepted) {
        int before = chat.size();
        Window windowBefore = window;
        action.run();
        Answer[] last = new Answer[1];
        Await.until(() -> name + " is answered as expected (was answered: " + last[0] + ")", Duration.ofSeconds(15), () -> {
            Window now = window;
            Answer answer = new Answer(chat.subList(Math.min(before, chat.size()), chat.size()).stream().map(Line::text).toList(),
                    now != null && now != windowBefore && !now.items().isEmpty() ? Optional.of(now) : Optional.empty());
            last[0] = answer;
            return accepted.test(answer);
        });
        return last[0];
    }

    /** Runs {@code command} and waits until a chat line contains {@code text}. */
    public Answer runAndExpect(String command, String text) {
        return answer(() -> run(command), answer -> answer.says(text));
    }

    /** Runs {@code command} and waits for a window titled like {@code title}. */
    public Answer runAndOpen(String command, String title) {
        return answer(() -> run(command), answer -> answer.opened(title));
    }

    /** Waits until a chat line contains {@code text}. */
    public Line expectChat(String text) {
        return Await.value(() -> name + " is told \"" + text + "\" (was told: " + chatText() + ")", Duration.ofSeconds(15),
                () -> chat.stream().filter(line -> line.text().contains(text)).findFirst().orElse(null));
    }

    /** That no chat line contains {@code text} within a moment. */
    public void expectNoChat(String text, Duration during) {
        Await.never(name + " is told \"" + text + "\"", during, () -> chat.stream().anyMatch(line -> line.text().contains(text)));
    }

    /** Waits until it carries an item matching. */
    public Item expectItem(String what, Predicate<Item> which) {
        return Await.value(() -> name + " carries " + what + " (carries: " + items() + ")", Duration.ofSeconds(15),
                () -> carrying(which).orElse(null));
    }

    /** That it does not carry such an item — waited for, since taking something away takes a tick. */
    public void expectNoItem(String what, Predicate<Item> which) {
        Await.until(() -> name + " no longer carries " + what + " (carries: " + items() + ")", Duration.ofSeconds(15),
                () -> carrying(which).isEmpty());
    }

    public void expectGameMode(GameMode wanted) {
        Await.until(() -> name + " is in " + wanted + " (is " + gameMode + ")", Duration.ofSeconds(15), () -> gameMode == wanted);
    }

    public void expectWorld(String containing) {
        Await.until(() -> name + " is in a world named like " + containing + " (is in " + world + ")", Duration.ofSeconds(30),
                () -> world.contains(containing));
    }

    public List<String> expectSidebar(String containing) {
        return Await.value(() -> name + "'s sidebar shows \"" + containing + "\" (shows " + sidebar() + ")", Duration.ofSeconds(15),
                () -> sidebar().stream().anyMatch(line -> line.contains(containing)) ? sidebar() : null);
    }

    public String expectTitle(String containing) {
        return Await.value(() -> name + " is shown the title \"" + containing + "\" (was shown " + titles + " / " + subtitles + ")",
                Duration.ofSeconds(15), () -> titles.stream().filter(title -> title.contains(containing)).findFirst()
                        .or(() -> subtitles.stream().filter(title -> title.contains(containing)).findFirst()).orElse(null));
    }

    public String expectBossBar(String containing) {
        return Await.value(() -> name + " is shown a boss bar \"" + containing + "\" (was shown " + bossBarsSeen + ")",
                Duration.ofSeconds(15), () -> bossBarsSeen.stream().filter(bar -> bar.contains(containing)).findFirst().orElse(null));
    }

    public String expectActionBar(String containing) {
        return Await.value(() -> name + " is shown \"" + containing + "\" above the hotbar (was shown " + actionBars + ")",
                Duration.ofSeconds(15), () -> actionBars.stream().filter(bar -> bar.contains(containing)).findFirst().orElse(null));
    }

    @Override
    public String toString() {
        return name;
    }
}
