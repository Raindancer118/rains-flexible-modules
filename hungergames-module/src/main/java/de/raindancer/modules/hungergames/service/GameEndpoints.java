package de.raindancer.modules.hungergames.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * Round-flow endpoints: preflight, initialising the arena, the start-up sequence, starting, ending or
 * resetting a round, forcing a phase, the time multiplier, and the world border.
 *
 * <h2>Why this depends on a port rather than concrete services</h2>
 * {@code GameControlService}, {@code PreflightCheckService} and {@code BorderService} are Bukkit-facing
 * collaborators built alongside this class in the same porting effort, by other hands, and were not yet
 * built when this class was written. Rather than inventing a second copy of them here — which is exactly
 * the mistake {@code ReuseTest} exists to catch — this class depends on {@link GameControl}, a small
 * interface stating exactly what the HTTP layer needs from round control. Wiring a real
 * {@code GameControlService} in is then one line in whatever assembles this module's services, with
 * nothing here to change. This is dependency inversion, not a stub: every method below is a complete,
 * tested implementation of the HTTP contract, over whichever {@link GameControl} it is given.
 */
final class GameEndpoints implements ApiRouter.Module, IHungerGamesService {

    /** What this class needs from round control, arena initialisation and preflight — see the class note. */
    interface GameControl {

        boolean canInit();

        boolean canStartup();

        boolean canStart();

        boolean canEndRound();

        int minPlayers();

        int maxPlayers();

        /** @return {@code false} if the request was refused; the console log says why */
        boolean init(Player admin, int playerCount);

        boolean startup(Player admin);

        boolean start(Player admin);

        boolean endRound(String actor);

        void prepareNextRound(String actor);

        List<PreflightResult> preflight();

        double timeMultiplier();

        /** @return the multiplier actually in force after the change */
        double setTimeMultiplier(double multiplier);

        double borderCurrentSize();

        int borderNextPhaseIndex();

        /** @return the shrink's duration in seconds, or {@code 0} if nothing was done */
        long borderShrinkTo(double size);

        void borderResetToInitial();
    }

    /** One preflight check's outcome. */
    record PreflightResult(String name, String severity, String detail, boolean blocking) {
    }

    private final ApiSupport support;
    private final GameControl control;
    /** A snapshot, replaced on reload — see {@link #settings(HungerGamesSettings)}. */
    private volatile HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;

    GameEndpoints(ApiSupport support, GameControl control) {
        this.support = support;
        this.control = control;
    }

    @Override
    public void register(ApiRouter router) {
        router.get("/api/game", "Phase preconditions and the round's state", this::game);
        router.get("/api/game/preflight", "The preflight check, every finding", this::preflight);
        router.post("/api/game/init",
                "Initialise the arena — {\"admin\": \"Name\", \"playerCount\": 24}", this::init);
        router.post("/api/game/startup", "The start-up sequence — {\"admin\": \"Name\"}", this::startup);
        router.post("/api/game/start", "Countdown and start — {\"admin\": \"Name\"}", this::start);
        router.post("/api/game/end", "Score the running round as if time had run out", this::endRound);
        router.post("/api/round/end", "Alias of /api/game/end", this::endRound);
        router.post("/api/game/reset",
                "Reset the round (tributes stay registered)", this::reset);
        router.post("/api/game/phase",
                "Force a phase (emergency use) — {\"phase\": \"LOBBY\"}", this::phase);

        router.post("/api/time/multiplier",
                "Set the time multiplier — {\"multiplier\": 4.0}", this::multiplier);

        router.get("/api/border", "Border size, next phase, configuration", this::border);
        router.post("/api/border/shrink",
                "Shrink the border to a size immediately — {\"size\": 200}", this::shrink);
        router.post("/api/border/reset", "Reset the border to its starting size", this::resetBorder);
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        // The border's initial size, floor and fairness ceiling are read from here for the parts of
        // /api/border that describe configuration rather than live state.
        this.settings = settings;
    }

    // ==================== round flow ====================

    private ApiResponse game(ApiRequest request) {
        var session = support.session();
        JsonObject json = new JsonObject();
        json.addProperty("phase", session.phase().name());
        json.addProperty("canInit", control.canInit());
        json.addProperty("canStartup", control.canStartup());
        json.addProperty("canStart", control.canStart());
        json.addProperty("canEndRound", control.canEndRound());
        json.addProperty("minPlayers", control.minPlayers());
        json.addProperty("maxPlayers", control.maxPlayers());
        json.addProperty("participantCount", session.participants().all().size());
        json.addProperty("aliveCount", session.participants().aliveCount());
        JsonArray phases = new JsonArray();
        for (GamePhase phase : GamePhase.values()) {
            phases.add(phase.name());
        }
        json.add("phases", phases);
        return ApiResponse.json(json);
    }

