package de.raindancer.modules.hungergames.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Direction endpoints around people: gamemasters, teleporting a spectator, and the mannequin test
 * simulation.
 *
 * <p>Backed by small ports rather than concrete services — see {@link GameEndpoints}'s class note for
 * why. {@code Gamemasters}, {@code Spectator} and {@code Simulation} stand in for
 * {@code GamemasterStore}/{@code SpectatorService}/{@code MannequinSimService}, built alongside this
 * class by other hands in the same porting effort.
 */
final class AdminEndpoints implements ApiRouter.Module, IHungerGamesService {

    interface Gamemasters {
        List<String> names();

        Set<UUID> activeGamemasters();

        /** @return error messages; empty on success */
        List<String> addName(String actor, String name);

        List<String> removeName(String actor, String name);

        Optional<String> activate(Player player);

        Optional<String> deactivate(Player player);

        boolean isActive(UUID uuid);

        void setMode(Player player, GameMode mode);
    }

    interface Spectator {
        boolean teleportTo(Player spectator, UUID target);
    }

    interface Simulation {
        int mannequinCount();

        int aliveCount();

        int teamCount();

        boolean canSpawn();

        String spawn(Player admin, int count);

        Optional<String> eliminateOne(Player admin);

        int clear();
    }

    private final ApiSupport support;
    private final Gamemasters gamemasters;
    private final Spectator spectator;
    private final Simulation simulation;

    AdminEndpoints(ApiSupport support, Gamemasters gamemasters, Spectator spectator,
                   Simulation simulation) {
        this.support = support;
        this.gamemasters = gamemasters;
        this.spectator = spectator;
        this.simulation = simulation;
    }

    @Override
    public void register(ApiRouter router) {
        router.get("/api/gamemaster", "Gamemaster names and who is active", this::gamemaster);
        router.post("/api/gamemaster/names",
                "Add a gamemaster name — {\"name\": \"...\"}", this::gamemasterAdd);
        router.delete("/api/gamemaster/names/{name}", "Remove a gamemaster name", this::gamemasterRemove);
        router.post("/api/gamemaster/activate",
                "Turn on gamemaster mode — {\"player\": \"Name\"}", this::gamemasterActivate);
        router.post("/api/gamemaster/deactivate",
                "Turn off gamemaster mode — {\"player\": \"Name\"}", this::gamemasterDeactivate);
        router.post("/api/gamemaster/mode",
                "Set the game mode — {\"player\": \"Name\", \"mode\": \"SPECTATOR\"}",
                this::gamemasterMode);

        router.post("/api/spectator/teleport",
                "Teleport a spectator to a tribute — {\"player\", \"target\"}", this::teleport);

        router.get("/api/simulation", "Mannequin test simulation: state", this::simulation);
        router.post("/api/simulation/spawn",
                "Spawn mannequins — {\"admin\": \"Name\", \"count\": 12}", this::spawn);
        router.post("/api/simulation/eliminate",
                "Eliminate one mannequin — {\"admin\": \"Name\"}", this::eliminateMannequin);
        router.delete("/api/simulation", "Remove every mannequin and test team", this::clearSim);
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        // Whether gamemaster mode is enabled at all, and how a gamemaster is recognised, are read by the
        // Gamemasters port implementation the routes delegate to.
    }

    // ==================== gamemasters ====================

    private ApiResponse gamemaster(ApiRequest request) {
        JsonObject json = new JsonObject();
        JsonArray names = new JsonArray();
        gamemasters.names().forEach(names::add);
        json.add("names", names);
        JsonArray active = new JsonArray();
        for (UUID uuid : gamemasters.activeGamemasters()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", uuid.toString());
            entry.addProperty("name", support.session().participants().nameOf(uuid)
                    .orElseGet(() -> {
                        Player online = Bukkit.getPlayer(uuid);
                        return online != null ? online.getName() : "?";
                    }));
            entry.addProperty("online", Bukkit.getPlayer(uuid) != null);
            active.add(entry);
        }
        json.add("active", active);
        return ApiResponse.json(json);
    }

    private ApiResponse gamemasterAdd(ApiRequest request) {
        String name = request.requireString("name");
        List<String> errors = gamemasters.addName(ApiSupport.ACTOR, name);
        return errors.isEmpty() ? ApiResponse.ok() : ApiResponse.conflict(String.join("; ", errors));
    }

    private ApiResponse gamemasterRemove(ApiRequest request) {
        String name = request.param("name");
        List<String> errors = gamemasters.removeName(ApiSupport.ACTOR, name);
        return errors.isEmpty() ? ApiResponse.ok() : ApiResponse.conflict(String.join("; ", errors));
    }

    private ApiResponse gamemasterActivate(ApiRequest request) {
        Player player = support.requireOnlinePlayer(request.requireString("player"));
        return ApiResponse.okOrConflict(gamemasters.activate(player).orElse(null));
    }

    private ApiResponse gamemasterDeactivate(ApiRequest request) {
        Player player = support.requireOnlinePlayer(request.requireString("player"));
        return ApiResponse.okOrConflict(gamemasters.deactivate(player).orElse(null));
    }

    private ApiResponse gamemasterMode(ApiRequest request) {
        Player player = support.requireOnlinePlayer(request.requireString("player"));
        GameMode mode = request.requireEnum("mode", GameMode.class);
        if (!gamemasters.isActive(player.getUniqueId())) {
            return ApiResponse.conflict(player.getName() + " is not an active gamemaster");
        }
        gamemasters.setMode(player, mode);
        support.log(player.getName() + " set to game mode " + mode + " via the HTTP API");
        return ApiResponse.ok();
    }

    // ==================== spectator ====================

    private ApiResponse teleport(ApiRequest request) {
        Player spectatorPlayer = support.requireOnlinePlayer(request.requireString("player"));
        UUID target = support.resolvePlayerRef(request.requireString("target"));
        if (!spectator.teleportTo(spectatorPlayer, target)) {
            return ApiResponse.conflict("The target is not online, or not a living tribute");
        }
        return ApiResponse.ok();
    }

    // ==================== test simulation ====================

    private ApiResponse simulation(ApiRequest request) {
        JsonObject json = new JsonObject();
        json.addProperty("mannequins", simulation.mannequinCount());
        json.addProperty("alive", simulation.aliveCount());
        json.addProperty("teams", simulation.teamCount());
        json.addProperty("canSpawn", simulation.canSpawn());
        return ApiResponse.json(json);
    }

    private ApiResponse spawn(ApiRequest request) {
        Player admin = support.requireOnlinePlayer(request.requireString("admin"));
        int count = request.requireInt("count");
        if (count < 1) {
            return ApiResponse.badRequest("count must be >= 1");
        }
        if (!simulation.canSpawn()) {
            return ApiResponse.conflict("Mannequins can only be spawned before the start-up sequence "
                    + "(phase " + support.session().phase() + ")");
        }
        String message = simulation.spawn(admin, count);
        return ApiResponse.ok(json -> {
            json.addProperty("message", message);
            json.addProperty("mannequins", simulation.mannequinCount());
        });
    }

    private ApiResponse eliminateMannequin(ApiRequest request) {
        Player admin = support.requireOnlinePlayer(request.requireString("admin"));
        return ApiResponse.okOrConflict(simulation.eliminateOne(admin).orElse(null));
    }

    private ApiResponse clearSim(ApiRequest request) {
        int removed = simulation.clear();
        support.log(removed + " mannequin(s) removed via the HTTP API");
        return ApiResponse.ok(json -> json.addProperty("removed", removed));
    }
}
