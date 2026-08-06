package de.raindancer.modules.hungergames.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.core.social.team.TeamOutcome;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.store.GameEvents.MembershipCause;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Team endpoints: create, rename, recolour, delete, assign and remove members, set a captain, and
 * distribute teamless tributes at random.
 *
 * <p>Every mutation runs through {@code GameSession}, and therefore through the same rules as the
 * screens and commands — team size, colour exclusivity, the phase teams freeze at — and fires the same
 * events they do.
 */
final class TeamEndpoints implements ApiRouter.Module, IHungerGamesService {

    private final ApiSupport support;

    TeamEndpoints(ApiSupport support) {
        this.support = support;
    }

    @Override
    public void register(ApiRouter router) {
        router.get("/api/teams", "Teams with their colour, members and captain", this::list);
        router.get("/api/teams/colors", "Which colours are free and which are claimed", this::colours);
        router.post("/api/teams", "Create a team — {\"name\", \"color\"?}", this::create);
        router.delete("/api/teams", "Delete every team", this::deleteAll);
        router.post("/api/teams/random", "Distribute teamless tributes at random", this::random);
        router.get("/api/teams/{id}", "One team", this::detail);
        router.patch("/api/teams/{id}",
                "Change a team — {\"name\"?, \"color\"?, \"captain\"?}", this::patch);
        router.delete("/api/teams/{id}", "Delete a team", this::delete);
        router.post("/api/teams/{id}/members",
                "Assign a player — {\"uuid\"|\"player\"}", this::addMember);
        router.delete("/api/teams/{id}/members/{player}",
                "Remove a player from the team (UUID or name)", this::removeMember);
        router.post("/api/teams/{id}/captain",
                "Set the captain — {\"uuid\"|\"player\"}", this::setCaptain);
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        // Every rule a team operation is checked against — size, colour exclusivity, whether captains
        // exist at all — lives in TeamRules, which GameSession already asks. Nothing here reads a
        // setting of its own; declared anyway so a future one does not go unnoticed.
    }

    // ==================== reading ====================

    private ApiResponse list(ApiRequest request) {
        JsonArray array = new JsonArray();
        for (Team team : support.session().teams().all()) {
            array.add(support.teamJson(team));
        }
        return ApiResponse.json("teams", array);
    }

    private ApiResponse detail(ApiRequest request) {
        return team(request)
                .map(team -> ApiResponse.json(support.teamJson(team)))
                .orElseGet(() -> ApiResponse.notFound("Unknown team: " + request.param("id")));
    }

    private ApiResponse colours(ApiRequest request) {
        var teams = support.session().teams();
        var available = teams.availableColours();
        JsonObject json = new JsonObject();
        JsonArray availableJson = new JsonArray();
        JsonArray allJson = new JsonArray();
        for (TeamColour colour : TeamColour.values()) {
            allJson.add(colour.name());
            if (available.contains(colour)) {
                availableJson.add(colour.name());
            }
        }
        JsonObject claimed = new JsonObject();
        for (Team team : teams.all()) {
            claimed.addProperty(team.colour().name(), team.id().value());
        }
        json.add("available", availableJson);
        json.add("all", allJson);
        json.add("claimed", claimed);
        return ApiResponse.json(json);
    }

    // ==================== writing ====================

    private ApiResponse create(ApiRequest request) {
        String name = request.requireString("name");
        TeamColour colour = request.optEnum("color", TeamColour.class, null);
        var result = support.session().teamCreate(name, colour);
        if (!result.status().isSuccess()) {
            return ApiResponse.conflict(describe(result.status()));
        }
        Team team = result.team().orElseThrow();
        support.log("Team \"" + name + "\" created via the HTTP API");
        return ApiResponse.ok(json -> {
            json.addProperty("teamId", team.id().value());
            json.addProperty("color", team.colour().name());
        });
    }

    /**
     * Applies {@code name}, {@code color} and {@code captain}, in that order. If a step fails, the ones
     * before it stay in effect — the response names them under {@code applied}.
     */
    private ApiResponse patch(ApiRequest request) {
        Optional<Team> existing = team(request);
        if (existing.isEmpty()) {
            return ApiResponse.notFound("Unknown team: " + request.param("id"));
        }
        TeamId id = existing.get().id();
        List<String> applied = new ArrayList<>();

        if (request.has("name")) {
            String newName = request.requireString("name");
            TeamOutcome result = support.session().teamRename(id, newName);
            if (!result.isSuccess()) {
                return failure(result, applied);
            }
            support.log("Team " + id.value() + " renamed to \"" + newName + "\" via the HTTP API");
            applied.add("name");
        }
        if (request.has("color")) {
            TeamColour colour = request.requireEnum("color", TeamColour.class);
            TeamOutcome result = support.session().teamSetColour(id, colour);
            if (!result.isSuccess()) {
                return failure(result, applied);
            }
            support.log("Team " + id.value() + " set to colour " + colour + " via the HTTP API");
            applied.add("color");
        }
        if (request.has("captain")) {
            UUID captain = support.resolvePlayerRef(request.requireString("captain"));
            TeamOutcome result = support.session().teamSetCaptain(id, captain);
            if (!result.isSuccess()) {
                return failure(result, applied);
            }
            support.log(captain + " set as captain of " + id.value() + " via the HTTP API");
            applied.add("captain");
        }
        if (applied.isEmpty()) {
            return ApiResponse.badRequest(
                    "Expected at least one of the fields \"name\", \"color\", \"captain\"");
        }
        List<String> changed = List.copyOf(applied);
        return ApiResponse.ok(json -> json.add("applied", toArray(changed)));
    }

