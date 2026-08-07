package de.raindancer.modules.hungergames.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.Participant;
import de.raindancer.modules.hungergames.model.Winner;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Read endpoints for the round's status and its tributes, plus the handful of tribute-management
 * mutations that need nothing beyond {@link de.raindancer.modules.hungergames.store.GameSession}:
 * registering, revoking, reviving and eliminating.
 */
final class StatusEndpoints implements ApiRouter.Module, IHungerGamesService {

    private final ApiSupport support;

    StatusEndpoints(ApiSupport support) {
        this.support = support;
    }

    @Override
    public void register(ApiRouter router) {
        router.get("/api/status", "Phase, tributes alive, and the round's headline numbers", this::status);
        router.get("/api/participants", "Every tribute with their status, team and kills",
                this::participants);
        router.get("/api/participants/{player}", "One tribute (UUID or name)", this::detail);
        router.get("/api/players", "Every online player, with position and game mode",
                this::onlinePlayers);

        router.post("/api/whitelist", "Register a tribute — {\"uuid\", \"name\"?}", this::whitelistAdd);
        router.delete("/api/whitelist/{player}", "Remove a tribute from the tournament",
                this::whitelistRemove);
        router.post("/api/revive", "Undo an elimination — {\"uuid\"|\"player\"}", this::revive);
        router.post("/api/eliminate",
                "Eliminate a tribute — {\"uuid\"|\"player\", \"killer\"?}", this::eliminate);
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        // Nothing here reads a setting today — status and roster management ask the session directly.
        // Declared anyway: the day a "hide eliminated tributes from /api/participants" toggle is added,
        // this is where it is read from, and a service that had never taken settings would keep whatever
        // it started with for the rest of the tournament.
    }

    // ==================== status ====================

    private ApiResponse status(ApiRequest request) {
        var session = support.session();
        JsonObject json = new JsonObject();
        GamePhase phase = session.phase();
        json.addProperty("phase", phase.name());
        json.addProperty("aliveCount", session.participants().aliveCount());
        json.addProperty("participantCount", session.participants().all().size());
        json.addProperty("teamCount", session.teams().all().size());
        json.addProperty("readOnly", support.settings().apiReadOnly());
        session.winner().ifPresentOrElse(
                winner -> json.addProperty("winner", describeWinner(winner)),
                () -> json.add("winner", null));
        return ApiResponse.json(json);
    }

    private ApiResponse participants(ApiRequest request) {
        JsonArray array = new JsonArray();
        for (Participant participant : support.session().participants().all()) {
            array.add(support.participantJson(participant));
        }
        return ApiResponse.json("participants", array);
    }

    private ApiResponse detail(ApiRequest request) {
        String key = request.param("player");
        Optional<Participant> found = support.session().participants().all().stream()
                .filter(p -> p.lastKnownName().equalsIgnoreCase(key)
                        || p.uuid().toString().equalsIgnoreCase(key))
                .findFirst();
        return found.map(participant -> ApiResponse.json(support.participantJson(participant)))
                .orElseGet(() -> ApiResponse.notFound("\"" + key + "\" is not a registered tribute"));
    }

    private ApiResponse onlinePlayers(ApiRequest request) {
        JsonArray array = new JsonArray();
        for (Player player : Bukkit.getOnlinePlayers()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", player.getUniqueId().toString());
            entry.addProperty("name", player.getName());
            entry.addProperty("gameMode", player.getGameMode().name());
            entry.addProperty("health", player.getHealth());
            entry.addProperty("participant", support.session().isWhitelisted(player.getUniqueId()));
            entry.add("location", ApiSupport.locationJson(player.getLocation()));
            array.add(entry);
        }
        return ApiResponse.json("players", array);
    }

    // ==================== managing tributes ====================

    private ApiResponse whitelistAdd(ApiRequest request) {
        UUID uuid = request.requireUuid("uuid");
        String requestedName = request.optString("name", "");
        String name = !requestedName.isEmpty() ? requestedName
                : Objects.requireNonNullElse(onlineNameOf(uuid), uuid.toString().substring(0, 8));
        if (!support.session().whitelistAdd(uuid, name)) {
            return ApiResponse.conflict("Already registered as a tribute");
        }
        support.log(name + " (" + uuid + ") registered via the HTTP API");
        return ApiResponse.ok(json -> {
            json.addProperty("uuid", uuid.toString());
            json.addProperty("name", name);
        });
    }

    private ApiResponse whitelistRemove(ApiRequest request) {
        UUID uuid = support.resolvePlayerRef(request.param("player"));
        if (!support.session().whitelistRemove(uuid)) {
            return ApiResponse.conflict("No such registered tribute: " + uuid);
        }
        support.log(uuid + " removed from the tournament via the HTTP API");
        return ApiResponse.ok();
    }

    private ApiResponse revive(ApiRequest request) {
        UUID uuid = support.requirePlayerUuid(request);
        if (!support.session().revive(uuid)) {
            return ApiResponse.conflict("Not eliminated, or not a tribute: " + uuid);
        }
        // Nothing about the player is undone here. GameSession.revive fires the event
        // HungerGamesWiring's own phase watcher answers by calling SpectatorService.restoreFromElimination
        // — one door for every way a revive can happen, so this endpoint cannot forget the compass.
        support.log(uuid + " revived via the HTTP API");
        return ApiResponse.ok();
    }

    private ApiResponse eliminate(ApiRequest request) {
        UUID uuid = support.requirePlayerUuid(request);
        UUID killer = request.has("killer")
                ? support.resolvePlayerRef(request.requireString("killer")) : null;
        if (!support.session().eliminate(uuid, killer)) {
            return ApiResponse.conflict("Not alive, or not a tribute: " + uuid);
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            support.spectators().makeSpectator(player);
        }
        support.log(uuid + " eliminated via the HTTP API"
                + (killer == null ? "" : " (killer: " + killer + ")"));
        return ApiResponse.ok();
    }

    // ==================== internal ====================

    private static String onlineNameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        return online == null ? null : online.getName();
    }

    private String describeWinner(Winner winner) {
        var session = support.session();
        return switch (winner) {
            case Winner.Solo solo -> session.participants().nameOf(solo.uuid()).orElse("?");
            case Winner.Team team -> session.teams().team(team.teamId())
                    .map(de.raindancer.core.social.team.Team::name).orElse(team.teamId().value());
            case Winner.None none -> "none";
        };
    }
}