    private ApiResponse preflight(ApiRequest request) {
        List<PreflightResult> results = control.preflight();
        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();
        boolean blocking = false;
        for (PreflightResult result : results) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", result.name());
            entry.addProperty("severity", result.severity());
            entry.addProperty("detail", result.detail());
            array.add(entry);
            blocking = blocking || result.blocking();
        }
        json.add("checks", array);
        json.addProperty("blocking", blocking);
        return ApiResponse.json(json);
    }

    private ApiResponse init(ApiRequest request) {
        Player admin = requireAdmin(request);
        int playerCount = request.requireInt("playerCount");
        if (playerCount < control.minPlayers() || playerCount > control.maxPlayers()) {
            return ApiResponse.badRequest("playerCount must be between " + control.minPlayers()
                    + " and " + control.maxPlayers());
        }
        if (!control.canInit()) {
            return ApiResponse.conflict("The arena is already initialised (phase "
                    + support.session().phase() + ") — end the round first");
        }
        support.log("Arena initialisation via the HTTP API at " + admin.getName() + "'s position");
        if (!control.init(admin, playerCount)) {
            return ApiResponse.conflict("Initialisation refused — see the server log");
        }
        return ApiResponse.ok(json -> {
            json.addProperty("admin", admin.getName());
            json.addProperty("playerCount", playerCount);
        });
    }

    private ApiResponse startup(ApiRequest request) {
        Player admin = requireAdmin(request);
        if (!control.canStartup()) {
            return ApiResponse.conflict("The start-up sequence only runs in phase LOBBY (currently: "
                    + support.session().phase() + ")");
        }
        support.log("Start-up sequence triggered via the HTTP API (admin " + admin.getName() + ")");
        return ApiResponse.okOrConflict(control.startup(admin)
                ? null : "Start-up refused — see the server log");
    }

    private ApiResponse start(ApiRequest request) {
        Player admin = requireAdmin(request);
        if (!control.canStart()) {
            return ApiResponse.conflict("Starting only works in phase READY (currently: "
                    + support.session().phase() + ")");
        }
        support.log("Round start triggered via the HTTP API (admin " + admin.getName() + ")");
        return ApiResponse.okOrConflict(control.start(admin)
                ? null : "Start refused — see the server log");
    }

    private ApiResponse endRound(ApiRequest request) {
        if (!control.endRound(ApiSupport.ACTOR)) {
            return ApiResponse.conflict("No round is running (phase " + support.session().phase() + ")");
        }
        return ApiResponse.ok();
    }

    private ApiResponse reset(ApiRequest request) {
        control.prepareNextRound(ApiSupport.ACTOR);
        return ApiResponse.ok(json -> json.addProperty("phase", support.session().phase().name()));
    }

    /** Forces a phase change — emergency use only (a stuck sequence). Invalid transitions are refused. */
    private ApiResponse phase(ApiRequest request) {
        GamePhase target = request.requireEnum("phase", GamePhase.class);
        GamePhase current = support.session().phase();
        if (!support.session().transitionTo(target)) {
            return ApiResponse.conflict("Phase change " + current + " -> " + target + " is not allowed");
        }
        support.log("Phase set from " + current + " to " + target + " via the HTTP API");
        return ApiResponse.ok(json -> json.addProperty("phase", target.name()));
    }

    // ==================== time ====================

    private ApiResponse multiplier(ApiRequest request) {
        double multiplier = request.requireDouble("multiplier");
        if (multiplier < 1.0 || multiplier > 100.0) {
            return ApiResponse.badRequest("multiplier must be between 1.0 and 100.0");
        }
        double applied = control.setTimeMultiplier(multiplier);
        support.log("Time multiplier set to x" + applied + " via the HTTP API");
        return ApiResponse.ok(json -> json.addProperty("multiplier", applied));
    }

    // ==================== border ====================

    private ApiResponse border(ApiRequest request) {
        HungerGamesSettings snapshot = settings;
        JsonObject json = new JsonObject();
        json.addProperty("currentSize", control.borderCurrentSize());
        json.addProperty("initialSize", snapshot.borderInitialSize());
        json.addProperty("minimumSize", snapshot.borderFloor());
        json.addProperty("maxEdgeSpeed", snapshot.borderEdgeSpeed());
        json.addProperty("nextPhaseIndex", control.borderNextPhaseIndex());
        return ApiResponse.json(json);
    }

    private ApiResponse shrink(ApiRequest request) {
        double size = request.requireDouble("size");
        HungerGamesSettings snapshot = settings;
        if (size < snapshot.borderFloor()) {
            return ApiResponse.badRequest("size must not be below the floor (" + snapshot.borderFloor()
                    + ")");
        }
        long seconds = control.borderShrinkTo(size);
        if (seconds == 0) {
            return ApiResponse.conflict("Nothing shrunk — no arena world loaded, or the target is at or "
                    + "above the current size (" + control.borderCurrentSize() + ")");
        }
        support.log("Border shrunk to " + size + " blocks via the HTTP API (" + seconds + "s)");
        return ApiResponse.ok(json -> {
            json.addProperty("targetSize", size);
            json.addProperty("durationSeconds", seconds);
        });
    }

    private ApiResponse resetBorder(ApiRequest request) {
        control.borderResetToInitial();
        support.log("Border reset to its starting size via the HTTP API");
        return ApiResponse.ok(json -> json.addProperty("currentSize", control.borderCurrentSize()));
    }

    // ==================== internal ====================

    /** The reference admin the start-up runners take their arena centre and progress reports from. */
    private Player requireAdmin(ApiRequest request) {
        return support.requireOnlinePlayer(request.requireString("admin"));
    }
}