    private ApiResponse delete(ApiRequest request) {
        TeamId id = new TeamId(request.param("id"));
        TeamOutcome result = support.session().teamDelete(id);
        if (!result.isSuccess()) {
            return ApiResponse.conflict(describe(result));
        }
        support.log("Team " + id.value() + " deleted via the HTTP API");
        return ApiResponse.ok();
    }

    /** Deletes every team; members stay registered as teamless tributes. */
    private ApiResponse deleteAll(ApiRequest request) {
        List<Team> teams = support.session().teams().all();
        int deleted = 0;
        List<String> failures = new ArrayList<>();
        for (Team team : teams) {
            TeamOutcome result = support.session().teamDelete(team.id());
            if (result.isSuccess()) {
                deleted++;
            } else {
                failures.add(team.id().value() + ": " + describe(result));
            }
        }
        if (deleted == 0 && !failures.isEmpty()) {
            return ApiResponse.conflict(String.join("; ", failures));
        }
        support.log(deleted + " team(s) deleted via the HTTP API");
        int count = deleted;
        return ApiResponse.ok(json -> {
            json.addProperty("deleted", count);
            if (!failures.isEmpty()) {
                json.add("failed", toArray(failures));
            }
        });
    }

    private ApiResponse random(ApiRequest request) {
        int assigned = support.session().teamAssignRandomly();
        support.log(assigned + " tribute(s) randomly assigned to teams via the HTTP API");
        return ApiResponse.ok(json -> json.addProperty("assigned", assigned));
    }

    private ApiResponse addMember(ApiRequest request) {
        UUID uuid = support.requirePlayerUuid(request);
        TeamId team = new TeamId(request.param("id"));
        TeamOutcome result = support.session().teamAssign(uuid, team, MembershipCause.API);
        if (!result.isSuccess()) {
            return ApiResponse.conflict(describe(result));
        }
        support.log(uuid + " assigned to team " + team.value() + " via the HTTP API");
        return ApiResponse.ok();
    }

    private ApiResponse removeMember(ApiRequest request) {
        UUID uuid = support.resolvePlayerRef(request.param("player"));
        TeamOutcome result = support.session().teamRemovePlayer(uuid, MembershipCause.API);
        if (!result.isSuccess()) {
            return ApiResponse.conflict(describe(result));
        }
        support.log(uuid + " removed from their team via the HTTP API");
        return ApiResponse.ok();
    }

    private ApiResponse setCaptain(ApiRequest request) {
        UUID uuid = support.requirePlayerUuid(request);
        TeamId team = new TeamId(request.param("id"));
        TeamOutcome result = support.session().teamSetCaptain(team, uuid);
        if (!result.isSuccess()) {
            return ApiResponse.conflict(describe(result));
        }
        support.log(uuid + " set as captain of " + team.value() + " via the HTTP API");
        return ApiResponse.ok();
    }

    // ==================== internal ====================

    private Optional<Team> team(ApiRequest request) {
        return support.session().teams().team(new TeamId(request.param("id")));
    }

    /**
     * A team outcome as a sentence. {@code TeamOutcome.key()} gives a stable message key rather than
     * prose; without a wired-up sponsor/message catalogue for this outcome vocabulary yet, the enum's own
     * name — already written to read as a sentence, see {@link TeamOutcome}'s own javadoc — is used
     * directly. Once the module has a place to put a keyed sentence per outcome, this is the one line
     * that changes.
     */
    private static String describe(TeamOutcome outcome) {
        return switch (outcome) {
            case NO_SUCH_TEAM -> "No such team";
            case NAME_TAKEN -> "Another team is already called that";
            case COLOUR_TAKEN -> "Another team already has that colour";
            case NO_COLOUR_FREE -> "Every colour is taken";
            case TEAM_FULL -> "That team is full";
            case TOO_MANY_TEAMS -> "There are already as many teams as allowed";
            case FROZEN -> "Teams cannot be changed right now";
            case NOT_ELIGIBLE -> "That player is not eligible to be on a team here";
            case ALREADY_IN_THAT_TEAM -> "Already on that team";
            case MUST_LEAVE_FIRST -> "That player must leave their current team first";
            case NOT_IN_A_TEAM -> "Not on a team";
            case NO_CAPTAINS_HERE -> "Captains are disabled";
            case CAPTAIN_NOT_A_MEMBER -> "The proposed captain is not on that team";
            case SUCCESS -> "";
        };
    }

    /**
     * A patch that stopped partway.
     *
     * <p>{@code 409} with the refusal, and — the part that matters — the fields that <em>did</em> go
     * through before it. A patch applies name, colour and captain in order and does not roll back, so a
     * bare error would leave the caller unable to tell whether the rename happened. Something reading only
     * the status code still sees a failure; something reading the body can tell what the team looks like
     * now without re-fetching it.
     */
    private static ApiResponse failure(TeamOutcome outcome, List<String> applied) {
        ApiResponse response = ApiResponse.conflict(describe(outcome));
        response.body().addProperty("outcome", outcome.key());
        response.body().add("applied", toArray(applied));
        return response;
    }

    private static JsonArray toArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
