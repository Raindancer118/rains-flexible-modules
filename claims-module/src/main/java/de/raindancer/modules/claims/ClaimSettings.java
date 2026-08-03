package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.BroadcastScope;
import de.raindancer.modules.claims.model.CostType;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.Icon;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Everything a server owner can decide about claims, as one record.
 *
 * <h2>Why a record and not a settings class</h2>
 * The thing this replaces was 835 lines that held every setting, and also parsed, wrote, validated and
 * migrated them — and every call site was stringly typed, so a typo was a runtime failure with no
 * find-usages and no compiler to catch it. Here the record <em>is</em> the schema: {@code config.yml}, its
 * comments, validation, tab completion and the settings GUI are all derived from it, so there is nothing to
 * keep in sync and no second copy to fail a test over. The defaults are real Java in {@link #DEFAULTS},
 * checked by the compiler rather than being untyped literals in a list.
 *
 * <h2>Why every component carries a {@link Key}</h2>
 * Because servers already have a {@code config.yml}. The paths below are the ones the old plugin wrote, so a
 * server that upgrades keeps its limits, its costs and its fence height instead of quietly reverting to the
 * defaults — which for {@code max-claims-per-player} would mean handing everybody three more claims.
 *
 * <p>What is <b>not</b> here: flags and their policies. Those went to Core with the enforcement, and are
 * read through {@code RainsCore.land().flags()} — see {@code core.world.protection.LandPolicy}.
 */
@Settings(id = "claims", topics = {
        @Topic(path = "management", title = "Claims", icon = Material.GRASS_BLOCK),
        @Topic(path = "management/limits", title = "Limits", icon = Material.BARRIER),
        @Topic(path = "management/cost", title = "What a claim costs", icon = Material.GOLD_INGOT),
        @Topic(path = "management/selection", title = "Marking one out", icon = Material.GOLDEN_SHOVEL),
        @Topic(path = "management/fences", title = "Fences", icon = Material.OAK_FENCE),
        @Topic(path = "management/entry-fee", title = "Entry fees", icon = Material.GOLD_NUGGET),
        @Topic(path = "appearance", title = "How claims look", icon = Material.SPYGLASS),
        @Topic(path = "appearance/borders", title = "Borders", icon = Material.GLOWSTONE_DUST),
        @Topic(path = "appearance/notices", title = "Arriving and leaving", icon = Material.PAPER),
        @Topic(path = "player", title = "What owners may do", icon = Material.PLAYER_HEAD),
        @Topic(path = "player/perks", title = "Perks", icon = Material.BREWING_STAND),
        @Topic(path = "player/announcements", title = "Announcements", icon = Material.BELL),
})
public record ClaimSettings(

        // ───────────────────────────────────────────────────────────── limits

        @In("management/limits") @Title("Claims per player") @Range(min = 0, max = 1000)
        @Describe("How many claims somebody may hold. A permission can raise it for individuals.")
        @Key("limits.max-claims-per-player")
        int maxClaimsDefault,

        @In("management/limits") @Title("Smallest claim") @Range(min = 1, max = 1_000_000)
        @Describe("In blocks of ground. Below this a selection is refused.")
        @Key("limits.min-area-blocks")
        int minClaimArea,

        @In("management/limits") @Title("Largest claim")
        @Describe("In blocks of ground. -1 for no limit.")
        @Key("limits.max-area-blocks")
        long maxClaimArea,

        @In("management/limits") @Title("Most corners") @Range(min = 3, max = 256)
        @Describe("A claim's outline may have this many vertices. More costs more to check on every step.")
        @Key("limits.max-vertices")
        int maxVertices,

        @In("management/limits") @Title("Shortest claim") @Range(min = 1, max = 384)
        @Describe("How few blocks tall a claim may be.")
        @Key("limits.min-height")
        int minClaimHeight,

        @In("management/limits") @Title("Claims may not overlap")
        @Describe("Off lets two claims share ground, which makes 'who may build here' ambiguous.")
        @Key("limits.claims-must-not-overlap")
        boolean allowOverlappingWorldsOnly,

        // ───────────────────────────────────────────────────────────── what a claim costs

        @In("management/cost") @Title("Paid in") @Icon(Material.GOLD_INGOT)
        @Describe("Nothing, experience levels, or an item somebody hands over.")
        @Key("creation-cost.type")
        CostType creationCostType,

        @In("management/cost") @Title("How much") @Range(min = 1, max = 100_000)
        @Describe("Levels, or how many of the item.")
        @Key("creation-cost.amount")
        int creationCostAmount,

        @In("management/cost") @Title("Scale with size")
        @Describe("On charges per block of ground rather than a flat price.")
        @Key("creation-cost.scale-with-area")
        boolean creationCostPerBlock,

        @In("management/cost") @Title("Blocks per unit") @Range(min = 1, max = 1_000_000)
        @Describe("With scaling on, how much ground one unit of the price buys.")
        @Key("creation-cost.blocks-per-unit")
        int creationCostBlocksPerUnit,

        @In("management/cost") @Title("Refund on delete")
        @Describe("Give back what was paid when somebody deletes their own claim.")
        @Key("creation-cost.refund-on-delete")
        boolean refundOnDelete,

        @In("management/cost") @Title("Refund rate when shrinking")
        @Describe("A fraction between 0 and 1. Applied to the difference, proportionally to the original "
                + "payment rather than to today's price.")
        @Key("creation-cost.shrink-refund-rate")
        double shrinkRefundRate,

        @In("management/cost") @Title("Charge for growing")
        @Describe("Off lets an owner resize outwards for nothing.")
        @Key("creation-cost.charge-on-grow")
        boolean chargeOnGrow,

        // ───────────────────────────────────────────────────────────── entry fees

        @In("management/entry-fee") @Title("Most an owner may charge") @Range(min = 1, max = 10_000)
        @Key("entry-fee.max-amount")
        int entryFeeMaxAmount,

        @In("management/entry-fee") @Title("Wait after declining") @Range(min = 1, max = 3600)
        @Describe("Seconds before somebody who said no is asked again.")
        @Key("entry-fee.decline-cooldown-seconds")
        int entryFeeDeclineCooldownSeconds,

        @In("management/entry-fee") @Title("How long the question stands") @Range(min = 5, max = 600)
        @Key("entry-fee.prompt-timeout-seconds")
        int entryFeePromptTimeoutSeconds,

        @In("management/entry-fee") @Title("Trusted players pay nothing")
        @Key("entry-fee.exempt-trusted")
        boolean entryFeeExemptTrusted,

        @In("management/entry-fee") @Title("Admins pay nothing")
        @Describe("Off makes an admin walking through pay like anybody else, which is usually what you "
                + "want while testing.")
        @Key("entry-fee.exempt-admins")
        boolean entryFeeExemptAdmins,

        // ───────────────────────────────────────────────────────────── fences

        @In("management/fences") @Title("Build one on creation")
        @Key("fence.auto-build-on-create")
        boolean fenceAutoBuild,

        @In("management/fences") @Title("Owner supplies the blocks")
        @Describe("On takes the fence material out of their claim's bank.")
        @Key("fence.charge-material")
        boolean fenceChargeMaterial,

        @In("management/fences") @Title("Default material") @Icon(Material.OAK_FENCE)
        @Key("fence.default-material")
        Material fenceDefaultMaterial,

        @In("management/fences") @Title("Height") @Range(min = 1, max = 4)
        @Key("fence.height")
        int fenceHeight,

        @In("management/fences") @Title("Columns per build") @Range(min = 16, max = 100_000)
        @Describe("A cap on how much fence goes up in one go, so a huge claim does not stall a tick.")
        @Key("fence.max-columns-per-build")
        int fenceMaxColumns,

        @In("management/fences") @Title("Largest step") @Range(min = 1, max = 16)
        @Describe("How far a fence may climb between neighbouring columns before it gives up.")
        @Key("fence.max-step")
        int fenceMaxStep,

        @In("management/fences") @Title("Refund into the bank")
        @Describe("Taking a fence down puts its blocks back into the claim's bank.")
        @Key("fence.refund-to-bank")
        boolean fenceRefundToBank,

        // ───────────────────────────────────────────────────────────── perks

        @In("player/perks") @Title("Effects per claim") @Range(min = 1, max = 32)
        @Key("effects.max-per-claim")
        int maxClaimEffects,

        @In("player/perks") @Title("Strongest effect") @Range(min = 0, max = 4)
        @Describe("Amplifier. 0 is level I.")
        @Key("effects.max-amplifier")
        int maxEffectAmplifier,

        @In("player/perks") @Title("Effects need potions")
        @Describe("On makes an owner stock potions for the effects their claim grants.")
        @Key("effects.require-potions")
        boolean effectsRequirePotions,

        @In("player/perks") @Title("Minutes one potion buys") @Range(min = 0, max = 1440)
        @Key("effects.potion-minutes")
        int effectPotionMinutes,

        @In("player/perks") @Title("Potion store size") @Range(min = 1, max = 2000)
        @Describe("In stacks.")
        @Key("effects.potion-store-max-stacks")
        int potionStoreMaxStacks,

        @In("player/perks") @Title("Thunder in claim weather")
        @Describe("Whether a claim showing its own storm gets lightning with it.")
        @Key("atmosphere.thunder-bolts")
        boolean claimThunderBolts,

        @In("player/perks") @Title("Effects owners may never grant")
        @Describe("Harmful ones are blocked out of the box: a claim that quietly poisons its visitors "
                + "would be a trap, not a perk.")
        @Key("atmosphere.blocked")
        List<String> blockedEffects,

        @In("player/perks") @Title("Auto-equip rules") @Range(min = 1, max = 64)
        @Key("equipment.max-rules")
        int maxEquipRules,

        @In("player/perks") @Title("Equipment store size") @Range(min = 1, max = 2000)
        @Key("equipment.max-stacks")
        int equipmentMaxStacks,

        @In("player/perks") @Title("Pantry size") @Range(min = 1, max = 2000)
        @Key("pantry.max-stacks")
        int pantryMaxStacks,

        // ───────────────────────────────────────────────────────────── announcements

        @In("player/announcements") @Title("How far an announcement carries") @Range(min = 0, max = 1000)
        @Describe("In blocks, for the 'nearby' scope.")
        @Key("broadcasts.nearby-radius")
        int broadcastNearbyRadius,

        @In("player/announcements") @Title("Who hears them") @Icon(Material.BELL)
        @Key("broadcasts.scope")
        BroadcastScope broadcastScope,

        @In("player/announcements") @Title("Announce kicks")
        @Key("broadcasts.kick")
        boolean broadcastKick,

        @In("player/announcements") @Title("Announce bans")
        @Key("broadcasts.ban")
        boolean broadcastBan,

        @In("player/announcements") @Title("Announce timeouts")
        @Key("broadcasts.timeout")
        boolean broadcastTimeout,

        @In("player/announcements") @Title("Announce when one is lifted")
        @Key("broadcasts.lift")
        boolean broadcastLift,

        // ───────────────────────────────────────────────────────────── arriving and leaving

        @In("appearance/notices") @Title("Notices on the action bar")
        @Describe("Off puts them in chat. The action bar suits something that only matters for the second "
                + "it is shown.")
        @Key("notifications.use-action-bar")
        boolean enterMessageActionBar,

        @In("appearance/notices") @Title("Border flash on entry") @Range(min = 0, max = 60)
        @Describe("Seconds. 0 switches it off.")
        @Key("notifications.border-on-enter-seconds")
        int borderOnEnterSeconds,

        @In("appearance/notices") @Title("Quiet period between notices") @Range(min = 0, max = 600)
        @Describe("Seconds. Stops somebody pacing a border being told about it twenty times a second.")
        @Key("notifications.cooldown-seconds")
        int notificationCooldownSeconds,

        // ───────────────────────────────────────────────────────────── borders

        @In("appearance/borders") @Title("How long a border stays up") @Range(min = 1, max = 300)
        @Describe("Seconds.")
        @Key("visualisation.duration-seconds")
        int visualDurationSeconds,

        @In("appearance/borders") @Title("How far borders are drawn") @Range(min = 8, max = 256)
        @Describe("In blocks from the player. Beyond this the outline is not sent.")
        @Key("visualisation.radius")
        int visualRadius,

        @In("appearance/borders") @Title("Spacing") @Range(min = 1, max = 16)
        @Describe("Every nth block of the outline. Higher is cheaper and sparser.")
        @Key("visualisation.spacing")
        int visualSpacing,

        @In("appearance/borders") @Title("Blocks per tick") @Range(min = 50, max = 20_000)
        @Describe("A cap, so outlining a huge claim does not cost a tick.")
        @Key("visualisation.max-points")
        int visualMaxPointsPerTick,

        @In("appearance/borders") @Title("Corner pillars")
        @Key("visualisation.corner-pillars")
        boolean visualShowVerticalPillars,

        @In("appearance/borders") @Title("Drawn with") @Icon(Material.GLOWSTONE_DUST)
        @Describe("Particles, blocks only the player sees, or both.")
        @Key("visualisation.mode")
        VisualMode visualMode,

        @In("appearance/borders") @Title("Edge block") @Icon(Material.GOLD_BLOCK)
        @Describe("What the outline of a border is drawn with.")
        @Key("visualisation.edge-block")
        Material visualEdgeBlock,

        @In("appearance/borders") @Title("Corner block") @Icon(Material.GLOWSTONE)
        @Key("visualisation.corner-block")
        Material visualCornerBlock,

        @In("appearance/borders") @Title("No-claim-zone block") @Icon(Material.REDSTONE_BLOCK)
        @Key("visualisation.zone-block")
        Material visualZoneBlock,

        // ───────────────────────────────────────────────────────────── marking one out

        @In("management/selection") @Title("The marking tool") @Icon(Material.STICK)
        @Key("selection.stick-material")
        Material selectionStickMaterial,

        @In("management/selection") @Title("Marker block") @Icon(Material.SEA_LANTERN)
        @Describe("What a marked corner is shown as while somebody is drawing.")
        @Key("visualisation.selection-marker-block")
        Material selectionMarkerBlock,

        @In("management/selection") @Title("How tall a new claim is") @Icon(Material.LADDER)
        @Describe("Bedrock to build limit, exactly what was clicked, or what was clicked plus the "
                + "margins below.")
        @Key("selection.vertical-mode")
        VerticalMode verticalMode,

        @In("management/selection") @Title("The tool glints")
        @Key("selection.stick-glint")
        boolean selectionStickGlint,

        @In("management/selection") @Title("Depth below the selection") @Range(min = 0, max = 320)
        @Describe("How far down a claim reaches from where it was drawn.")
        @Key("selection.padding-down")
        int verticalPaddingDown,

        @In("management/selection") @Title("Height above the selection") @Range(min = 0, max = 320)
        @Key("selection.padding-up")
        int verticalPaddingUp,

        @In("management/selection") @Title("Claims underground")
        @Describe("Off refuses a claim whose top is below the surface.")
        @Key("selection.allow-underground-claims")
        boolean allowUndergroundClaims,

        @In("management/selection") @Title("Quiet for buried claims")
        @Describe("Stops a claim under somebody's feet announcing itself as they walk over it.")
        @Key("selection.mute-notifications-for-hidden-claims")
        boolean hiddenUndergroundNotificationsMuted,

        // ───────────────────────────────────────────────────────────── housekeeping

        @In("management") @Title("Write claims every") @Range(min = 30, max = 3600)
        @Describe("Seconds. Claims are also written on shutdown.")
        @Key("storage.auto-save-seconds")
        int autoSaveSeconds,

        @In("management") @Title("Worlds claims are switched off in")
        @Describe("A world named here refuses new claims. Existing ones keep protecting.")
        @Key("worlds.disabled")
        List<String> disabledWorlds,

        @In("management/cost") @Title("The item somebody hands over")
        @Describe("Encoded. Set through the menu rather than by hand — an item is more than a material.")
        @Key("creation-cost.item")
        String creationCostItemEncoded,

        @In("management") @Title("Log what the module is thinking")
        @Describe("Verbose. For working out why a claim is or is not protecting something.")
        @Key("debug")
        boolean debug
) {

    /** How the outline of a border is drawn. */
    public enum VisualMode {
        PARTICLES, BLOCKS, BOTH;

        public VisualMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** How the height of a new claim is worked out from what somebody clicked. */
    public enum VerticalMode {
        /** Bedrock to build limit. */
        FULL_HEIGHT,
        /** Exactly the Y values they clicked, which is what an underground claim needs. */
        SELECTION,
        /** What they clicked, padded by the configured margins. */
        SELECTION_PADDED;

        public VerticalMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /**
     * What a server that has said nothing gets.
     *
     * <p>Every value is the one the old plugin defaulted to, with one deliberate exception. That rule is not
     * nostalgia: an existing server whose {@code config.yml} does not mention a setting must behave tomorrow
     * the way it behaved yesterday, and a changed default here is a silent change to every server that never
     * had an opinion.
     *
     * <p>The exception is {@code notifications.use-action-bar}, which the old plugin defaulted to off. A border
     * notice matters for the second it is shown and then never again; in chat it pushes real conversation up
     * the screen, and walking home across three claims leaves a wall of them. A server that has an opinion
     * keeps it — the key is unchanged — so this only reaches servers that never chose.
     */
    public static final ClaimSettings DEFAULTS = new ClaimSettings(
            3, 9, -1L, 32, 3, true,
            CostType.NONE, 1, false, 256, false, 1.0D, true,
            64, 10, 30, true, false,
            false, true, Material.OAK_FENCE, 1, 2048, 4, true,
            3, 1, false, 30, 270, true,
            List.of("wither", "poison", "instant_damage", "blindness", "nausea", "levitation", "slowness",
                    "mining_fatigue", "weakness", "hunger", "darkness", "unluck", "bad_omen", "infested",
                    "oozing", "weaving", "wind_charged", "trial_omen", "raid_omen"),
            8, 270, 270,
            96, BroadcastScope.CLAIM, true, true, true, true,
            true, 4, 3,
            12, 48, 2, 600, true, VisualMode.PARTICLES,
            Material.GOLD_BLOCK, Material.GLOWSTONE, Material.REDSTONE_BLOCK,
            Material.STICK, Material.SEA_LANTERN, VerticalMode.SELECTION_PADDED,
            true, 8, 16, true, true,
            300, List.of(), "", false);

    /**
     * How many claims this player may hold.
     *
     * <p>A permission may raise it for individuals: {@code rec.maxclaims.unlimited}, or
     * {@code rec.maxclaims.<n>} for a number. The highest number they hold wins, so stacking two ranks does
     * something sensible rather than depending on which was checked first.
     */
    public int maxClaimsFor(Player player) {
        if (player.hasPermission("rec.maxclaims.unlimited")) {
            return Integer.MAX_VALUE;
        }
        int highest = -1;
        for (var attached : player.getEffectivePermissions()) {
            if (!attached.getValue()) {
                continue;
            }
            String node = attached.getPermission().toLowerCase(java.util.Locale.ROOT);
            if (!node.startsWith("rec.maxclaims.")) {
                continue;
            }
            try {
                highest = Math.max(highest, Integer.parseInt(node.substring("rec.maxclaims.".length())));
            } catch (NumberFormatException notANumber) {
                // rec.maxclaims.unlimited, or somebody's typo. Neither is a limit.
            }
        }
        return highest >= 0 ? highest : maxClaimsDefault;
    }

    /**
     * Whether claims work in this world at all.
     *
     * <p>Named worlds refuse <em>new</em> claims; the ones already there keep protecting. Switching a world
     * off must not silently unprotect what people already built in it.
     */
    public boolean worldEnabled(String worldName) {
        if (worldName == null) {
            return false;
        }
        for (String disabled : disabledWorlds) {
            if (disabled.equalsIgnoreCase(worldName)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an owner may never grant this effect.
     *
     * <p>Checked against the potion effect's own key (e.g. {@code wither}), case-insensitively — the list
     * a server writes is meant to be typed by hand, not looked up in the registry first.
     */
    public boolean isEffectBlocked(String effectKey) {
        if (effectKey == null) {
            return false;
        }
        for (String blocked : blockedEffects) {
            if (blocked.equalsIgnoreCase(effectKey)) {
                return true;
            }
        }
        return false;
    }

    /** The item a claim is paid for with, or null when it is not paid for with one. */
    public ItemStack creationCostItem() {
        return creationCostType == CostType.ITEM
                ? de.raindancer.core.data.nbt.ItemText.decode(creationCostItemEncoded)
                : null;
    }

    /**
     * The same settings with one value changed.
     *
     * <p>There are two of these rather than a full builder because a record with fifty-seven components has a
     * positional constructor, and anything that spells all fifty-seven out is a mis-ordering waiting to happen —
     * it has already happened twice while this was being written. A caller that wants to vary one thing should
     * not have to restate the rest.
     */
    public ClaimSettings withMaxClaimArea(long blocks) {
        return new ClaimSettings(maxClaimsDefault, minClaimArea, blocks, maxVertices, minClaimHeight,
                allowOverlappingWorldsOnly, creationCostType, creationCostAmount, creationCostPerBlock,
                creationCostBlocksPerUnit, refundOnDelete, shrinkRefundRate, chargeOnGrow, entryFeeMaxAmount,
                entryFeeDeclineCooldownSeconds, entryFeePromptTimeoutSeconds, entryFeeExemptTrusted,
                entryFeeExemptAdmins, fenceAutoBuild, fenceChargeMaterial, fenceDefaultMaterial, fenceHeight,
                fenceMaxColumns, fenceMaxStep, fenceRefundToBank, maxClaimEffects, maxEffectAmplifier,
                effectsRequirePotions, effectPotionMinutes, potionStoreMaxStacks, claimThunderBolts, blockedEffects,
                maxEquipRules, equipmentMaxStacks, pantryMaxStacks, broadcastNearbyRadius, broadcastScope,
                broadcastKick, broadcastBan, broadcastTimeout, broadcastLift, enterMessageActionBar,
                borderOnEnterSeconds, notificationCooldownSeconds, visualDurationSeconds, visualRadius,
                visualSpacing, visualMaxPointsPerTick, visualShowVerticalPillars, visualMode, visualEdgeBlock,
                visualCornerBlock, visualZoneBlock, selectionStickMaterial, selectionMarkerBlock, verticalMode,
                selectionStickGlint, verticalPaddingDown, verticalPaddingUp, allowUndergroundClaims,
                hiddenUndergroundNotificationsMuted, autoSaveSeconds, disabledWorlds,
                creationCostItemEncoded, debug);
    }

    /** The same, for the refund rate — the other value a caller genuinely wants to vary on its own. */
    public ClaimSettings withShrinkRefundRate(double rate) {
        return new ClaimSettings(maxClaimsDefault, minClaimArea, maxClaimArea, maxVertices, minClaimHeight,
                allowOverlappingWorldsOnly, creationCostType, creationCostAmount, creationCostPerBlock,
                creationCostBlocksPerUnit, refundOnDelete, rate, chargeOnGrow, entryFeeMaxAmount,
                entryFeeDeclineCooldownSeconds, entryFeePromptTimeoutSeconds, entryFeeExemptTrusted,
                entryFeeExemptAdmins, fenceAutoBuild, fenceChargeMaterial, fenceDefaultMaterial, fenceHeight,
                fenceMaxColumns, fenceMaxStep, fenceRefundToBank, maxClaimEffects, maxEffectAmplifier,
                effectsRequirePotions, effectPotionMinutes, potionStoreMaxStacks, claimThunderBolts, blockedEffects,
                maxEquipRules, equipmentMaxStacks, pantryMaxStacks, broadcastNearbyRadius, broadcastScope,
                broadcastKick, broadcastBan, broadcastTimeout, broadcastLift, enterMessageActionBar,
                borderOnEnterSeconds, notificationCooldownSeconds, visualDurationSeconds, visualRadius,
                visualSpacing, visualMaxPointsPerTick, visualShowVerticalPillars, visualMode, visualEdgeBlock,
                visualCornerBlock, visualZoneBlock, selectionStickMaterial, selectionMarkerBlock, verticalMode,
                selectionStickGlint, verticalPaddingDown, verticalPaddingUp, allowUndergroundClaims,
                hiddenUndergroundNotificationsMuted, autoSaveSeconds, disabledWorlds,
                creationCostItemEncoded, debug);
    }

    /** The same, for the disabled-world list — the third thing a caller varies on its own. */
    public ClaimSettings withDisabledWorlds(List<String> worlds) {
        return new ClaimSettings(maxClaimsDefault, minClaimArea, maxClaimArea, maxVertices, minClaimHeight,
                allowOverlappingWorldsOnly, creationCostType, creationCostAmount, creationCostPerBlock,
                creationCostBlocksPerUnit, refundOnDelete, shrinkRefundRate, chargeOnGrow, entryFeeMaxAmount,
                entryFeeDeclineCooldownSeconds, entryFeePromptTimeoutSeconds, entryFeeExemptTrusted,
                entryFeeExemptAdmins, fenceAutoBuild, fenceChargeMaterial, fenceDefaultMaterial, fenceHeight,
                fenceMaxColumns, fenceMaxStep, fenceRefundToBank, maxClaimEffects, maxEffectAmplifier,
                effectsRequirePotions, effectPotionMinutes, potionStoreMaxStacks, claimThunderBolts, blockedEffects,
                maxEquipRules, equipmentMaxStacks, pantryMaxStacks, broadcastNearbyRadius, broadcastScope,
                broadcastKick, broadcastBan, broadcastTimeout, broadcastLift, enterMessageActionBar,
                borderOnEnterSeconds, notificationCooldownSeconds, visualDurationSeconds, visualRadius,
                visualSpacing, visualMaxPointsPerTick, visualShowVerticalPillars, visualMode, visualEdgeBlock,
                visualCornerBlock, visualZoneBlock, selectionStickMaterial, selectionMarkerBlock, verticalMode,
                selectionStickGlint, verticalPaddingDown, verticalPaddingUp, allowUndergroundClaims,
                hiddenUndergroundNotificationsMuted, autoSaveSeconds, worlds,
                creationCostItemEncoded, debug);
    }

    /** Whether a claim of this many blocks is within the configured limits. */
    public boolean areaIsAllowed(long area) {
        if (area < minClaimArea) {
            return false;
        }
        return maxClaimArea <= 0 || area <= maxClaimArea;
    }

    /** Refund rate, clamped — a file saying 4.0 must not pay out four times what was taken. */
    public double refundRate() {
        return Math.max(0.0D, Math.min(1.0D, shrinkRefundRate));
    }
}
