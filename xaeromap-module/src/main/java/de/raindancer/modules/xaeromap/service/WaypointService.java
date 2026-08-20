package de.raindancer.modules.xaeromap.service;

import de.raindancer.core.world.poi.Poi;
import de.raindancer.modules.xaeromap.XaeroMapSettings;
import de.raindancer.modules.xaeromap.model.Waypoint;
import de.raindancer.modules.xaeromap.model.XaeroShare;
import de.raindancer.modules.xaeromap.rules.WaypointVisibilityRule;
import de.raindancer.modules.xaeromap.store.MapClients;
import de.raindancer.modules.xaeromap.store.PlaceLookup;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Offers a player their homes and the warps they may use as waypoints on their own map.
 *
 * <h2>Why this is an offer and not a push</h2>
 * Neither Xaero mod has a way for a server to put a waypoint on a client's map. Its own share feature
 * is the whole of what exists: a chat message that is nothing but {@code xaero-waypoint:…} becomes a
 * button, and the player clicks it. Two things follow, and both are visible to whoever uses this:
 *
 * <ul>
 *   <li><b>One click per place.</b> There is no batch. Ten homes is ten buttons, which is why this is a
 *       command somebody runs rather than something that happens on every join — a wall of eleven lines
 *       every time you log in is worse than a command you type once a month.</li>
 *   <li><b>Only to clients that have the mod.</b> Without it, the raw line is shown to the player
 *       exactly as written. {@link MapClients} is what a client's own channel registration tells us,
 *       and nothing goes to anybody else.</li>
 * </ul>
 *
 * <p>Once added, a waypoint belongs to the client: renaming the home here does not rename it there, and
 * deleting the home does not remove it. That is the mod's model, not a shortcoming of this — and it is
 * why the wording says "add to your map" rather than anything that sounds like a live link.
 */
public final class WaypointService implements IXaeroMapService {

    /** RainsCore's kind for a home, which {@code homes-module} writes. */
    public static final String HOMES = "home";

    /** And for a warp, which {@code warp-module} writes. */
    public static final String WARPS = "warp";

    private final Supplier<PlaceLookup> places;
    private final MapClients clients;
    private final Server server;

    private volatile XaeroMapSettings settings;

    public WaypointService(Supplier<PlaceLookup> places, MapClients clients, Server server,
                           XaeroMapSettings settings) {
        this.places = places;
        this.clients = clients;
        this.server = server;
        this.settings = settings;
    }

    @Override
    public void settings(XaeroMapSettings settings) {
        this.settings = settings;
    }

    /** Whether this is switched on at all. */
    public boolean enabled() {
        return settings.waypoints();
    }

    /** Whether there is any point offering this player anything. */
    public boolean canReceive(Player player) {
        return player != null && clients.hasAMapMod(player.getUniqueId());
    }

    /** This player's own homes, as waypoints. */
    public List<Waypoint> homesOf(Player player) {
        if (player == null) {
            return List.of();
        }
        return waypointsOf(player, places.get().owned(player.getUniqueId(), HOMES),
                settings.homeColour());
    }

    /** Every warp this player may actually use, as waypoints. */
    public List<Waypoint> warpsFor(Player player) {
        if (player == null) {
            return List.of();
        }
        return waypointsOf(player, places.get().ofKind(WARPS), settings.warpColour());
    }

    /**
     * Sends one offer per place.
     *
     * <p>Each line is its own message and carries nothing but the share string — no prefix, no colour,
     * no brand. The client matches the whole message, so a decorated one is not recognised and is shown
     * to the player as the raw text it is.
     *
     * @return how many were actually sent
     */
    public int offer(Player player, List<Waypoint> waypoints) {
        if (!enabled() || !canReceive(player) || waypoints == null) {
            return 0;
        }
        int sent = 0;
        for (Waypoint waypoint : waypoints) {
            String line = waypoint.shareLine();
            if (!XaeroShare.looksValid(line)) {
                // Ten fields or the client ignores it. Not worth sending a line that cannot work, and
                // worth not counting it either — the player is told how many arrived.
                continue;
            }
            // Component.text, never MiniMessage: a place is named by a player, and a name containing
            // something that looks like a tag would be parsed rather than shown. It also must not be
            // wrapped in any styling — the client matches the message itself.
            player.sendMessage(net.kyori.adventure.text.Component.text(line));
            sent++;
        }
        return sent;
    }

    private List<Waypoint> waypointsOf(Player player, List<Poi> found, NamedTextColor colour) {
        WaypointVisibilityRule mayHave = new WaypointVisibilityRule(player::hasPermission);
        Map<String, Waypoint> byId = new LinkedHashMap<>();
        for (Poi place : found) {
            if (!mayHave.mayHave(player.getUniqueId(), place)) {
                continue;
            }
            String dimension = dimensionOf(place.world());
            if (dimension == null) {
                // A place in a world that is not loaded. Its coordinates are real, but which map they
                // belong on is not something this server can currently answer, and guessing puts the
                // waypoint on whichever map the player is looking at.
                continue;
            }
            byId.put(place.id(), new Waypoint(place.id(), place.label(), place.kind(), dimension,
                    (int) Math.floor(place.x()), (int) Math.floor(place.y()),
                    (int) Math.floor(place.z()), colour));
        }
        return List.copyOf(new ArrayList<>(byId.values()));
    }

    /** The client's own key for the world a place names, or {@code null} if there is no such world. */
    private String dimensionOf(String worldName) {
        if (server == null) {
            return null;
        }
        World world = server.getWorld(worldName);
        if (world != null) {
            return world.getKey().toString();
        }
        UUID asId = asUuid(worldName);
        World byId = asId == null ? null : server.getWorld(asId);
        return byId == null ? null : byId.getKey().toString();
    }

    /** Places store a world by name, but a stored uuid is worth still resolving rather than dropping. */
    private static UUID asUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
