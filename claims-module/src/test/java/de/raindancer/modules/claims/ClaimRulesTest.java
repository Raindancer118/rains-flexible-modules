package de.raindancer.modules.claims;

import de.raindancer.core.platform.rule.IRule;
import de.raindancer.core.platform.rule.Rules;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimPoint;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.model.NoClaimZone;
import de.raindancer.modules.claims.model.ClaimAttempt;
import de.raindancer.modules.claims.model.ClaimNames;
import de.raindancer.modules.claims.rules.ClaimRules;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.store.ZoneRegistry;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each reason a claim can be refused, on its own.
 *
 * <p>Which is the whole point of the rules: this used to be a ninety-line {@code validate} that returned early
 * nine times, so reaching the sixth reason in a test meant satisfying the first five, and nothing could add a
 * tenth. Now each is a class with one method and the chain is data.
 *
 * <p>These use the real chain rather than a stand-in, so the order — cheapest first — is exercised too.
 */
class ClaimRulesTest {

    private static final UUID WORLD = UUID.randomUUID();

    private final ClaimRegistry claims = new ClaimRegistry();
    private final ZoneRegistry zones = new ZoneRegistry();
    private final ClaimNames names = new ClaimNames(claims, id -> "Somebody");

    private ClaimSettings settings = ClaimSettings.DEFAULTS;
    private boolean bypassing = false;

