package de.raindancer.modules.hungergames.service;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.Tweak;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flight budget Hermes' boots spend — the pure half of the mechanic. The armour-slot check, the
 * granting and revoking of {@code setAllowFlight}, and only depleting while actually airborne all live in
 * {@code HungerGamesWiring.tickHermesBoots}, which needs a server; what is provable here is the arithmetic
 * that tick calls into.
 */
class HermesBootsServiceTest {

    private static final UUID TRIBUTE = UUID.fromString("00000000-0000-0000-0000-00000000b007");

    private HermesBootsService service;

    @BeforeEach
    void setUp() {
        service = new HermesBootsService(mockCustomItems(), HungerGamesSettings.DEFAULTS);
    }

    private static CustomItems mockCustomItems() {
        return org.mockito.Mockito.mock(CustomItems.class);
    }

    @Nested
    @DisplayName("granting")
    class Granting {

        @Test
        @DisplayName("nobody has a budget before it is granted")
        void nothingUntilGranted() {
            assertThat(service.remaining(TRIBUTE)).isZero();
            assertThat(service.hasFlightLeft(TRIBUTE)).isFalse();
        }

        @Test
        @DisplayName("granting hands out the full configured budget")
        void grantsTheFullBudget() {
            service.grantIfAbsent(TRIBUTE);

            assertThat(service.remaining(TRIBUTE)).isEqualTo(HungerGamesSettings.DEFAULTS.hermesFlightSeconds());
        }

        @Test
        @DisplayName("a server that tuned the flight time is actually honoured")
        void theBudgetIsTheServersOwn() {
            service.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "hermesFlightSeconds", 25));

            service.grantIfAbsent(TRIBUTE);

            assertThat(service.remaining(TRIBUTE)).isEqualTo(25);
        }

        @Test
        @DisplayName("granting again does not refill it — taking the boots off and back on is not a recharge")
        void secondGrantDoesNotRefill() {
            service.grantIfAbsent(TRIBUTE);
            service.depleteOneSecond(TRIBUTE);
            service.depleteOneSecond(TRIBUTE);

            service.grantIfAbsent(TRIBUTE);

            assertThat(service.remaining(TRIBUTE))
                    .isEqualTo(HungerGamesSettings.DEFAULTS.hermesFlightSeconds() - 2);
        }
    }

    @Nested
    @DisplayName("spending")
    class Spending {

        @Test
        @DisplayName("depleting counts down by exactly one second at a time")
        void countsDownByOne() {
            service.grantIfAbsent(TRIBUTE);
            int start = service.remaining(TRIBUTE);

            int after = service.depleteOneSecond(TRIBUTE);

            assertThat(after).isEqualTo(start - 1);
        }

        @Test
        @DisplayName("never goes below zero, however many times it is spent")
        void neverGoesNegative() {
            service.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "hermesFlightSeconds", 2));
            service.grantIfAbsent(TRIBUTE);

            service.depleteOneSecond(TRIBUTE);
            service.depleteOneSecond(TRIBUTE);
            int after = service.depleteOneSecond(TRIBUTE);

            assertThat(after).isZero();
            assertThat(service.hasFlightLeft(TRIBUTE)).isFalse();
        }

        @Test
        @DisplayName("spending before ever granting still floors at zero rather than going negative")
        void spendingBeforeGrantingIsSafe() {
            int after = service.depleteOneSecond(TRIBUTE);

            assertThat(after).isZero();
        }
    }

    @Nested
    @DisplayName("the warning threshold")
    class Warning {

        @Test
        @DisplayName("not running low with the full budget")
        void notLowAtFull() {
            service.grantIfAbsent(TRIBUTE);

            assertThat(service.isRunningLow(TRIBUTE)).isFalse();
        }

        @Test
        @DisplayName("running low exactly once the remaining budget crosses the configured warning window")
        void lowOnceUnderTheWarningWindow() {
            HungerGamesSettings tuned = Tweak.of(HungerGamesSettings.DEFAULTS,
                    "hermesFlightSeconds", 5, "hermesWarningSeconds", 2);
            service.settings(tuned);
            service.grantIfAbsent(TRIBUTE);

            service.depleteOneSecond(TRIBUTE); // 4 left
            assertThat(service.isRunningLow(TRIBUTE)).isFalse();
            service.depleteOneSecond(TRIBUTE); // 3 left
            assertThat(service.isRunningLow(TRIBUTE)).isFalse();
            service.depleteOneSecond(TRIBUTE); // 2 left — the window
            assertThat(service.isRunningLow(TRIBUTE)).isTrue();
        }

        @Test
        @DisplayName("zero is spent, not merely 'low' — the two must not be confused")
        void zeroIsNotLow() {
            service.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "hermesFlightSeconds", 1));
            service.grantIfAbsent(TRIBUTE);

            service.depleteOneSecond(TRIBUTE);

            assertThat(service.isRunningLow(TRIBUTE))
                    .as("spent is a different message than running low — see tickHermesBoots")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a new round clears everybody's budget")
    void resetForNewRoundClearsEverybody() {
        service.grantIfAbsent(TRIBUTE);
        service.depleteOneSecond(TRIBUTE);

        service.resetForNewRound();

        assertThat(service.remaining(TRIBUTE)).isZero();
        service.grantIfAbsent(TRIBUTE);
        assertThat(service.remaining(TRIBUTE))
                .as("a fresh grant after a reset is the full budget again, not the leftover from before")
                .isEqualTo(HungerGamesSettings.DEFAULTS.hermesFlightSeconds());
    }

    @Test
    @DisplayName("registers the item with no ability at all — it is worn, not clicked")
    void registersWithNoAbility(@TempDir Path dir) {
        CustomItems items = new CustomItems(dir.resolve("items.yml"));
        HermesBootsService real = new HermesBootsService(items, HungerGamesSettings.DEFAULTS);

        real.register();

        CustomItem defined = items.byKey(HermesBootsService.PLUGIN + ":" + HermesBootsService.HERMES_BOOTS)
                .orElseThrow();
        assertThat(defined.ability())
                .as("no ability key — there is nothing for a right click to dispatch to")
                .isEmpty();
        assertThat(defined.material()).isEqualTo(org.bukkit.Material.GOLDEN_BOOTS);
    }
}
