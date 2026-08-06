package de.raindancer.modules.hungergames.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.Tweak;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The transport half of the HTTP API, without a socket.
 *
 * <p>{@link HttpApiService#dispatch} is where an endpoint's answer — or its exception — becomes a status
 * code, and every branch of it is a thing a caller sees and nothing else in the suite covers. A handler
 * that throws has to become a 500 rather than a connection that never answers; a handler that asks for
 * something impossible has to become a 409 rather than a 500, because a dashboard retries one and gives up
 * on the other.
 *
 * <p>Driven with {@link HttpApiService#inline()} in place of the server-thread hop, which is exactly the
 * seam that interface exists for. Nothing here binds a port: {@link #start()} is the only method that
 * would, and it is deliberately not called.
 */
class HttpApiServiceTest {

    /** A route with no collaborators, for driving dispatch directly. */
    private static ApiRouter.Route route(boolean onTheServerThread, ApiRouter.Handler handler) {
        return new ApiRouter.Route("GET", "/api/test", List.of("api", "test"),
                false, onTheServerThread, "a test route", handler);
    }

    private static ApiRequest request() {
        return new ApiRequest("GET", "/api/test", Map.of(), Map.of(), null);
    }

    /**
     * A log that records every line rather than printing it.
     *
     * <p>Mockito, because {@code LogChannel} is a final class with a package-private constructor — there is
     * no way to build or subclass one from here, and the inline mock maker this project already enables for
     * Byte Buddy is what makes it mockable at all.
     *
     * <p>Recorded through the mock's <em>default answer</em> rather than by stubbing each level. Stubbing
     * {@code warn(String, Object...)} looked right and matched nothing: the matcher has to bind the whole
     * varargs array and quietly failed to, so the assertion about what the console said was checking an
     * empty list. A default answer cannot miss a call, which is the property wanted here — and it records
     * {@code debug} and {@code error} too, without this needing to know which the code chose.
     */
    private LogChannel recordingLog() {
        return mock(LogChannel.class, call -> {
            if (call.getArguments().length > 0 && call.getArgument(0) instanceof String line) {
                logged.add(line);
            }
            return null;
        });
    }

    private final List<String> logged = new ArrayList<>();

    private HttpApiService service(HttpApiService.ServerThread hop) {
        return new HttpApiService(null, null, recordingLog(), new ApiRouter(), hop, key -> {
        }, HungerGamesSettings.DEFAULTS);
    }

    @Nested
    @DisplayName("what comes back out of a handler")
    class Dispatch {

        @Test
        @DisplayName("an answer is passed straight through")
        void theHappyPath() {
            ApiResponse response = service(HttpApiService.inline())
                    .dispatch(route(false, request -> ApiResponse.ok()), request());

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("a bad-request exception becomes 400, with its own message")
        void theCallerAskedWrong() {
            ApiResponse response = service(HttpApiService.inline()).dispatch(
                    route(false, request -> {
                        throw new ApiBadRequestException("\"count\" has to be a number");
                    }), request());

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body().get("error").getAsString())
                    .as("the message is the endpoint's own and is safe to show — it is about the "
                            + "request, not about the server")
                    .contains("count");
        }

        @Test
        @DisplayName("a conflict becomes 409, not 500")
        void theGameSaidNo() {
            ApiResponse response = service(HttpApiService.inline()).dispatch(
                    route(false, request -> {
                        throw new ApiConflictException("no round is running");
                    }), request());

            // The distinction a dashboard acts on: 409 is "not right now, ask again later", 500 is
            // "something is broken, stop asking". Collapsing them makes an overlay either give up on a
            // healthy server or hammer a broken one.
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.body().get("error").getAsString()).contains("no round");
        }

        @Test
        @DisplayName("anything else becomes 500 and names only the exception type")
        void somethingBroke() {
            ApiResponse response = service(HttpApiService.inline()).dispatch(
                    route(false, request -> {
                        throw new IllegalStateException("/srv/mc/plugins/TheHungerGames/session.yml");
                    }), request());

            assertThat(response.status()).isEqualTo(500);
            assertThat(response.body().get("error").getAsString())
                    .as("an exception message is written for whoever reads the log and names file "
                            + "paths, player names and table names — none of which belongs in an HTTP "
                            + "response")
                    .doesNotContain("/srv/mc")
                    .contains("IllegalStateException");
            assertThat(logged)
                    .as("and the console gets all of it, or the 500 is unactionable")
                    .anyMatch(line -> line.contains("error in"));
        }

        @Test
        @DisplayName("a server too busy to answer becomes 503")
        void theServerNeverGotToIt() {
            HttpApiService.ServerThread neverAnswers = new HttpApiService.ServerThread() {
                @Override
                public <T> T call(java.util.concurrent.Callable<T> work) throws TimeoutException {
                    throw new TimeoutException("the tick never came");
                }
            };

            ApiResponse response = service(neverAnswers)
                    .dispatch(route(true, request -> ApiResponse.ok()), request());

            // 503 rather than 500: the request is fine and the server is momentarily not. A caller may
            // retry this one, which is the whole difference.
            assertThat(response.status()).isEqualTo(503);
        }

        @Test
        @DisplayName("a route that does not need the server thread does not use the hop")
        void nothingIsHoppedNeedlessly() {
            List<String> hops = new ArrayList<>();
            HttpApiService.ServerThread counting = new HttpApiService.ServerThread() {
                @Override
                public <T> T call(java.util.concurrent.Callable<T> work) throws Exception {
                    hops.add("hopped");
                    return work.call();
                }
            };

            service(counting).dispatch(route(false, request -> ApiResponse.ok()), request());

            // Every hop costs a tick of latency and holds an HTTP thread while it waits. A read-only
            // endpoint that touches nothing of the server's should never pay for one.
            assertThat(hops).isEmpty();
        }

        @Test
        @DisplayName("a route that does need it, does use it")
        void theHopIsUsedWhenItIsAskedFor() {
            List<String> hops = new ArrayList<>();
            HttpApiService.ServerThread counting = new HttpApiService.ServerThread() {
                @Override
                public <T> T call(java.util.concurrent.Callable<T> work) throws Exception {
                    hops.add("hopped");
                    return work.call();
                }
            };

            service(counting).dispatch(route(true, request -> ApiResponse.ok()), request());

            assertThat(hops).hasSize(1);
        }

        @Test
        @DisplayName("an exception thrown on the server thread is unwrapped, not reported as a wrapper")
        void theRealFailureSurvivesTheHop() {
            // The hop necessarily wraps whatever the work threw. Reporting the wrapper would turn every
            // 409 that happens to be on a world-touching route into a 500 — which is exactly what the
            // version this replaces did, because ExecutionException is not ApiConflictException.
            ApiResponse response = service(wrappingHop()).dispatch(
                    route(true, request -> {
                        throw new ApiConflictException("no round is running");
                    }), request());

            assertThat(response.status()).isEqualTo(409);
        }
    }

    /**
     * A hop shaped like the real one: whatever the work throws comes back wrapped, exactly as a future
     * wraps it. What {@link HttpApiService#viaScheduling} does with its {@code ExecutionException}.
     */
    private static HttpApiService.ServerThread wrappingHop() {
        return new HttpApiService.ServerThread() {
            @Override
            public <T> T call(java.util.concurrent.Callable<T> work) throws Exception {
                java.util.concurrent.CompletableFuture<T> answer = new java.util.concurrent.CompletableFuture<>();
                try {
                    answer.complete(work.call());
                } catch (Throwable thrown) {
                    answer.completeExceptionally(thrown);
                }
                try {
                    return answer.get();
                } catch (java.util.concurrent.ExecutionException wrapped) {
                    Throwable cause = wrapped.getCause();
                    throw cause instanceof Exception real ? real : new IllegalStateException(cause);
                }
            }
        };
    }

    @Nested
    @DisplayName("the self-description")
    class Index {

        @Test
        @DisplayName("lists every route with its method, path and description")
        void everythingIsListed() {
            ApiRouter router = new ApiRouter();
            router.get("/api/one", "the first", request -> ApiResponse.ok());
            router.post("/api/two", "the second", request -> ApiResponse.ok());

            HttpApiService service = new HttpApiService(null, null, recordingLog(), router,
                    HttpApiService.inline(), key -> {
            }, HungerGamesSettings.DEFAULTS);

            var body = service.index().body();

            assertThat(body.get("count").getAsInt()).isEqualTo(2);
            var endpoints = body.getAsJsonArray("endpoints");
            assertThat(endpoints).hasSize(2);
            assertThat(endpoints.get(0).getAsJsonObject().get("path").getAsString())
                    .isEqualTo("/api/one");
            assertThat(endpoints.get(1).getAsJsonObject().get("write").getAsBoolean())
                    .as("a caller has to be able to see which endpoints the read-only lock will refuse, "
                            + "rather than discovering it one 403 at a time")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("the key")
    class TheKey {

        @Test
        @DisplayName("a blank key is generated and handed over to be persisted")
        void aServerWithNoKeyGetsOne() {
            List<String> remembered = new ArrayList<>();
            HungerGamesSettings noKey = Tweak.of(HungerGamesSettings.DEFAULTS, "apiKey", "");

            HttpApiService service = new HttpApiService(null, null, recordingLog(), new ApiRouter(),
                    HttpApiService.inline(), remembered::add, noKey);

            service.ensureThereIsAKey();

            assertThat(remembered).hasSize(1);
            assertThat(remembered.get(0)).hasSize(40);
            assertThat(logged)
                    .as("the key itself must never be logged: a key in the console is a key in every "
                            + "log aggregator, and in the screenshot somebody posts asking for help")
                    .noneMatch(line -> line.contains(remembered.get(0)));
        }

        @Test
        @DisplayName("a configured key is left alone")
        void anExistingKeyIsNotReplaced() {
            List<String> remembered = new ArrayList<>();
            HungerGamesSettings hasKey =
                    Tweak.of(HungerGamesSettings.DEFAULTS, "apiKey", "already-configured");

            HttpApiService service = new HttpApiService(null, null, recordingLog(), new ApiRouter(),
                    HttpApiService.inline(), remembered::add, hasKey);

            service.ensureThereIsAKey();

            // Regenerating on every boot would mean every dashboard on the server stops working after
            // a restart, for no reason anybody could see.
            assertThat(remembered).isEmpty();
        }
    }

    @Test
    @DisplayName("nothing is listening until start() is called")
    void itDoesNotBindOnConstruction() {
        // A constructor that opened a socket would make every test in this file, and every test of every
        // endpoint, an attempt to bind a port.
        assertThat(service(HttpApiService.inline()).isRunning()).isFalse();
    }

    @Test
    @DisplayName("the timeout is long enough to survive a busy tick and short enough to give up")
    void theTimeoutIsSane() {
        assertThat(HttpApiService.SERVER_THREAD_TIMEOUT)
                .isGreaterThanOrEqualTo(Duration.ofSeconds(1))
                .isLessThanOrEqualTo(Duration.ofSeconds(30));
    }
}
