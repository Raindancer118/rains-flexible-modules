package de.raindancer.modules.hungergames;

import de.raindancer.core.world.protection.LandAction;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.core.world.protection.LandFlag;
import de.raindancer.core.world.protection.ProtectedArea;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.ProtectionRules;
import de.raindancer.modules.hungergames.service.CornucopiaProvider;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What the module tells RainsCore about the cornucopia.
 *
 * <p>The module's whole share of protection is this one provider: where the cornucopia is and whether the
 * phase currently closes it. Core's own listeners do the cancelling, Core's {@code Land} does the bypass
 * and the message. So what is worth testing here is exactly the two things the module decides — geometry
 * and phase — plus the guarantees Core relies on when it asks.
 *
 * <p>{@code ProtectedArea} is what Core receives, so it is checked through that interface rather than
 * through the class: the audience every tribute is in, the flags deliberately not overridden, and which
 * actions the area refuses.
 */
class CornucopiaProviderTest {

    private static final int RADIUS = HungerGamesSettings.DEFAULTS.cornucopiaRadius();

    private final World arena = mock(World.class);
    private final World survival = mock(World.class);

    private GamePhase phase = GamePhase.LOBBY;
    private Location centre;

    private CornucopiaProvider provider;

    @BeforeEach
    void setUp() {
        centre = new Location(arena, 0, 64, 0);
        provider = new CornucopiaProvider(HungerGamesSettings.DEFAULTS, () -> phase, () -> centre);
    }

    private Location at(World world, double x, double z) {
        return new Location(world, x, 64, z);
    }

    @Nested
    @DisplayName("where it is")
    class Geometry {

        @Test
        @DisplayName("inside the radius, in the arena's world")
        void theMiddle() {
            assertThat(provider.isInsideTheCornucopia(at(arena, RADIUS - 1, 0))).isTrue();
        }

        @Test
        @DisplayName("outside the radius is not")
        void further() {
            assertThat(provider.isInsideTheCornucopia(at(arena, RADIUS + 5, 0))).isFalse();
        }

        @Test
        @DisplayName("the boundary belongs to the cornucopia")
        void exactlyOnTheEdge() {
            // The wall of the cornucopia itself is the thing most likely to be mined by somebody standing
            // just outside it, so the edge has to be inside.
            assertThat(provider.isInsideTheCornucopia(at(arena, RADIUS, 0))).isTrue();
        }

        @Test
        @DisplayName("the same coordinates in another world are not")
        void anotherWorldEntirely() {
            // Without the world check, a matching circle is protected at the same coordinates in every
            // world on the server — including spawn, which is usually near the origin too.
            assertThat(provider.isInsideTheCornucopia(at(survival, 0, 0)))
                    .as("a circle around the survival world's origin is not the cornucopia")
                    .isFalse();
        }

        @Test
        @DisplayName("before /init there is no cornucopia anywhere")
        void beforeTheArenaExists() {
            CornucopiaProvider noArena = new CornucopiaProvider(
                    HungerGamesSettings.DEFAULTS, () -> phase, () -> null);

            assertThat(noArena.isInsideTheCornucopia(at(arena, 0, 0))).isFalse();
            assertThat(noArena.at(at(arena, 0, 0))).isEmpty();
        }

