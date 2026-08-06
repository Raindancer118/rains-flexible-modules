package de.raindancer.modules.hungergames.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * Gamemaster-triggered events: the deathmatch, Capitol supply drops, sponsor tokens and beacons, monster
 * waves, broadcasts, and testing a cue.
 *
 * <p>Backed by small ports rather than concrete services for the same reason as {@link GameEndpoints} —
 * see that class's note. {@code Deathmatch}, {@code SupplyDrops}, {@code Sponsors} and {@code MonsterWaves}
 * stand in for the Bukkit-facing services this module builds alongside this class; {@code SoundEffects}
 * stands in for asking Core's {@code ui.effect.Effects} for a named cue, which both {@code /api/sounds}
 * and {@code /api/effects} now mean, the old plugin's separate sound and particle catalogues having become
 * one idea — a cue, asked for by meaning — in Core.
 */
final class EventEndpoints implements ApiRouter.Module, IHungerGamesService {

    interface Deathmatch {
        String state();

        String statusLine();

        /** @return an error message, or empty on success */
        Optional<String> start(String actor);

        Optional<String> cancel(String actor);
    }

    /** One entry in the supply-drop schedule. */
    record SupplyDropSlot(int index, long afterSeconds, boolean triggered) {
    }

    interface SupplyDrops {
        String statusLine();

        List<SupplyDropSlot> schedule();

        Optional<String> triggerNow(String actor);
    }

    interface Sponsors {
        boolean tokensEnabled();

        boolean beaconsEnabled();

        String statusLine();

        List<Location> activeBeacons();

        void giveManually(String actor, Player target, int amount);

        int clearTokens(Player target);

        Optional<String> createBeacon(Location location, String actor);

        int removeAllBeacons(String actor);
    }

    interface MonsterWaves {
        int activeSeries();

        String defaultMob();

        int defaultCount();

        int defaultWaves();

        int defaultInterval();

        Optional<String> start(Location centre, String mob, int count, int waves, int interval,
                                String actor);

        int stopAll();
    }

    /** Asking Core's Effects for a named cue, by key — see the class note. */
    interface SoundEffects {
        List<String> knownCues();

        /** @return whether the cue existed and was played */
        boolean test(Player player, String cueKey);
    }

    private final ApiSupport support;
    private final Deathmatch deathmatch;
    private final SupplyDrops supplyDrops;
    private final Sponsors sponsors;
    private final MonsterWaves monsterWaves;
    private final SoundEffects soundEffects;

    EventEndpoints(ApiSupport support, Deathmatch deathmatch, SupplyDrops supplyDrops, Sponsors sponsors,
                   MonsterWaves monsterWaves, SoundEffects soundEffects) {
        this.support = support;
        this.deathmatch = deathmatch;
        this.supplyDrops = supplyDrops;
        this.sponsors = sponsors;
        this.monsterWaves = monsterWaves;
        this.soundEffects = soundEffects;
    }

    @Override
    public void register(ApiRouter router) {
        router.get("/api/deathmatch", "Deathmatch state", this::deathmatch);
        router.post("/api/deathmatch/start", "Trigger the deathmatch (warning phase)", request ->
                ApiResponse.okOrConflict(deathmatch.start(ApiSupport.ACTOR).orElse(null)));
        router.post("/api/deathmatch/cancel", "Cancel the warning phase", request ->
                ApiResponse.okOrConflict(deathmatch.cancel(ApiSupport.ACTOR).orElse(null)));

        router.get("/api/supplydrop", "Schedule and status of supply drops", this::supplyDrops);
        router.post("/api/supplydrop", "Trigger a drop manually", request ->
                ApiResponse.okOrConflict(supplyDrops.triggerNow(ApiSupport.ACTOR).orElse(null)));

        router.get("/api/sponsor", "Tokens, beacons and status", this::sponsor);
        router.post("/api/sponsor/give",
                "Give tokens — {\"player\": \"Name\", \"amount\": 1}", this::sponsorGive);
        router.post("/api/sponsor/clear",
                "Take a player's tokens — {\"player\": \"Name\"}", this::sponsorClear);
        router.post("/api/sponsor/beacons",
                "Place a sponsor beacon — {\"player\"|\"world\",\"x\",\"y\",\"z\"}", this::beaconCreate);
        router.delete("/api/sponsor/beacons", "Remove every sponsor beacon", this::beaconsClear);

        router.get("/api/monsterwaves", "Active series and the defaults", this::waves);
        router.post("/api/monsterwaves", "Start a wave series — {\"player\"|coordinates, "
                + "\"mob\"?, \"count\"?, \"waves\"?, \"interval\"?}", this::wavesStart);
        router.delete("/api/monsterwaves", "Stop every wave series", this::wavesStop);

        router.post("/api/announce", "Broadcast — {\"message\": \"<gold>Text\"}", this::announce);

        router.get("/api/sounds", "Every known cue name", request ->
                ApiResponse.json("sounds", toArray(soundEffects.knownCues())));
        router.post("/api/sounds/test",
                "Test a cue — {\"player\": \"Name\", \"sound\": \"...\"}", this::soundTest);
        router.get("/api/effects", "Every known cue name", request ->
                ApiResponse.json("effects", toArray(soundEffects.knownCues())));
        router.post("/api/effects/test",
                "Test a cue — {\"player\": \"Name\", \"effect\": \"...\"}", this::effectTest);
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        // Every setting behind these routes -- whether the deathmatch may be triggered, whether supply
        // drops or sponsor tokens run at all -- is read by the port implementation the routes delegate
        // to, not by this class. Declared empty rather than omitted, per IHungerGamesService.
    }

