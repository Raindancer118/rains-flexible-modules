package de.raindancer.modules.claims;

import de.raindancer.core.world.protection.LandAction;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.core.world.protection.LandFlag;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.model.CostType;
import de.raindancer.modules.claims.store.ClaimStorage;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A claim written by Rain's Extended Claims <b>1.2.2</b>, read by the module that replaced it.
 *
 * <h2>Why this fixture and not a reconstructed one</h2>
 * Every key below was taken from the {@code saveClaim} of commit {@code 02b705d} — the oldest version in the
 * repository, tagged 1.2.2 — rather than written from memory of what it probably looked like. A fixture that
 * agrees with today's writer proves nothing; the whole risk is in the shapes today's writer no longer produces.
 *
 * <p>1.2.2 predates the CHANGELOG, which starts at 1.3.1, so nothing had ever checked that its files load. What
 * makes it the interesting case is what it lacks:
 *
 * <ul>
 *   <li><b>no {@code data-version} key at all</b> — it reads as version 1, which is what puts every legacy
 *       branch in play;</li>
 *   <li><b>one boolean per flag</b> rather than a value per audience, which has to be spread across the three
 *       tiers <em>with</em> the exemptions the listeners used to apply in code — or an owner who had closed
 *       their home to teleports finds themselves locked out of it;</li>
 *   <li><b>{@code pantry.feed-visitors} and {@code equipment.equip-visitors}</b>, two booleans that became the
 *       general "who does this perk serve".</li>
 * </ul>
 *
 * <p>Losing this is not losing a setting. It is putting years of somebody's building behind a format the
 * current code has never been shown.
 */
class From122Test {

    private static final UUID CLAIM = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID WORLD = UUID.fromString("66666666-7777-8888-9999-000000000000");
    private static final UUID OWNER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID FRIEND = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final UUID BARRED = UUID.fromString("99999999-8888-7777-6666-555555555555");

    /**
     * Exactly the keys 1.2.2 wrote, in the order it wrote them.
     *
     * <p>The item-valued fields are left out only because they are Base64 of a live server's NBT and cannot be
     * produced without one — their handling is covered by {@code ItemTextTest}. Everything else is here.
     */
    private static final String AS_WRITTEN_BY_122 = """
            id: 11111111-2222-3333-4444-555555555555
            name: Ahornhof
            world-id: 66666666-7777-8888-9999-000000000000
            world-name: world
            created-at: 1721900000000
            shape:
              vertices:
                - '10,10'
                - '10,40'
                - '40,40'
                - '40,10'
              min-y: -64
              max-y: 319
            owners:
              - aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
            public-permissions:
              - enter
              - workstations
            members:
              12345678-1234-1234-1234-123456789abc:
                permissions:
                  - enter
                  - build
                  - containers
                admin-permissions:
                  - manage_members
                grantable:
                  - build
                added-at: 1721900500000
            bans:
              99999999-8888-7777-6666-555555555555:
                issued-by: aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
                issued-at: 1721901000000
                expires-at: 0
                reason: griefing the barn
            flags:
              pvp: false
              teleport-in: false
              fire-spread: false
              fall-damage: true
            titles:
              fade-in: 10
              stay: 40
              fade-out: 10
              enter:
                title:
                  text: Ahornhof
                  color: green
                  bold: true
            entry-fee:
              enabled: true
              type: xp_levels
              amount: 3
              pass-seconds: 600
            bank:
              experience: 120
            fence:
              enabled: true
              material: SPRUCE_FENCE
            pantry:
              enabled: true
              threshold: 6
              feed-visitors: false
              allow-deposits: true
            equipment:
              enabled: true
              equip-visitors: false
            atmosphere:
              weather: clear
              time-preset: noon
              time-ticks: 6000
            paid:
              type: xp_levels
              amount: 20
              area: 900
              settled: 20
            """;

