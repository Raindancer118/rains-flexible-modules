package de.raindancer.modules.claims;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a claim can never be made to wither, poison or otherwise hurt somebody who merely walks in.
 *
 * <h2>Why this test exists</h2>
 * {@code atmosphere.blocked} — the list of potion effects an owner may never grant — existed in the old
 * plugin's {@code config.yml} and was dropped when the settings were rebuilt as {@link ClaimSettings}.
 * Nothing enforced it any more: {@code EffectsMenu}'s picker would have offered every registered potion
 * effect, including Wither and Poison, to any owner who wanted to set a trap for their visitors. This is
 * the one gap in this rebuild with a real griefing consequence, so it gets its own test rather than being
 * folded into the general settings test.
 *
 * <p>{@link ClaimSettings#isEffectBlocked(String)} is exactly what {@code EffectsMenu}'s effect picker
 * calls to decide whether an effect is shown at all — a blocked effect is filtered out of the list, not
 * merely refused after the fact. Testing the predicate here is testing the one piece of logic a picker
 * bug could get wrong.
 */
class EffectBlocklistTest {

    private final ClaimSettings defaults = ClaimSettings.DEFAULTS;

    @Test
    @DisplayName("the old plugin's harmful-effect list survives the rebuild, unchanged")
    void restoresTheOldDefaults() {
        assertThat(defaults.blockedEffects()).containsExactlyInAnyOrder(
                "wither", "poison", "instant_damage", "blindness", "nausea", "levitation", "slowness",
                "mining_fatigue", "weakness", "hunger", "darkness", "unluck", "bad_omen", "infested",
                "oozing", "weaving", "wind_charged", "trial_omen", "raid_omen");
    }

    @Test
    @DisplayName("the key an existing server's config.yml already uses still works")
    void keepsTheOldConfigPath() {
        var schema = de.raindancer.core.data.settings.SettingsSchema.of(ClaimSettings.class, ClaimSettings.DEFAULTS);
        assertThat(schema.settings().stream().map(de.raindancer.core.data.settings.Setting::key).toList())
                .as("an existing server's atmosphere.blocked has to keep meaning the same thing, or "
                        + "upgrading silently unblocks every harmful effect")
                .contains("atmosphere.blocked");
    }

    @Test
    @DisplayName("a blocked effect is blocked regardless of how it is capitalised or spelled with dashes")
    void blockingIsCaseAndSeparatorInsensitive() {
        assertThat(defaults.isEffectBlocked("wither")).isTrue();
        assertThat(defaults.isEffectBlocked("WITHER")).isTrue();
        assertThat(defaults.isEffectBlocked("Poison")).isTrue();
    }

    @Test
    @DisplayName("an effect not on the list is not blocked")
    void everythingElseIsFine() {
        assertThat(defaults.isEffectBlocked("speed")).isFalse();
        assertThat(defaults.isEffectBlocked("jump_boost")).isFalse();
        assertThat(defaults.isEffectBlocked(null)).isFalse();
    }

    @Test
    @DisplayName("a server can still write its own list and have it replace, not extend, the defaults")
    void aServerCanOverrideTheWholeList() {
        ClaimSettings custom = new ClaimSettings(
                defaults.maxClaimsDefault(), defaults.minClaimArea(), defaults.maxClaimArea(),
                defaults.maxVertices(), defaults.minClaimHeight(), defaults.allowOverlappingWorldsOnly(),
                defaults.creationCostType(), defaults.creationCostAmount(), defaults.creationCostPerBlock(),
                defaults.creationCostBlocksPerUnit(), defaults.refundOnDelete(), defaults.shrinkRefundRate(),
                defaults.chargeOnGrow(), defaults.entryFeeMaxAmount(), defaults.entryFeeDeclineCooldownSeconds(),
                defaults.entryFeePromptTimeoutSeconds(), defaults.entryFeeExemptTrusted(),
                defaults.entryFeeExemptAdmins(), defaults.fenceAutoBuild(), defaults.fenceChargeMaterial(),
                defaults.fenceDefaultMaterial(), defaults.fenceHeight(), defaults.fenceMaxColumns(),
                defaults.fenceMaxStep(), defaults.fenceRefundToBank(), defaults.maxClaimEffects(),
                defaults.maxEffectAmplifier(), defaults.effectsRequirePotions(), defaults.effectPotionMinutes(),
                defaults.potionStoreMaxStacks(), defaults.claimThunderBolts(), java.util.List.of("speed"),
                defaults.maxEquipRules(), defaults.equipmentMaxStacks(), defaults.pantryMaxStacks(),
                defaults.broadcastNearbyRadius(), defaults.broadcastScope(), defaults.broadcastKick(),
                defaults.broadcastBan(), defaults.broadcastTimeout(), defaults.broadcastLift(),
                defaults.enterMessageActionBar(), defaults.borderOnEnterSeconds(),
                defaults.notificationCooldownSeconds(), defaults.visualDurationSeconds(), defaults.visualRadius(),
                defaults.visualSpacing(), defaults.visualMaxPointsPerTick(), defaults.visualShowVerticalPillars(),
                defaults.visualMode(), defaults.visualEdgeBlock(), defaults.visualCornerBlock(),
                defaults.visualZoneBlock(), defaults.selectionStickMaterial(), defaults.selectionMarkerBlock(),
                defaults.verticalMode(), defaults.selectionStickGlint(), defaults.verticalPaddingDown(),
                defaults.verticalPaddingUp(), defaults.allowUndergroundClaims(),
                defaults.hiddenUndergroundNotificationsMuted(), defaults.autoSaveSeconds(),
                defaults.disabledWorlds(), defaults.creationCostItemEncoded(), defaults.debug());

        assertThat(custom.isEffectBlocked("speed")).isTrue();
        assertThat(custom.isEffectBlocked("wither"))
                .as("a server that writes its own list means exactly that list, not the list plus its own")
                .isFalse();
    }
}