    // ==================== deathmatch / drops ====================

    private ApiResponse deathmatch(ApiRequest request) {
        JsonObject json = new JsonObject();
        json.addProperty("state", deathmatch.state());
        json.addProperty("status", deathmatch.statusLine());
        return ApiResponse.json(json);
    }

    private ApiResponse supplyDrops(ApiRequest request) {
        JsonObject json = new JsonObject();
        json.addProperty("status", supplyDrops.statusLine());
        JsonArray schedule = new JsonArray();
        for (SupplyDropSlot slot : supplyDrops.schedule()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("index", slot.index());
            entry.addProperty("afterSeconds", slot.afterSeconds());
            entry.addProperty("triggered", slot.triggered());
            schedule.add(entry);
        }
        json.add("schedule", schedule);
        return ApiResponse.json(json);
    }

    // ==================== sponsors ====================

    private ApiResponse sponsor(ApiRequest request) {
        JsonObject json = new JsonObject();
        json.addProperty("tokensEnabled", sponsors.tokensEnabled());
        json.addProperty("beaconsEnabled", sponsors.beaconsEnabled());
        json.addProperty("status", sponsors.statusLine());
        JsonArray beacons = new JsonArray();
        for (Location location : sponsors.activeBeacons()) {
            beacons.add(ApiSupport.locationJson(location));
        }
        json.add("beacons", beacons);
        return ApiResponse.json(json);
    }

    private ApiResponse sponsorGive(ApiRequest request) {
        Player target = support.requireOnlinePlayer(request.requireString("player"));
        int amount = Math.max(1, request.optInt("amount", 1));
        sponsors.giveManually(ApiSupport.ACTOR, target, amount);
        return ApiResponse.ok(json -> {
            json.addProperty("player", target.getName());
            json.addProperty("amount", amount);
        });
    }

    private ApiResponse sponsorClear(ApiRequest request) {
        Player target = support.requireOnlinePlayer(request.requireString("player"));
        int removed = sponsors.clearTokens(target);
        support.log(removed + " token(s) taken from " + target.getName() + " via the HTTP API");
        return ApiResponse.ok(json -> {
            json.addProperty("player", target.getName());
            json.addProperty("removed", removed);
        });
    }

    private ApiResponse beaconCreate(ApiRequest request) {
        Location location = support.requireLocation(request);
        Optional<String> error = sponsors.createBeacon(location, ApiSupport.ACTOR);
        if (error.isPresent()) {
            return ApiResponse.conflict(error.get());
        }
        return ApiResponse.ok(json -> json.add("location", ApiSupport.locationJson(location)));
    }

    private ApiResponse beaconsClear(ApiRequest request) {
        int removed = sponsors.removeAllBeacons(ApiSupport.ACTOR);
        return ApiResponse.ok(json -> json.addProperty("removed", removed));
    }

    // ==================== monster waves ====================

    private ApiResponse waves(ApiRequest request) {
        JsonObject json = new JsonObject();
        json.addProperty("activeSeries", monsterWaves.activeSeries());
        JsonObject defaults = new JsonObject();
        defaults.addProperty("mob", monsterWaves.defaultMob());
        defaults.addProperty("count", monsterWaves.defaultCount());
        defaults.addProperty("waves", monsterWaves.defaultWaves());
        defaults.addProperty("interval", monsterWaves.defaultInterval());
        json.add("defaults", defaults);
        return ApiResponse.json(json);
    }

    private ApiResponse wavesStart(ApiRequest request) {
        Location centre = support.requireLocation(request);
        String mob = request.optString("mob", monsterWaves.defaultMob());
        int count = request.optInt("count", monsterWaves.defaultCount());
        int waves = request.optInt("waves", monsterWaves.defaultWaves());
        int interval = request.optInt("interval", monsterWaves.defaultInterval());
        if (count < 1 || waves < 1 || interval < 1) {
            return ApiResponse.badRequest("count, waves and interval must all be >= 1");
        }
        Optional<String> error = monsterWaves.start(centre, mob, count, waves, interval, ApiSupport.ACTOR);
        if (error.isPresent()) {
            return ApiResponse.conflict(error.get());
        }
        return ApiResponse.ok(json -> {
            json.addProperty("mob", mob);
            json.addProperty("count", count);
            json.addProperty("waves", waves);
            json.addProperty("interval", interval);
            json.add("location", ApiSupport.locationJson(centre));
        });
    }

    private ApiResponse wavesStop(ApiRequest request) {
        int stopped = monsterWaves.stopAll();
        support.log(stopped + " monster wave series stopped via the HTTP API");
        return ApiResponse.ok(json -> json.addProperty("stopped", stopped));
    }

    // ==================== announcements, cues ====================

    private ApiResponse announce(ApiRequest request) {
        String message = request.requireString("message");
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(message));
        support.log("Broadcast via the HTTP API: " + message);
        return ApiResponse.ok();
    }

    private ApiResponse soundTest(ApiRequest request) {
        Player player = support.requireOnlinePlayer(request.requireString("player"));
        String cue = request.requireString("sound");
        if (!soundEffects.test(player, cue)) {
            return ApiResponse.badRequest("Unknown cue: " + cue);
        }
        return ApiResponse.ok();
    }

    private ApiResponse effectTest(ApiRequest request) {
        Player player = support.requireOnlinePlayer(request.requireString("player"));
        String cue = request.requireString("effect");
        if (!soundEffects.test(player, cue)) {
            return ApiResponse.badRequest("Unknown cue: " + cue);
        }
        return ApiResponse.ok();
    }

    // ==================== internal ====================

    private static JsonArray toArray(Iterable<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
