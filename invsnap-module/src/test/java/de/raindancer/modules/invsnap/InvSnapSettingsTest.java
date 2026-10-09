package de.raindancer.modules.invsnap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Every default, spelled out by name — see {@code MannequinSettingsTest} for why this is not trusted. */
class InvSnapSettingsTest {

    private final InvSnapSettings defaults = InvSnapSettings.DEFAULTS;

    @Nested
    @DisplayName("the shipped defaults")
    class Defaults {

        @Test
        @DisplayName("every five minutes, twenty-four kept")
        void eachOneByName() {
            assertThat(defaults.snapshotIntervalSeconds()).isEqualTo(300);
            assertThat(defaults.retentionCount()).isEqualTo(24);
            assertThat(defaults.snapshotInterval()).isEqualTo(Duration.ofMinutes(5));
        }
    }

    @Nested
    @DisplayName("clamping")
    class Clamping {

        @Test
        @DisplayName("the interval is clamped into its declared range")
        void intervalIsClamped() {
            assertThat(defaults.withSnapshotIntervalSeconds(0).snapshotInterval())
                    .isEqualTo(Duration.ofSeconds(30));
            assertThat(defaults.withSnapshotIntervalSeconds(999_999).snapshotInterval())
                    .isEqualTo(Duration.ofSeconds(86_400));
            assertThat(defaults.withSnapshotIntervalSeconds(120).snapshotInterval())
                    .isEqualTo(Duration.ofSeconds(120));
        }

        @Test
        @DisplayName("the retention count is clamped, never below one")
        void retentionIsClamped() {
            assertThat(defaults.withRetentionCount(0).retentionCountClamped()).isEqualTo(1);
            assertThat(defaults.withRetentionCount(9999).retentionCountClamped()).isEqualTo(500);
            assertThat(defaults.withRetentionCount(50).retentionCountClamped()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("death insurance")
    class Insurance {

        @Test
        @DisplayName("ships off and free, so a server without an economy behaves as before")
        void defaultsChangeNothing() {
            assertThat(defaults.insuranceEnabled()).isFalse();
            assertThat(defaults.insurancePricePercent()).isZero();
            assertThat(defaults.insurancePriceFlat()).isEqualTo("0");
            assertThat(defaults.insuranceMost()).isEqualTo("0");
            assertThat(defaults.insuranceKeepXp()).isFalse();
            assertThat(defaults.insuranceWorlds()).isEmpty();
        }

        @Test
        @DisplayName("an empty world list means every world, otherwise only the listed ones")
        void worlds() {
            assertThat(defaults.insuresWorld("anything")).isTrue();
            InvSnapSettings listed = defaults.withInsuranceWorlds(java.util.List.of("Survival"));
            assertThat(listed.insuresWorld("survival")).isTrue();
            assertThat(listed.insuresWorld("hungergames")).isFalse();
        }
    }

    @Nested
    @DisplayName("item insurance")
    class ItemInsurance {

        @Test
        @DisplayName("ships off and without a price, a week between premiums, three items")
        void defaultsChangeNothing() {
            assertThat(defaults.itemInsuranceEnabled()).isFalse();
            assertThat(defaults.itemInsurancePricePercent()).isZero();
            assertThat(defaults.itemInsurancePriceFlat()).isEqualTo("0");
            assertThat(defaults.itemInsuranceLeast()).isEqualTo("0");
            assertThat(defaults.itemInsuranceEveryHours()).isEqualTo(168);
            assertThat(defaults.itemInsuranceMostItems()).isEqualTo(3);
            assertThat(defaults.itemInsuranceClaimFee()).isEqualTo("0");
            assertThat(defaults.itemInsurancePriced()).isFalse();
        }

        @Test
        @DisplayName("any one of percent, flat or floor makes it priced")
        void priced() {
            assertThat(defaults.withItemInsurancePrice(5, "0", "0").itemInsurancePriced()).isTrue();
            assertThat(defaults.withItemInsurancePrice(0, "10", "0").itemInsurancePriced()).isTrue();
            assertThat(defaults.withItemInsurancePrice(0, "0", "10").itemInsurancePriced()).isTrue();
        }

        @Test
        @DisplayName("renewal hours and item count are clamped")
        void clamped() {
            assertThat(defaults.withItemInsuranceTerms(0, 0, "0").itemInsuranceEvery())
                    .isEqualTo(Duration.ofHours(1));
            assertThat(defaults.withItemInsuranceTerms(24, 99, "0").itemInsuranceMostItemsClamped()).isEqualTo(50);
        }

        @Test
        @DisplayName("changing one group leaves the others, death insurance included, as they were")
        void withersKeepTheRest() {
            InvSnapSettings changed = defaults.withInsuranceEnabled(true).withItemInsurance(true)
                    .withItemInsurancePrice(5, "1", "2").withItemInsuranceTerms(24, 5, "3");

            assertThat(changed.insuranceEnabled()).isTrue();
            assertThat(changed.itemInsuranceEnabled()).isTrue();
            assertThat(changed.itemInsurancePricePercent()).isEqualTo(5);
            assertThat(changed.itemInsurancePriceFlat()).isEqualTo("1");
            assertThat(changed.itemInsuranceLeast()).isEqualTo("2");
            assertThat(changed.itemInsuranceEveryHours()).isEqualTo(24);
            assertThat(changed.itemInsuranceMostItems()).isEqualTo(5);
            assertThat(changed.itemInsuranceClaimFee()).isEqualTo("3");
            assertThat(changed.withRetentionCount(7).itemInsuranceMostItems()).isEqualTo(5);
        }
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the settings are a schema the server accepts: every page a setting sits on is declared")
    void schemaIsValid() {
        org.assertj.core.api.Assertions.assertThatCode(() ->
                de.raindancer.core.data.settings.SettingsSchema.of(InvSnapSettings.class, InvSnapSettings.DEFAULTS))
                .doesNotThrowAnyException();
    }
}
