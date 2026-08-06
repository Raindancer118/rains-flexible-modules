package de.raindancer.modules.hungergames.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.team.Team;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.Participant;
import de.raindancer.modules.hungergames.store.GameSession;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * What every API endpoint needs and none of them should build twice: resolving a player or a location out
 * of a request, turning a tribute or a team into JSON, and writing a line to this module's log channel.
 *
 * <h2>Why player resolution does not use {@code Bukkit.getOfflinePlayer}</h2>
 * {@code ReuseTest} forbids it for a reason worth restating here: it is a blocking lookup that, for a name
 * nobody has seen, can reach out to Mojang from whatever thread called it — the last thread a request
 * handler wants to block is the one an HTTP client is waiting on. Everybody this API can name is either a
 * registered tribute (known to {@link GameSession}, no lookup needed) or currently online (known to
 * Bukkit's connected-player list, also no lookup needed); an offline stranger has to be named by UUID.
 */
public final class ApiSupport implements IHungerGamesService {

    /** The actor name written into log lines for changes made through the API, not by a person in-game. */
    public static final String ACTOR = "HTTP-API";

    private final GameSession session;
    private final LogChannel log;
    private volatile HungerGamesSettings settings;

    public ApiSupport(GameSession session, LogChannel log, HungerGamesSettings settings) {
        this.session = session;
        this.log = log;
        this.settings = settings;
    }

    public GameSession session() {
        return session;
    }

    public HungerGamesSettings settings() {
        return settings;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /** Writes one line to this module's log channel, tagged as coming through the API. */
    public void log(String message) {
        log.info("[API] {}", message);
    }

    // ==================== resolving players ====================

    /**
     * An online player by name or UUID.
     *
     * @throws ApiConflictException if nobody by that name or id is currently connected
     */
    public Player requireOnlinePlayer(String nameOrUuid) {
        Player player = findOnlinePlayer(nameOrUuid);
        if (player == null) {
            throw new ApiConflictException("\"" + nameOrUuid + "\" is not online");
        }
        return player;
    }

    /** The online player by name or UUID, or {@code null}. */
    public Player findOnlinePlayer(String nameOrUuid) {
        Player byName = Bukkit.getPlayerExact(nameOrUuid);
        if (byName != null) {
            return byName;
        }
        try {
            return Bukkit.getPlayer(UUID.fromString(nameOrUuid.trim()));
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /**
     * A player out of the body: the {@code uuid} field directly, or {@code player} resolved by name.
     *
     * @throws ApiBadRequestException when neither field is present
     * @throws ApiConflictException   when a name cannot be matched to anybody
     */
    public UUID requirePlayerUuid(ApiRequest request) {
        Optional<UUID> direct = request.optUuid("uuid");
        if (direct.isPresent()) {
            return direct.get();
        }
        if (!request.has("player")) {
            throw new ApiBadRequestException(
                    "Expected \"uuid\" (a UUID) or \"player\" (a name) in the JSON body");
        }
        return resolvePlayerRef(request.requireString("player"));
    }

    /** A path segment naming a player: a UUID directly, or a name resolved via {@link #resolveName}. */
    public UUID resolvePlayerRef(String uuidOrName) {
        try {
            return UUID.fromString(uuidOrName.trim());
        } catch (IllegalArgumentException notAUuid) {
            return resolveName(uuidOrName);
        }
    }

    /**
     * A name resolved to a UUID: a registered tribute first, then somebody currently online.
     *
     * <p>An offline stranger is deliberately not covered — see the class note — so a caller naming
     * somebody who has never registered and is not connected right now has to send the UUID instead.
     */
    public UUID resolveName(String name) {
        for (Participant participant : session.participants().all()) {
            if (participant.lastKnownName().equalsIgnoreCase(name)) {
                return participant.uuid();
            }
        }
        Player online = findOnlinePlayer(name);
        if (online != null) {
            return online.getUniqueId();
        }
        throw new ApiConflictException("\"" + name
                + "\" is neither a registered tribute nor online — send the UUID for an offline stranger");
    }

    // ==================== resolving locations ====================

    /**
     * A location out of the body: either {@code {"player": "Name"}} (their current position) or
     * {@code {"world": ..., "x": .., "y": .., "z": ..}}.
     */
    public Location requireLocation(ApiRequest request) {
        if (request.has("player")) {
            return requireOnlinePlayer(request.requireString("player")).getLocation();
        }
        World world = resolveWorld(request.optString("world", null));
        return new Location(world, request.requireDouble("x"), request.requireDouble("y"),
                request.requireDouble("z"));
    }

    /** A named world, or — with no name given — whichever world the caller's session is running in. */
    public World resolveWorld(String name) {
        if (name != null && !name.isBlank()) {
            World world = Bukkit.getWorld(name);
            if (world == null) {
                throw new ApiBadRequestException("Unknown world: " + name);
            }
            return world;
        }
        World fallback = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        if (fallback == null) {
            throw new ApiBadRequestException("Field \"world\" is required — the server has no world loaded");
        }
        return fallback;
    }

    // ==================== JSON views ====================

    /** A tribute, fully described. */
    public JsonObject participantJson(Participant participant) {
        JsonObject entry = new JsonObject();
        entry.addProperty("uuid", participant.uuid().toString());
        entry.addProperty("name", participant.lastKnownName());
        entry.addProperty("alive", participant.isAlive());
        entry.addProperty("online", Bukkit.getPlayer(participant.uuid()) != null);
        entry.addProperty("kills", session.kills().kills(participant.uuid()));
        participant.teamId().flatMap(id -> session.teams().team(id))
                .ifPresentOrElse(team -> {
                    entry.addProperty("team", team.name());
                    entry.addProperty("teamId", team.id().value());
                    entry.addProperty("teamColour", team.colour().name());
                }, () -> entry.add("team", null));
        return entry;
    }

    /** A team, with its members and captain. */
    public JsonObject teamJson(Team team) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", team.id().value());
        entry.addProperty("name", team.name());
        entry.addProperty("colour", team.colour().name());
        JsonArray members = new JsonArray();
        for (UUID member : team.members()) {
            JsonObject memberJson = new JsonObject();
            memberJson.addProperty("uuid", member.toString());
            memberJson.addProperty("name", session.participants().nameOf(member).orElse("?"));
            memberJson.addProperty("alive", session.participants().isAlive(member));
            members.add(memberJson);
        }
        entry.add("members", members);
        entry.addProperty("size", team.size());
        team.captain().ifPresentOrElse(captain -> {
            entry.addProperty("captain", captain.toString());
            entry.addProperty("captainName", session.participants().nameOf(captain).orElse("?"));
        }, () -> entry.add("captain", null));
        return entry;
    }

    /** A location as JSON, or {@code null} for none. */
    public static JsonObject locationJson(Location location) {
        if (location == null) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("world", location.getWorld() == null ? null : location.getWorld().getName());
        json.addProperty("x", location.getBlockX());
        json.addProperty("y", location.getBlockY());
        json.addProperty("z", location.getBlockZ());
        return json;
    }
}
