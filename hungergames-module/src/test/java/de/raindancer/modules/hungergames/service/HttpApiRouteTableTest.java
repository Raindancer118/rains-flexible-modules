package de.raindancer.modules.hungergames.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The whole API surface, looked at once.
 *
 * <h2>Why a test about the table rather than about the endpoints</h2>
 * Every endpoint class registers its own routes and none of them can see the others. That is the right
 * arrangement and it has one blind spot: two modules can register the same path, and the router keeps
 * both. The second one is then unreachable — no error, no warning, and the endpoint's own test passes,
 * because it was never asked whether anybody else wanted its path.
 *
 * <p>The same goes for the two flags every route carries. A write route that forgot to say it writes is
 * not covered by {@code api.read-only}, which means the switch a server flipped to make a public dashboard
 * safe silently does not apply to it. A route that touches the world and forgot to say so runs on an HTTP
 * thread, where touching a player is undefined behaviour on Paper and a hard crash on Folia. Both are
 * invisible to every other test in the suite, and both are decided in one line per route.
 *
 * <p>Built through {@link HttpApiService#route} with mocked collaborators. Nothing is called — the handlers
 * are never invoked, only counted and inspected — so the mocks need no behaviour at all.
 */
class HttpApiRouteTableTest {

    /**
     * The routing table, exactly as a running server would have it.
     *
     * <p>Every collaborator mocked with {@code RETURNS_DEEP_STUBS} off and no stubbing: registration
     * happens in a constructor and a {@code register} call, neither of which asks a collaborator anything.
     * If that stops being true this test will fail with a null pointer rather than passing on a partial
     * table, which is the right way round.
     */
    private static ApiRouter table() {
        ApiSupport support = new ApiSupport(null, mock(LogChannel.class), mock(SpectatorService.class),
                HungerGamesSettings.DEFAULTS);
        @SuppressWarnings("unchecked")
        de.raindancer.core.data.settings.SettingsStore<HungerGamesSettings> store =
                mock(de.raindancer.core.data.settings.SettingsStore.class);

        return HttpApiService.route(support, new HttpApiService.Wiring(
                mock(GameEndpoints.GameControl.class),
                mock(EventEndpoints.Deathmatch.class),
                mock(EventEndpoints.SupplyDrops.class),
                mock(EventEndpoints.Sponsors.class),
                mock(EventEndpoints.MonsterWaves.class),
                mock(EventEndpoints.SoundEffects.class),
                mock(LootEndpoints.Catalogue.class),
                mock(AdminEndpoints.Gamemasters.class),
                mock(AdminEndpoints.Spectator.class),
                mock(AdminEndpoints.Simulation.class),
                store));
    }

    @Test
    @DisplayName("no two endpoints claim the same method and path")
    void thereAreNoDuplicates() {
        Map<String, String> claimed = new LinkedHashMap<>();
        List<String> clashes = new ArrayList<>();

        for (ApiRouter.Route route : table().routes()) {
            String key = route.method() + " " + route.pattern();
            String first = claimed.putIfAbsent(key, route.description());
            if (first != null) {
                clashes.add(key + " is registered twice: \"" + first + "\" and \""
                        + route.description() + "\"");
            }
        }

        assertThat(clashes)
                .as("the router keeps both and answers with the first, so the second endpoint is "
                        + "unreachable — with no error anywhere and its own test still passing")
                .isEmpty();
    }

    @Test
    @DisplayName("a path that two patterns could match is not ambiguous by accident")
    void thereAreNoAmbiguousPatterns() {
        // /api/teams/random and /api/teams/{id} both match "random". That is fine and intended — the
        // router prefers the more literal pattern — but it only works if the literal one is registered
        // and reachable. Checked by asking the router, rather than by reading the registration order.
        ApiRouter router = table();

        for (ApiRouter.Route route : router.routes()) {
            if (route.pattern().contains("{")) {
                continue;
            }
            var matched = router.match(route.method(), route.pattern());
            assertThat(matched)
                    .as("%s %s is registered but the router resolves that path to something else — a "
                            + "literal path losing to a parameter pattern is an endpoint that exists and "
                            + "cannot be called", route.method(), route.pattern())
                    .isPresent();
            assertThat(matched.get().route().pattern()).isEqualTo(route.pattern());
        }
    }

    @Test
    @DisplayName("every route that changes something says so, and every GET does not")
    void theWriteFlagIsRight() {
        for (ApiRouter.Route route : table().routes()) {
            boolean changesThings = !route.method().equals("GET");

            assertThat(route.write())
                    .as("%s %s: a write route that does not declare itself is not covered by "
                            + "api.read-only, so the switch a server flipped to make a public dashboard "
                            + "safe silently does not apply to it — and a GET marked as a write is "
                            + "refused on a read-only server for no reason",
                            route.method(), route.pattern())
                    .isEqualTo(changesThings);
        }
    }

    @Test
    @DisplayName("every route runs on the server thread, which is the safe answer and the true one")
    void everythingHopsOntoTheServerThread() {
        // Written expecting a mix and finding none: every endpoint in this API reads or writes the
        // session, so every route hops. That is the correct answer rather than an oversight — the
        // session is not thread-safe, and a route answering from an HTTP thread would be reading a
        // participant list while the round mutates it.
        //
        // The cost is real and accepted: every request waits for a tick. The alternative — deciding
        // per route which reads happen to be safe today — is a decision that stops being true the
        // first time an endpoint grows a second line.
        //
        // The async helpers on ApiRouter are therefore currently unused. They stay because the index
        // and any future endpoint that genuinely touches nothing of the server's belong on them, and
        // this assertion is what will notice when one starts being used.
        Set<Boolean> sides = new LinkedHashSet<>();
        table().routes().forEach(route -> sides.add(route.mainThread()));

        assertThat(sides)
                .as("a route that answers off the server thread must be a deliberate, argued exception "
                        + "— if this now contains false, the route that did it needs a comment saying "
                        + "why it touches nothing the round can change underneath it")
                .containsExactly(true);
    }

    @Test
    @DisplayName("every route has a description a person could read")
    void everythingIsDocumented() {
        for (ApiRouter.Route route : table().routes()) {
            assertThat(route.description())
                    .as("GET /api is the only documentation this API has, and an endpoint with a blank "
                            + "description is one nobody can find out the purpose of: %s %s",
                            route.method(), route.pattern())
                    .isNotBlank()
                    // Low on purpose. "One team" is eight characters and says everything there is to
                    // say; a threshold that failed it would be a threshold people pad descriptions to
                    // satisfy, which is worse than a short accurate one.
                    .hasSizeGreaterThan(4);
        }
    }

    @Test
    @DisplayName("every path is under /api and lower case")
    void thePathsAreConsistent() {
        for (ApiRouter.Route route : table().routes()) {
            assertThat(route.pattern())
                    .as("the server context is /api, so a path outside it is registered and never "
                            + "reached: %s", route.pattern())
                    .startsWith("/api");
            assertThat(route.pattern())
                    .as("path matching is case-insensitive but a mixed-case pattern in the /api index "
                            + "reads as though case mattered: %s", route.pattern())
                    .isEqualTo(route.pattern().toLowerCase(Locale.ROOT));
            assertThat(route.pattern())
                    .as("a trailing slash is a second spelling of the same endpoint: %s", route.pattern())
                    .doesNotEndWith("/");
        }
    }

    @Test
    @DisplayName("the table is not empty, so everything above is actually checking something")
    void thereIsATableAtAll() {
        // Every test above walks the routes. An empty table would pass all of them.
        assertThat(table().routes())
                .as("seven endpoint modules are registered; a table this small means one of them "
                        + "registered nothing")
                .hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("the health endpoint is deliberately not in the table")
    void healthIsHandledBeforeTheRouter() {
        // It answers without a key, so it cannot be an ordinary route — an unauthenticated route in the
        // table would be one more thing every future endpoint author has to remember not to copy.
        assertThat(table().routes())
                .noneMatch(route -> route.pattern().equals("/api/health"));
    }
}
