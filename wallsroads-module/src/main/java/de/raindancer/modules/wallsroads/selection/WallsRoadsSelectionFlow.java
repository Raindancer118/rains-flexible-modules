package de.raindancer.modules.wallsroads.selection;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.core.world.geometry.Polyline;
import de.raindancer.core.world.selection.MarkingListener;
import de.raindancer.core.world.selection.MarkingSession;
import de.raindancer.core.world.selection.MarkingSessions;
import de.raindancer.core.world.selection.MarkingTool;
import de.raindancer.core.world.visual.OutlineRenderer;
import de.raindancer.modules.wallsroads.WallsRoadsSettings;
import de.raindancer.modules.wallsroads.model.CornerStyle;
import de.raindancer.modules.wallsroads.model.ElevationMode;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.Wall;
import de.raindancer.modules.wallsroads.service.WallsRoadsService;
import de.raindancer.modules.wallsroads.store.WallsRoadsRegistry;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * What happens when somebody finishes marking a wall or a road out — the one place that decides,
 * so the stick, the command and the "open the selection menu" click all behave identically. Built
 * on Core's {@link MarkingTool}/{@link MarkingSessions}/{@link MarkingListener}: this class supplies
 * only the meaning of "finish", the same split {@code claims-module}'s own {@code SelectionFlow} has
 * always had over its stick.
 */
public final class WallsRoadsSelectionFlow implements MarkingListener.Callback {

    public enum Purpose {
        WALL, ROAD
    }

    private final MarkingTool tool;
    private final MarkingSessions sessions;
    private final OutlineRenderer outline;
    private final WallsRoadsRegistry registry;
    private final WallsRoadsService service;
    private final Messages messages;
    private final LogChannel log;
    private final Supplier<WallsRoadsSettings> settings;
    private final Map<UUID, Purpose> purposeByPlayer = new ConcurrentHashMap<>();

    public WallsRoadsSelectionFlow(MarkingTool tool, MarkingSessions sessions, OutlineRenderer outline,
                                   WallsRoadsRegistry registry, WallsRoadsService service, Messages messages,
                                   LogChannel log, Supplier<WallsRoadsSettings> settings) {
        this.tool = tool;
        this.sessions = sessions;
        this.outline = outline;
        this.registry = registry;
        this.service = service;
        this.messages = messages;
        this.log = log;
        this.settings = settings;
    }

    /** Starts a fresh selection and hands the player the stick. */
    public void begin(Player player, Purpose purpose) {
        stop(player);
        MarkingSession.Mode mode = MarkingSession.Mode.POLYGON;
        sessions.begin(player.getUniqueId(), player.getWorld().getName(), mode);
        purposeByPlayer.put(player.getUniqueId(), purpose);
        WallsRoadsSettings current = settings.get();
        String label = purpose == Purpose.WALL ? "<gradient:#94a3b8:#475569><bold>Wall Marking Stick</bold></gradient>"
                : "<gradient:#f59e0b:#b45309><bold>Road Marking Stick</bold></gradient>";
        List<String> lore = List.of(
                "<white>Right-click a block <gray>add a corner",
                "<yellow>Left-click a block <gray>undo the last corner",
                "<yellow>Shift + right-click <gray>finish now",
                "<yellow>Shift + left-click air <gray>cancel");
        var stick = tool.create(current.selectionStickMaterial(), purpose.name(), label, lore);
        tool.give(player, stick);
        messages.send(player, "wallsroads.selection.started", "purpose", purpose.name().toLowerCase());
    }

    public void cancel(Player player) {
        stop(player);
        messages.send(player, "wallsroads.selection.cancelled");
    }

    private void stop(Player player) {
        tool.revoke(player);
        sessions.clear(player.getUniqueId());
        purposeByPlayer.remove(player.getUniqueId());
        outline.stop(player);
    }

    @Override
    public void onVertexAdded(Player player, MarkingSession session) {
        messages.send(player, "wallsroads.selection.point-added", "count", String.valueOf(session.pointCount()));
    }

    @Override
    public void onVertexRemoved(Player player, MarkingSession session) {
        messages.send(player, "wallsroads.selection.point-removed", "count", String.valueOf(session.pointCount()));
    }

    @Override
    public void onEmpty(Player player) {
        messages.send(player, "wallsroads.selection.none");
    }

    @Override
    public void onCancel(Player player, MarkingSession session) {
        cancel(player);
    }

    @Override
    public void onFinish(Player player, MarkingSession session) {
        Purpose purpose = purposeByPlayer.get(player.getUniqueId());
        if (purpose == null) {
            messages.send(player, "wallsroads.selection.none");
            return;
        }
        WallsRoadsSettings current = settings.get();
        List<ColumnPolygon.Column> vertices = session.vertices();

        if (purpose == Purpose.WALL) {
            if (vertices.size() < 3) {
                messages.send(player, "wallsroads.selection.incomplete", "needed", "3", "have",
                        String.valueOf(vertices.size()));
                return;
            }
            ColumnPolygon outlinePolygon = new ColumnPolygon(vertices);
            String name = "Wall " + (registry.wallCount() + 1);
            Wall wall = new Wall(UUID.randomUUID().toString(), name, player.getUniqueId(),
                    player.getWorld().getName(), outlinePolygon,
                    player.getLocation().getBlockY(), current.wallHeight(), current.defaultWallMaterial(),
                    current.wallThickness(),
                    current.cornerRadius() > 0 ? CornerStyle.rounded(current.cornerRadius()) : CornerStyle.SHARP);
            registry.putWall(wall);
            service.buildWall(wall, () -> messages.send(player, "wallsroads.wall.created", "name", wall.name()));
        } else {
            if (vertices.size() < 2) {
                messages.send(player, "wallsroads.selection.incomplete", "needed", "2", "have",
                        String.valueOf(vertices.size()));
                return;
            }
            // Smoothed at creation rather than at build time: the shape is what the owner then sees,
            // edits and tears down, and a road whose stored path is not the one standing in the world
            // restores the wrong blocks.
            Polyline path = new Polyline(vertices).smoothed(current.curvinessClamped());
            String name = "Road " + (registry.roadCount() + 1);
            RoadPath road = new RoadPath(UUID.randomUUID().toString(), name, player.getUniqueId(),
                    player.getWorld().getName(), path, current.roadWidth(), current.defaultRoadMaterial(),
                    ElevationMode.FOLLOW_TERRAIN, player.getLocation().getBlockY());
            registry.putRoad(road);
            service.buildRoad(road, () -> messages.send(player, "wallsroads.road.created", "name", road.name()));
        }
        stop(player);
    }

    public void showLivePreview(Player player) {
        outline.showLive(player, player.getWorld(), () -> {
            var maybeSession = sessions.sessionOf(player.getUniqueId());
            return maybeSession.map(MarkingSession::vertices).orElse(List.of());
        }, () -> player.getLocation().getBlockY(), new Particle.DustOptions(org.bukkit.Color.YELLOW, 1.0f));
    }
}
