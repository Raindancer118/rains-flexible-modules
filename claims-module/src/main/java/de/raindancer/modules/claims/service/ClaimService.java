package de.raindancer.modules.claims.service;

import de.raindancer.modules.claims.ClaimSettings;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.model.CostType;
import de.raindancer.modules.claims.model.NoClaimZone;
import de.raindancer.modules.claims.rules.ClaimRights;
import de.raindancer.modules.claims.rules.Features;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.store.ClaimStorage;
import de.raindancer.modules.claims.store.ZoneRegistry;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Creation, validation, resizing and deletion of claims plus the persistence orchestration.
 * <p>
 * All disk writes are pushed onto the async scheduler; the YAML tree is built on the calling thread so
 * the async task never touches live Bukkit objects.
 */
public final class ClaimService {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_\\-]{3,24}");

    /** Why a claim could not be created. */
    public enum Failure {
        WORLD_DISABLED,
        SELECTION_INCOMPLETE,
        NAME_INVALID,
        NAME_TAKEN,
        TOO_MANY_CLAIMS,
        TOO_SMALL,
        TOO_LARGE,
        TOO_MANY_VERTICES,
        OVERLAPS_CLAIM,
        IN_NO_CLAIM_ZONE,
        CANNOT_AFFORD,
        UNDERGROUND_DISALLOWED
    }

    public record Result(boolean success, Failure failure, Claim claim, String detail) {
        public static Result ok(Claim claim) {
            return new Result(true, null, claim, "");
        }

        public static Result fail(Failure failure, String detail) {
            return new Result(false, failure, null, detail);
        }
    }

    private final Plugin plugin;
    private final Logger logger;
    private final ClaimRegistry registry;
    private final ZoneRegistry zones;
    private final ClaimStorage storage;
    /** A snapshot, replaced on reload — see settings(ClaimSettings). */
    private volatile ClaimSettings settings;
    private final CostService costs;
    private final ClaimRights rights;
    /** Set after construction — the fence service needs this service, so the cycle is broken here. */
    private FenceService fences;
    private Features features;

    public ClaimService(Plugin plugin, ClaimRegistry registry, ZoneRegistry zones, ClaimStorage storage,
                        ClaimSettings settings, CostService costs, ClaimRights rights) {
        this.rights = rights;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.registry = registry;
        this.zones = zones;
        this.storage = storage;
        this.settings = settings;
        this.costs = costs;
    }

    /**
     * Swaps in the settings as they are now.
     *
     * <p>Called on reload. The field is a snapshot rather than a live view, so nothing here has to think about a
     * value changing halfway through a calculation — and replacing the whole snapshot means a reload takes effect
     * on the next event rather than on the next restart.
     */
    public void settings(ClaimSettings settings) {
        this.settings = settings;
    }

    /** Wired up once the features are known, so a claim can be refused a perk the server took away. */
    public void features(Features features) {
        this.features = features;
    }

    // ------------------------------------------------------------ validation

    public boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    /**
     * Runs every rule except the cost check, which is separate because the GUI wants to preview the
     * price before charging it.
     */
    public Optional<Result> validate(Player player, World world, ClaimShape shape, String name, UUID ignoreClaimId) {
        if (!settings.worldEnabled(world.getName())) {
            return Optional.of(Result.fail(Failure.WORLD_DISABLED, world.getName()));
        }
        if (name != null && !isValidName(name)) {
            return Optional.of(Result.fail(Failure.NAME_INVALID, name));
        }
        if (name != null) {
            // Per owner, not per server: somebody else's "home" is not in the way of this one.
            for (Claim existing : registry.allByName(name)) {
                if (!existing.id().equals(ignoreClaimId) && existing.isOwner(player.getUniqueId())) {
                    return Optional.of(Result.fail(Failure.NAME_TAKEN, name));
                }
            }
        }
        if (shape.vertices().size() > settings.maxVertices()) {
            return Optional.of(Result.fail(Failure.TOO_MANY_VERTICES, String.valueOf(settings.maxVertices())));
        }
        long area = shape.areaBlocks();
        if (area < settings.minClaimArea()) {
            return Optional.of(Result.fail(Failure.TOO_SMALL, String.valueOf(settings.minClaimArea())));
        }
        long maxArea = settings.maxClaimArea();
        if (maxArea > 0 && area > maxArea) {
            return Optional.of(Result.fail(Failure.TOO_LARGE, String.valueOf(maxArea)));
        }
        if (!settings.allowUndergroundClaims()
                && (shape.minY() > world.getMinHeight() || shape.maxY() < world.getMaxHeight() - 1)) {
            return Optional.of(Result.fail(Failure.UNDERGROUND_DISALLOWED, ""));
        }
        Optional<NoClaimZone> zone = zones.firstOverlap(world.getUID(), shape);
        if (zone.isPresent() && !player.hasPermission("rec.admin.zonebypass")) {
            return Optional.of(Result.fail(Failure.IN_NO_CLAIM_ZONE, zone.get().name()));
        }
        if (settings.allowOverlappingWorldsOnly()) {
            Optional<Claim> overlap = firstOverlap(world.getUID(), shape, ignoreClaimId);
            if (overlap.isPresent()) {
                return Optional.of(Result.fail(Failure.OVERLAPS_CLAIM, overlap.get().name()));
            }
        }
        return Optional.empty();
    }

    public Optional<Claim> firstOverlap(UUID worldId, ClaimShape shape, UUID ignoreClaimId) {
        for (Claim candidate : registry.candidatesFor(worldId, shape)) {
            if (candidate.id().equals(ignoreClaimId)) {
                continue;
            }
            if (candidate.shape().intersects(shape)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------ cost

    /** The price of a claim with the given footprint, honouring area scaling. */
    public int creationCostAmount(ClaimShape shape) {
        int base = settings.creationCostAmount();
        if (!settings.creationCostPerBlock()) {
            return base;
        }
        long units = Math.max(1L, shape.areaBlocks() / settings.creationCostBlocksPerUnit());
        long total = base * units;
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public CostType creationCostType() {
        return settings.creationCostType();
    }

    public ItemStack creationCostItem() {
        return settings.creationCostItem();
    }

    // ------------------------------------------------------------ mutations

    /** Creates a claim, charging the configured cost. The caller has already validated the name. */
    public Result create(Player player, World world, ClaimShape shape, String name) {
        Optional<Result> validation = validate(player, world, shape, name, null);
        if (validation.isPresent()) {
            return validation.get();
        }
        if (!player.hasPermission("rec.admin.nolimit")) {
            int limit = settings.maxClaimsFor(player);
            if (registry.countOwned(player.getUniqueId()) >= limit) {
                return Result.fail(Failure.TOO_MANY_CLAIMS, String.valueOf(limit));
            }
        }

        CostType type = settings.creationCostType();
        int amount = creationCostAmount(shape);
        ItemStack item = type == CostType.ITEM ? settings.creationCostItem() : null;
        boolean free = player.hasPermission("rec.admin.nocost") || type == CostType.NONE;
        if (!free) {
            CostService.Charge charge = costs.charge(player, type, amount, item);
            if (!charge.success()) {
                return Result.fail(Failure.CANNOT_AFFORD, charge.shortfallDescription());
            }
        }

        Claim claim = new Claim(UUID.randomUUID(), name, world.getUID(), world.getName(), shape,
                player.getUniqueId());
        if (!free) {
            claim.recordPayment(type, amount, shape.areaBlocks(), item);
        }
        claim.fence().material(settings.fenceDefaultMaterial());
        registry.add(claim);

        // An automatic fence is opt-in for the server; the owner can always build one later themselves.
        if (fences != null && features.isOffered(ClaimFeature.FENCE) && settings.fenceAutoBuild()) {
            claim.fence().enabled(true);
            fences.build(claim, player);
        }
        saveAsync(claim);
        return Result.ok(claim);
    }

    /**
     * Outcome of settling the price difference after a resize.
     *
     * @param refunded  how much went back into the claim bank
     * @param charged   how much was taken from the player
     * @param shortfall non-empty when the player could not pay for the enlargement
     */
    public record Settlement(int refunded, int charged, String shortfall) {
        public static final Settlement NOTHING = new Settlement(0, 0, "");

        public boolean failed() {
            return !shortfall.isEmpty();
        }
    }

    /**
     * Settles the creation cost against a new footprint, strictly proportionally to the recorded payment.
     * <p>
     * Shrinking pays the overpayment into the claim bank (so nothing is lost to a full inventory or an
     * offline owner), growing charges the difference from the player. Nothing is moved before the
     * enlargement is confirmed affordable.
     */
    public Settlement settleResizeCost(Player player, Claim claim, ClaimShape newShape) {
        if (!claim.hasRecordedPayment() || player.hasPermission("rec.admin.nocost")) {
            return Settlement.NOTHING;
        }
        // Both figures come from the untouched original payment, never from the running total. Deriving
        // them from the running total lets a player farm material: every shrink rounds the refund up in
        // their favour, so several small shrinks pay out more than one large one and growing back costs
        // less than was refunded.
        int target = claim.targetAmountFor(newShape.areaBlocks());
        int settled = claim.settledAmount();
        if (target == settled) {
            return Settlement.NOTHING;
        }

        CostType type = claim.paidCostType();
        ItemStack item = claim.paidItem();

        if (target < settled) {
            int overpaid = settled - target;
            int refund = (int) Math.floor(overpaid * settings.shrinkRefundRate());
            // The investment drops to the new target either way; a reduced refund rate is the fee.
            claim.settledAmount(target);
            if (refund <= 0) {
                return Settlement.NOTHING;
            }
            depositRefund(claim, type, refund, item);
            return new Settlement(refund, 0, "");
        }

        if (!settings.chargeOnGrow()) {
            return Settlement.NOTHING;
        }
        int extra = target - settled;
        CostService.Charge charge = costs.charge(player, type, extra, item);
        if (!charge.success()) {
            return new Settlement(0, 0, charge.shortfallDescription());
        }
        claim.settledAmount(target);
        return new Settlement(0, extra, "");
    }

    private void depositRefund(Claim claim, CostType type, int amount, ItemStack item) {
        switch (type) {
            case ITEM -> {
                if (item == null) {
                    return;
                }
                ItemStack stack = item.clone();
                stack.setAmount(amount);
                claim.bank().depositItem(stack);
            }
            case XP_LEVELS -> claim.bank().depositExperience(CostService.totalExperienceForLevel(amount));
            case XP_POINTS -> claim.bank().depositExperience(amount);
            case NONE -> {
            }
        }
        claim.markDirty();
    }

    /** Replaces a claim's footprint and settles the price difference. */
    public Result resize(Player player, Claim claim, World world, ClaimShape shape) {
        Optional<Result> validation = validate(player, world, shape, null, claim.id());
        if (validation.isPresent()) {
            return validation.get();
        }
        Settlement settlement = settleResizeCost(player, claim, shape);
        if (settlement.failed()) {
            return Result.fail(Failure.CANNOT_AFFORD, settlement.shortfall());
        }
        claim.shape(shape);
        registry.reindex(claim);
        // The fence follows the new outline, keeping the owner's gaps and gates.
        if (fences != null) {
            fences.sync(claim, player, "resize");
        }
        saveAsync(claim);
        return new Result(true, null, claim, describeSettlement(settlement));
    }

    private String describeSettlement(Settlement settlement) {
        if (settlement.refunded() > 0) {
            return "refunded:" + settlement.refunded();
        }
        if (settlement.charged() > 0) {
            return "charged:" + settlement.charged();
        }
        return "";
    }

    /**
     * Changes only the vertical range, keeping the footprint.
     * <p>
     * Still validated: a taller claim can start overlapping a stacked neighbour that was previously
     * clear, and the fence has to follow because its columns are clamped to the claim's Y range.
     */
    public Result changeHeight(Player player, Claim claim, int newMinY, int newMaxY) {
        World world = plugin.getServer().getWorld(claim.worldId());
        if (world == null) {
            return Result.fail(Failure.WORLD_DISABLED, claim.worldName());
        }
        int worldMin = world.getMinHeight();
        int worldMax = world.getMaxHeight() - 1;
        int low = Math.max(worldMin, Math.min(worldMax, Math.min(newMinY, newMaxY)));
        int high = Math.max(worldMin, Math.min(worldMax, Math.max(newMinY, newMaxY)));
        if (high - low + 1 < settings.minClaimHeight()) {
            return Result.fail(Failure.TOO_SMALL, settings.minClaimHeight() + " blocks tall");
        }

        ClaimShape candidate = claim.shape().withVerticalRange(low, high);
        Optional<Result> validation = validate(player, world, candidate, null, claim.id());
        if (validation.isPresent()) {
            return validation.get();
        }

        claim.shape(candidate);
        registry.reindex(claim);
        if (fences != null) {
            fences.sync(claim, player, "height-change");
        }
        saveAsync(claim);
        return Result.ok(claim);
    }

    /** Admin shrink that skips the player-facing limits but still refuses overlaps. */
    public Optional<Failure> adminReshape(Claim claim, ClaimShape shape) {
        Optional<Claim> overlap = firstOverlap(claim.worldId(), shape, claim.id());
        if (overlap.isPresent() && settings.allowOverlappingWorldsOnly()) {
            return Optional.of(Failure.OVERLAPS_CLAIM);
        }
        claim.shape(shape);
        registry.reindex(claim);
        if (fences != null) {
            fences.sync(claim, null, "admin-reshape");
        }
        saveAsync(claim);
        return Optional.empty();
    }

    public void rename(Claim claim, String newName) {
        registry.rename(claim, newName);
        saveAsync(claim);
    }

    /** Deletes a claim, optionally refunding what was actually paid to a present owner. */
    public void delete(Claim claim, Player refundTo) {
        // Take the fence down first: once the claim is gone the segment records go with it, and the blocks
        // would be left standing in the world forever. The material is banked synchronously from the
        // records, because the block removal itself only runs on the region schedulers a tick later — far
        // too late for the payout further down.
        if (fences != null && claim.fence().standingCount() > 0) {
            if (settings.fenceRefundToBank()) {
                fences.bankStandingFence(claim);
            }
            fences.tearDown(claim, false);
        }
        if (refundTo != null && settings.refundOnDelete() && claim.isOwner(refundTo.getUniqueId())
                && claim.hasRecordedPayment()) {
            // Straight into the bank first, so the payout below hands it over in one go. Only what is
            // currently invested comes back — earlier shrink refunds were already paid out.
            depositRefund(claim, claim.paidCostType(), claim.settledAmount(), claim.paidItem());
            claim.settledAmount(0);
        }
        // Banked entry fees must not evaporate silently.
        if (refundTo != null && !claim.bank().isEmpty()) {
            for (ItemStack stack : claim.bank().withdrawItems()) {
                costs.giveOrDrop(refundTo, stack);
            }
            int experience = claim.bank().withdrawExperience();
            if (experience > 0) {
                refundTo.giveExp(experience);
            }
        }
        registry.remove(claim);
        UUID id = claim.id();
        Scheduling.async(plugin, () -> {
            try {
                storage.delete(id);
            } catch (IOException exception) {
                logger.log(Level.SEVERE, "Could not delete claim file for " + id, exception);
            }
        });
    }

    // ------------------------------------------------------------ persistence

    /** Serialises on the calling thread, writes off-thread. */
    public void saveAsync(Claim claim) {
        claim.clearDirty();
        Scheduling.async(plugin, () -> {
            try {
                storage.save(claim);
            } catch (IOException exception) {
                claim.markDirty();
                logger.log(Level.SEVERE, "Could not save claim " + claim.name(), exception);
            }
        });
    }

    /** Writes every claim that changed since the last save. Returns how many were written. */
    public int saveDirty() {
        List<Claim> dirty = new ArrayList<>();
        for (Claim claim : registry.all()) {
            if (claim.dirty()) {
                dirty.add(claim);
            }
        }
        for (Claim claim : dirty) {
            saveAsync(claim);
        }
        return dirty.size();
    }

    /** Blocking save of everything — only used on plugin disable. */
    public int saveAllBlocking() {
        int saved = 0;
        for (Claim claim : registry.all()) {
            try {
                storage.save(claim);
                claim.clearDirty();
                saved++;
            } catch (IOException exception) {
                logger.log(Level.SEVERE, "Could not save claim " + claim.name() + " during shutdown", exception);
            }
        }
        return saved;
    }

    public int loadAll() {
        registry.clear();
        List<Claim> loaded = storage.loadAll();
        for (Claim claim : loaded) {
            // Two claims may share a name now, as long as they are not the same person's: that pair
            // could not be told apart even by its owner, so one of them is made unique.
            if (clashesWithOwnClaim(claim)) {
                String unique = claim.name() + "-" + claim.id().toString().substring(0, 4);
                logger.warning("Duplicate claim name '" + claim.name()
                        + "' for the same owner — renaming to '" + unique + "'.");
                claim.name(unique);
            }
            registry.add(claim);
        }
        return loaded.size();
    }

    /** Whether one of this claim's owners already has another claim by the same name. */
    private boolean clashesWithOwnClaim(Claim claim) {
        for (Claim existing : registry.allByName(claim.name())) {
            for (java.util.UUID owner : claim.owners()) {
                if (existing.isOwner(owner)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Refreshes cached world names after a world was renamed or re-created. */
    public void refreshWorldNames() {
        for (Claim claim : registry.all()) {
            World world = plugin.getServer().getWorld(claim.worldId());
            if (world != null && !world.getName().equals(claim.worldName())) {
                claim.worldName(world.getName());
            }
        }
    }

    public String suggestName(Player player) {
        String base = player.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
        if (base.length() < 3) {
            base = "claim";
        }
        if (base.length() > 18) {
            base = base.substring(0, 18);
        }
        int index = registry.countOwned(player.getUniqueId()) + 1;
        String candidate = base + "-" + index;
        while (registry.nameTaken(candidate, player.getUniqueId())) {
            index++;
            candidate = base + "-" + index;
        }
        return candidate;
    }

    public void fences(FenceService fences) {
        this.fences = fences;
    }

    public ClaimRegistry registry() {
        return registry;
    }

    public ZoneRegistry zones() {
        return zones;
    }

    public ClaimStorage storage() {
        return storage;
    }
}