        @Test
        @DisplayName("a null location is answered, not thrown at")
        void nothingAtAll() {
            assertThat(provider.isInsideTheCornucopia(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("which worlds Core is asked about")
    class Worlds {

        @Test
        @DisplayName("only the arena's own world")
        void justTheOne() {
            assertThat(provider.hasAnyIn(arena)).isTrue();
            assertThat(provider.hasAnyIn(survival))
                    .as("answering true everywhere makes Core consult this provider about the survival "
                            + "world, the nether and every farm world on the server")
                    .isFalse();
            assertThat(provider.hasAnyIn(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("when it is closed")
    class Phases {

        @Test
        @DisplayName("closed while tributes wait, so Core is given an area")
        void beforeTheBell() {
            phase = GamePhase.LOBBY;

            assertThat(provider.at(at(arena, 1, 1)))
                    .as("forty people are standing on platforms watching; the middle is not to be "
                            + "dismantled while they wait")
                    .isPresent();
        }

        @Test
        @DisplayName("open during the round, so Core is given nothing")
        void duringTheFight() {
            phase = GamePhase.RUNNING;

            assertThat(provider.at(at(arena, 1, 1)))
                    .as("digging up the middle mid-fight is the game, not griefing — and an empty answer "
                            + "is cheaper for Core than an area whose every flag is permissive")
                    .isEmpty();
        }

        @Test
        @DisplayName("a settings reload changes it from then on")
        void reloadingIsRespected() {
            phase = GamePhase.RUNNING;
            assertThat(provider.at(at(arena, 1, 1))).isEmpty();

            provider.settings(Tweak.of(HungerGamesSettings.DEFAULTS,
                    "protectCornucopiaDuringRunning", true));

            assertThat(provider.at(at(arena, 1, 1)))
                    .as("a provider that kept the config it was built with would go on protecting — or "
                            + "not protecting — for the rest of the tournament")
                    .isPresent();
        }

        @Test
        @DisplayName("a collaborator that throws does not take the whole server's protection down")
        void nothingUnwinds() {
            // Core asks this to decide whether to cancel an event. An exception unwinding through it
            // leaves the event uncancelled — which is protection switching itself off for *every* plugin
            // that goes through Land, not just this module.
            CornucopiaProvider broken = new CornucopiaProvider(HungerGamesSettings.DEFAULTS,
                    () -> {
                        throw new IllegalStateException("no session");
                    }, () -> centre);

            assertThat(broken.at(at(arena, 1, 1))).isEmpty();
        }
    }

    @Nested
    @DisplayName("the area Core receives")
    class TheArea {

        private ProtectedArea area() {
            phase = GamePhase.LOBBY;
            Optional<ProtectedArea> found = provider.at(at(arena, 1, 1));
            assertThat(found).isPresent();
            return found.get();
        }

        @Test
        @DisplayName("nobody owns it, so every tribute is a visitor in it")
        void ownedByTheTournament() {
            ProtectedArea area = area();

            assertThat(area.owners())
                    .as("an owner list with the admin who ran /init in it would quietly exempt one "
                            + "player from what the other thirty-nine are held to")
                    .isEmpty();
            assertThat(area.audienceOf(UUID.randomUUID())).isEqualTo(LandAudience.VISITOR);
        }

        @Test
        @DisplayName("it overrides no flags")
        void pvpIsTheServersBusiness() {
            ProtectedArea area = area();

            for (LandFlag flag : LandFlag.values()) {
                for (LandAudience audience : LandAudience.values()) {
                    assertThat(area.flagOverride(flag, audience))
                            .as("a cornucopia that switched %s off would be a safe square in the middle "
                                    + "of a fight to the death", flag)
                            .isEmpty();
                }
            }
        }

        @Test
        @DisplayName("entering and opening chests always work")
        void theRoundStartsHere() {
            ProtectedArea area = area();
            UUID tribute = UUID.randomUUID();

            assertThat(area.may(tribute, LandAction.ENTER))
                    .as("the cornucopia is where the round starts; a barrier round it is a different game")
                    .isTrue();
            assertThat(area.may(tribute, LandAction.CONTAINERS))
                    .as("a round where tributes cannot open the cornucopia chests is not a Hunger "
                            + "Games round")
                    .isTrue();
        }

        @Test
        @DisplayName("building and breaking are refused while it is closed")
        void theStructureIsHeld() {
            ProtectedArea area = area();
            UUID tribute = UUID.randomUUID();

            assertThat(area.may(tribute, LandAction.BREAK)).isFalse();
            assertThat(area.may(tribute, LandAction.BUILD)).isFalse();
        }

        @Test
        @DisplayName("it has a stable id and a name a player would recognise")
        void itNamesItself() {
            ProtectedArea area = area();

            assertThat(area.id()).isEqualTo(CornucopiaProvider.AREA_ID);
            assertThat(area.name()).containsIgnoringCase("cornucopia");
        }
    }

    @Nested
    @DisplayName("narrowing Core's seventeen actions to the matrix's four")
    class Translation {

        @Test
        @DisplayName("every Core action maps to something")
        void nothingFallsThrough() {
            for (LandAction action : LandAction.values()) {
                assertThat(CornucopiaProvider.translate(action))
                        .as("%s has no mapping", action)
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("the ones that change blocks map to placing or breaking")
        void structuralActions() {
            assertThat(CornucopiaProvider.translate(LandAction.BUILD))
                    .isEqualTo(ProtectionRules.ActionType.PLACE);
            assertThat(CornucopiaProvider.translate(LandAction.BUCKETS))
                    .as("a bucket of lava on the cornucopia is a placement by any reasonable reading")
                    .isEqualTo(ProtectionRules.ActionType.PLACE);
            assertThat(CornucopiaProvider.translate(LandAction.IGNITE))
                    .as("setting fire to it is not an interaction")
                    .isEqualTo(ProtectionRules.ActionType.BREAK);
        }

        @Test
        @DisplayName("the ones that only touch things map to interacting")
        void harmlessActions() {
            assertThat(CornucopiaProvider.translate(LandAction.DOORS))
                    .isEqualTo(ProtectionRules.ActionType.INTERACT);
            assertThat(CornucopiaProvider.translate(LandAction.CONTAINERS))
                    .isEqualTo(ProtectionRules.ActionType.CONTAINER);
        }

        @Test
        @DisplayName("anything unrecognised is treated as the strictest of the four")
        void theDefaultIsSafe() {
            // Core has seventeen actions and the matrix four, so this is a narrowing with a default — and
            // the direction it falls decides whether an action added to Core tomorrow arrives protected or
            // arrives as a hole. VEHICLES stands in for "something nobody thought about".
            assertThat(CornucopiaProvider.translate(LandAction.VEHICLES))
                    .isEqualTo(ProtectionRules.ActionType.BREAK);
        }
    }
}