    private static Claim load(String yaml) {
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(yaml);
        } catch (InvalidConfigurationException malformed) {
            throw new AssertionError("the fixture is not valid YAML", malformed);
        }
        return ClaimStorage.fromYaml(parsed, "from-1.2.2.yml");
    }

    private final Claim claim = load(AS_WRITTEN_BY_122);

    @Test
    @DisplayName("it loads at all")
    void aFileWithNoVersionKeyIsStillAClaim() {
        assertThat(claim).isNotNull();
        assertThat(claim.id()).isEqualTo(CLAIM);
        assertThat(claim.name()).isEqualTo("Ahornhof");
        assertThat(claim.worldId()).isEqualTo(WORLD);
        assertThat(claim.worldName()).isEqualTo("world");
        assertThat(claim.createdAt()).isEqualTo(1_721_900_000_000L);
    }

    @Test
    @DisplayName("it is marked for rewriting, so the upgrade lands once")
    void anOldFileIsDirty() {
        // Otherwise the legacy branches are re-derived on every start for ever, and the log line saying how
        // many were brought forward never stops appearing.
        assertThat(claim.dirty())
                .as("a file in an older shape has to be written back in the current one")
                .isTrue();
    }

    @Nested
    @DisplayName("the ground and the people")
    class Basics {

        @Test
        void theShapeSurvives() {
            assertThat(claim.shape().vertices()).hasSize(4);
            assertThat(claim.shape().minY()).isEqualTo(-64);
            assertThat(claim.shape().maxY()).isEqualTo(319);
            assertThat(claim.shape().areaBlocks()).isPositive();
        }

        @Test
        void theOwnerSurvives() {
            assertThat(claim.owners()).containsExactly(OWNER);
            assertThat(claim.isOwner(OWNER)).isTrue();
        }

        @Test
        void aTrustedPlayerKeepsExactlyWhatTheyHad() {
            var member = claim.member(FRIEND);
            assertThat(member).isPresent();
            assertThat(member.get().permissions())
                    .containsExactlyInAnyOrder(LandAction.ENTER, LandAction.BUILD, LandAction.CONTAINERS);
            assertThat(member.get().adminPermissions())
                    .containsExactly(ClaimAdminPermission.MANAGE_MEMBERS);
            assertThat(member.get().grantablePermissions()).containsExactly(LandAction.BUILD);
        }

        @Test
        void thePublicGrantSurvives() {
            assertThat(claim.publicHas(LandAction.ENTER)).isTrue();
            assertThat(claim.publicHas(LandAction.WORKSTATIONS)).isTrue();
            assertThat(claim.publicHas(LandAction.BUILD)).isFalse();
        }

        @Test
        void aBanSurvivesAndStillBars() {
            assertThat(claim.bans()).containsKey(BARRED);
            assertThat(claim.activeBan(BARRED)).isPresent();
            assertThat(claim.activeBan(BARRED).get().reason()).isEqualTo("griefing the barn");
            assertThat(claim.activeBan(BARRED).get().permanent())
                    .as("expires-at 0 meant permanent")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("the flags, which is where an upgrade goes wrong")
    class Flags {

        @Test
        void aBareFalseBecomesFalseForEverybody() {
            for (LandAudience audience : LandAudience.values()) {
                assertThat(claim.flagOverride(LandFlag.PVP, audience))
                        .as("pvp for %s", audience)
                        .contains(false);
            }
        }

        @Test
        void aBareTrueBecomesTrueForEverybody() {
            for (LandAudience audience : LandAudience.values()) {
                assertThat(claim.flagOverride(LandFlag.FALL_DAMAGE, audience)).contains(true);
            }
        }

        @Test
        void anExemptionTheListenersUsedToApplyIsKept() {
            // The one that matters most. "teleport-in: false" in 1.2.2 meant "denied for strangers" — owners
            // and trusted players were waved through in the listener. Read literally it locks the owner out
            // of their own home, and they would have no idea why.
            assertThat(claim.flagOverride(LandFlag.TELEPORT_IN, LandAudience.OWNER))
                    .as("the owner was exempt in code and must stay exempt")
                    .contains(true);
            assertThat(claim.flagOverride(LandFlag.TELEPORT_IN, LandAudience.TRUSTED)).contains(true);
            assertThat(claim.flagOverride(LandFlag.TELEPORT_IN, LandAudience.VISITOR))
                    .as("and the setting still means what it said, for the people it was aimed at")
                    .contains(false);
        }

        @Test
        void anAreaWideFlagIsNotGivenAnExemptionItNeverHad() {
            // Fire does not spread for some onlookers and not others, so there is nothing to exempt.
            for (LandAudience audience : LandAudience.values()) {
                assertThat(claim.flagOverride(LandFlag.FIRE_SPREAD, audience)).contains(false);
            }
        }
    }

    @Nested
    @DisplayName("the perks")
    class Perks {

        @Test
        void thePantrySurvivesWithItsThreshold() {
            assertThat(claim.pantry().enabled()).isTrue();
            assertThat(claim.pantry().threshold()).isEqualTo(6);
            assertThat(claim.pantry().allowDeposits()).isTrue();
        }

        @Test
        void feedVisitorsBecomesWhoThePantryServes() {
            // Two booleans became one general "who does this serve". An owner who had switched visitors off
            // must not find strangers being fed from their larder after an upgrade.
            assertThat(claim.featureServes(ClaimFeature.PANTRY, LandAudience.VISITOR)).isFalse();
            assertThat(claim.featureServes(ClaimFeature.PANTRY, LandAudience.OWNER)).isTrue();
            assertThat(claim.featureServes(ClaimFeature.PANTRY, LandAudience.TRUSTED)).isTrue();
        }

        @Test
        void equipVisitorsBecomesWhoAutoEquipServes() {
            assertThat(claim.equipment().enabled()).isTrue();
            assertThat(claim.featureServes(ClaimFeature.AUTO_EQUIP, LandAudience.VISITOR)).isFalse();
            assertThat(claim.featureServes(ClaimFeature.AUTO_EQUIP, LandAudience.OWNER)).isTrue();
        }

        @Test
        void theFenceSurvivesWithItsMaterial() {
            assertThat(claim.fence().enabled()).isTrue();
            assertThat(claim.fence().material().name()).isEqualTo("SPRUCE_FENCE");
        }

        @Test
        void theAtmosphereSurvives() {
            assertThat(claim.atmosphere().weather().key()).isEqualTo("clear");
            assertThat(claim.atmosphere().timePreset().key()).isEqualTo("noon");
            assertThat(claim.atmosphere().customTicks()).isEqualTo(6000);
        }
    }

    @Nested
    @DisplayName("what was paid, which decides what a resize refunds")
    class Payment {

        @Test
        void theSplitFiguresSurvive() {
            assertThat(claim.paidCostType()).isEqualTo(CostType.XP_LEVELS);
            assertThat(claim.paidAmount()).isEqualTo(20);
            assertThat(claim.paidArea()).isEqualTo(900L);
            assertThat(claim.settledAmount()).isEqualTo(20);
            assertThat(claim.hasRecordedPayment()).isTrue();
        }

        @Test
        void aResizeStillSettlesAgainstTheOriginal() {
            // The baseline is what makes shrinking and growing back cost nothing. Losing it on upgrade would
            // let somebody farm the difference.
            assertThat(claim.targetAmountFor(450L)).isEqualTo(10);
            assertThat(claim.targetAmountFor(900L)).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("the entry fee and the titles")
    class Extras {

        @Test
        void theFeeSurvives() {
            assertThat(claim.entryFee().rawEnabled()).isTrue();
            assertThat(claim.entryFee().type()).isEqualTo(CostType.XP_LEVELS);
            assertThat(claim.entryFee().amount()).isEqualTo(3);
            assertThat(claim.entryFee().passDurationSeconds()).isEqualTo(600);
        }

        @Test
        void theBankSurvives() {
            assertThat(claim.bank().experiencePoints()).isEqualTo(120);
        }

        @Test
        void theEnterTitleSurvivesWithItsStyling() {
            assertThat(claim.titles().hasEnterTitle()).isTrue();
            assertThat(claim.titles().enterTitle().raw()).isEqualTo("Ahornhof");
            assertThat(claim.titles().enterTitle().bold()).isTrue();
        }
    }

    @Test
    @DisplayName("read, written in the current shape, and read again gives the same claim")
    void itSurvivesTheRoundTripItIsAboutToBeGiven() {
        // What actually happens on an upgraded server: the old file is read, marked dirty, and written back in
        // the current format. If that round trip loses anything, it is lost for good — the old file is gone.
        Claim rewritten = load(ClaimStorage.describe(claim).saveToString());

        assertThat(rewritten).isNotNull();
        assertThat(rewritten.name()).isEqualTo(claim.name());
        assertThat(rewritten.owners()).isEqualTo(claim.owners());
        assertThat(rewritten.members().keySet()).isEqualTo(claim.members().keySet());
        assertThat(rewritten.bans().keySet()).isEqualTo(claim.bans().keySet());
        assertThat(rewritten.publicPermissions()).isEqualTo(claim.publicPermissions());
        assertThat(rewritten.paidAmount()).isEqualTo(claim.paidAmount());
        assertThat(rewritten.paidArea()).isEqualTo(claim.paidArea());
        assertThat(rewritten.pantry().threshold()).isEqualTo(claim.pantry().threshold());

        for (LandAudience audience : LandAudience.values()) {
            assertThat(rewritten.flagOverride(LandFlag.TELEPORT_IN, audience))
                    .as("the exemption survives being written in the new shape, for %s", audience)
                    .isEqualTo(claim.flagOverride(LandFlag.TELEPORT_IN, audience));
        }
        assertThat(rewritten.featureServes(ClaimFeature.PANTRY, LandAudience.VISITOR))
                .as("and so does who the pantry serves")
                .isFalse();
    }
}