    private Rules<ClaimAttempt> chain() {
        return ClaimRules.standard(() -> settings, claims, zones, names, player -> bypassing);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────────

    private static ClaimShape square(int from, int to) {
        return new ClaimShape(List.of(
                new ClaimPoint(from, from), new ClaimPoint(from, to),
                new ClaimPoint(to, to), new ClaimPoint(to, from)), -64, 319);
    }

    /** A world whose only interesting answers are its id and its height. */
    private static final World WORLD_OBJECT = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUID" -> WORLD;
                case "getName" -> "world";
                case "getMinHeight" -> -64;
                case "getMaxHeight" -> 320;
                case "toString" -> "a fake world";
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> null;
            });

    private final Set<String> permissions = new HashSet<>();
    private final UUID playerId = UUID.randomUUID();

    private final Player player = (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getName" -> "Somebody";
                case "hasPermission" -> args[0] instanceof String node && permissions.contains(node);
                case "toString" -> "a fake player";
                case "hashCode" -> 2;
                case "equals" -> proxy == args[0];
                default -> defaultFor(method.getReturnType());
            });

    private static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        return type == void.class ? null : 0;
    }

    private ClaimAttempt attempt(ClaimShape shape, String name) {
        return ClaimAttempt.toCreate(player, WORLD_OBJECT, shape, name);
    }

    private Claim existing(String name, ClaimShape shape, UUID owner) {
        Claim claim = new Claim(UUID.randomUUID(), name, WORLD, "world", shape, owner);
        claims.add(claim);
        return claim;
    }

    // ── the rules ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an ordinary claim is allowed")
    void nothingObjectsToAReasonableClaim() {
        assertThat(chain().judge(attempt(square(0, 20), "home")).isAllowed()).isTrue();
    }

    @Nested
    @DisplayName("the world")
    class Worlds {

        @Test
        void aDisabledWorldRefusesNewClaims() {
            // Disabled by name, which is how a server owner writes it.
            settings = disabledIn("world");

            Verdict verdict = chain().judge(attempt(square(0, 20), "home"));

            assertThat(verdict.reason()).isEqualTo("error.world-disabled");
            assertThat(verdict.detail()).isEqualTo("world");
        }

        @Test
        void anotherWorldBeingDisabledChangesNothing() {
            settings = disabledIn("nether");
            assertThat(chain().judge(attempt(square(0, 20), "home")).isAllowed()).isTrue();
        }

        private ClaimSettings disabledIn(String world) {
            return ClaimSettings.DEFAULTS.withDisabledWorlds(List.of(world));
        }
    }

    @Nested
    @DisplayName("the name")
    class Names {

        @Test
        void aNameWithSpacesIsRefused() {
            assertThat(chain().judge(attempt(square(0, 20), "my base")).reason())
                    .isEqualTo("error.name-invalid");
        }

        @Test
        void aNameTooShortIsRefused() {
            assertThat(chain().judge(attempt(square(0, 20), "ab")).reason())
                    .isEqualTo("error.name-invalid");
        }

        @Test
        void theirOwnNameTwiceIsRefused() {
            existing("home", square(100, 120), playerId);
            assertThat(chain().judge(attempt(square(0, 20), "home")).reason())
                    .isEqualTo("error.name-taken");
        }

        @Test
        void somebodyElsesNameIsFine() {
            // Names are unique per owner, not per server: five people each wanting a "home" is the obvious
            // thing to want, and the first should not take the word from everybody else.
            existing("home", square(100, 120), UUID.randomUUID());
            assertThat(chain().judge(attempt(square(0, 20), "home")).isAllowed()).isTrue();
        }

        @Test
        void aReshapeIsNotJudgedOnItsName() {
            Claim mine = existing("home", square(0, 20), playerId);
            ClaimAttempt reshape = ClaimAttempt.toReshape(player, WORLD_OBJECT, square(0, 30), mine);

            assertThat(chain().judge(reshape).isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("the shape")
    class Shapes {

        @Test
        void tooSmallIsRefusedWithTheLimit() {
            Verdict verdict = chain().judge(attempt(square(0, 1), "tiny"));

            assertThat(verdict.reason()).isEqualTo("error.claim-too-small");
            assertThat(verdict.detail()).isEqualTo(String.valueOf(ClaimSettings.DEFAULTS.minClaimArea()));
        }

        @Test
        void noUpperLimitMeansNoUpperLimit() {
            // The default is -1, and reading that as a maximum would refuse every claim on the server.
            assertThat(chain().judge(attempt(square(0, 5000), "huge")).isAllowed()).isTrue();
        }

        @Test
        void anUpperLimitIsEnforcedWhenThereIsOne() {
            settings = ClaimSettings.DEFAULTS.withMaxClaimArea(100L);

            Verdict verdict = chain().judge(attempt(square(0, 50), "big"));

            assertThat(verdict.reason()).isEqualTo("error.claim-too-large");
            assertThat(verdict.detail()).isEqualTo("100");
        }
    }

    @Nested
    @DisplayName("the ground")
    class Ground {

        @Test
        void overlappingSomebodyElseIsRefusedAndNamesThem() {
            existing("theirs", square(10, 30), UUID.randomUUID());

            Verdict verdict = chain().judge(attempt(square(0, 20), "mine"));

            assertThat(verdict.reason()).isEqualTo("error.overlaps-claim");
            assertThat(verdict.detail()).isEqualTo("theirs");
        }

        @Test
        void aReshapeDoesNotOverlapItself() {
            Claim mine = existing("home", square(0, 20), playerId);
            ClaimAttempt reshape = ClaimAttempt.toReshape(player, WORLD_OBJECT, square(0, 25), mine);

            assertThat(chain().judge(reshape).isAllowed()).isTrue();
        }

        @Test
        void aNoClaimZoneIsRefusedAndNamesIt() {
            zones.add(new NoClaimZone("spawn", WORLD, "world", square(10, 30), 0L));

            Verdict verdict = chain().judge(attempt(square(0, 20), "mine"));

            assertThat(verdict.reason()).isEqualTo("error.in-no-claim-zone");
            assertThat(verdict.detail()).isEqualTo("spawn");
        }

        @Test
        void anAdminMayClaimInsideAZone() {
            // Not a permission any more: an operator or any staff rank gets nothing here for free. Only
            // the explicit /claimadmin bypass toggle gets through a no-claim zone.
            zones.add(new NoClaimZone("spawn", WORLD, "world", square(10, 30), 0L));
            bypassing = true;

            assertThat(chain().judge(attempt(square(0, 20), "mine")).isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("the chain itself")
    class Chain {

        @Test
        void asksTheCheapRulesBeforeTheExpensiveOnes() {
            // A claim in a disabled world that also overlaps: the answer should be the world, because the
            // world check is a string compare and the overlap check walks a spatial index.
            existing("theirs", square(0, 20), UUID.randomUUID());
            settings = new Worlds().disabledIn("world");

            assertThat(chain().judge(attempt(square(0, 20), "mine")).reason())
                    .isEqualTo("error.world-disabled");
        }

        @Test
        void canReportEveryReasonAtOnce() {
            // What a screen needs: everything wrong with an outline, rather than one thing at a time.
            existing("theirs", square(0, 20), UUID.randomUUID());
            zones.add(new NoClaimZone("spawn", WORLD, "world", square(0, 20), 0L));

            List<Verdict> refusals = chain().judgeAll(attempt(square(0, 1), "x y"));

            assertThat(refusals).extracting(Verdict::reason)
                    .contains("error.name-invalid", "error.claim-too-small",
                            "error.in-no-claim-zone", "error.overlaps-claim");
        }

        @Test
        void everyRuleSaysWhatItRequires() {
            // The names end up in the diagnostic that says which rule refused, so a rule called
            // "DoesNotOverlap$$Lambda" is a rule nobody can act on.
            for (IRule<ClaimAttempt> rule : chain().all()) {
                assertThat(rule.describe())
                        .as("%s", rule.getClass().getSimpleName())
                        .isNotBlank()
                        .doesNotContain("Lambda");
            }
        }

        @Test
        void aServerMayAddAReasonOfItsOwn() {
            // The point of the chain being data: a plugin adds a rule rather than editing a method.
            Rules<ClaimAttempt> stricter = chain().and(
                    subject -> Verdict.refused("error.generic", "not on my server"));

            assertThat(stricter.judge(attempt(square(0, 20), "home")).detail())
                    .isEqualTo("not on my server");
        }
    }
}
