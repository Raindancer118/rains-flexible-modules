package de.raindancer.modules.claims.service;

import de.raindancer.modules.claims.ClaimSettings;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.model.ClaimFence;
import de.raindancer.modules.claims.model.ClaimPoint;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.model.FenceSegment;
import de.raindancer.modules.claims.rules.FeatureRules;
import de.raindancer.modules.claims.util.BlockConnector;
import de.raindancer.modules.claims.visual.OutlineGeometry;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds, maintains and tears down the physical fence around a claim.
 * <p>
 * The fence follows the claim: on every reshape, columns that dropped off the outline are taken back down
 * and new ones are put up. Two things are deliberately preserved across that:
 * <ul>
 *   <li>gaps the owner broke open stay open ({@link ClaimFence#suppressed()}),</li>
 *   <li>gates the owner swapped in stay gates, and are re-placed as gates if the terrain changed.</li>
 * </ul>
 * Material is supplied by the owner when the server charges for it, refunds go into the claim bank, and a
 * shortfall simply leaves the rest of the ring unbuilt so it can be finished later.
 * <p>
 * Folia safety: nothing touches the world on the calling thread. Columns are grouped by chunk and each
 * group hops to the region that owns it.
 */
public final class FenceService implements IClaimService {

    @Override
    public String describe() {
        return "building and taking down the fence along a border";
    }


    /**
     * What a build or sync run set in motion.
     * <p>
     * {@code placed} and {@code removed} are the columns handed to the region schedulers. The world work
     * itself finishes a tick or two later; a column the terrain refuses (a tree, a wall, unloaded chunk)
     * has its material paid back into the claim bank at that point, so the numbers here are an upper
     * bound on what ends up standing rather than an exact count.
     */
    public record FenceResult(int placed, int removed, int skipped, int missingMaterial) {

        public static final FenceResult NOTHING = new FenceResult(0, 0, 0, 0);

        public boolean changedAnything() {
            return placed > 0 || removed > 0;
        }

        public boolean incomplete() {
            return missingMaterial > 0;
        }
    }

    private final Plugin plugin;
    /** A snapshot, replaced on reload — see settings(ClaimSettings). */
    private volatile ClaimSettings settings;
    private final ClaimService claims;
    private final FeatureRules features;

    public FenceService(Plugin plugin, ClaimSettings settings, ClaimService claims, FeatureRules features) {
        this.features = features;
        this.plugin = plugin;
        this.settings = settings;
        this.claims = claims;
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

    // ------------------------------------------------------------ queries

    public boolean featureAvailable() {
        return features.isOffered(ClaimFeature.FENCE);
    }

    /**
     * Columns the fence should occupy.
     * <p>
     * Uses the 4-connected ring rather than the interpolated outline: a diagonal step would leave two
     * posts that are only diagonal neighbours, and those can never connect in Minecraft.
     */
    public Set<ClaimPoint> desiredOutline(ClaimShape shape) {
        return new LinkedHashSet<>(OutlineGeometry.connectedRing(shape));
    }

    /** How many blocks a complete fence would still need, ignoring cost. */
    public int pendingColumns(Claim claim) {
        ClaimFence fence = claim.fence();
        int pending = 0;
        for (ClaimPoint point : desiredOutline(claim.shape())) {
            if (!fence.segments().containsKey(point) && !fence.isSuppressed(point)) {
                pending++;
            }
        }
        return pending;
    }

    /** Blocks of material a full build would cost right now. */
    public int estimateCost(Claim claim) {
        return pendingColumns(claim) * settings.fenceHeight();
    }

    // ------------------------------------------------------------ main entry points

    /**
     * Brings the fence in line with the claim's current shape.
     *
     * @param payer  charged for new blocks and told about shortfalls; may be {@code null} for automatic
     *               syncs with no player present, in which case nothing is charged and nothing is built
     * @param reason free-text label used in debug logging
     */
    public void sync(Claim claim, Player payer, String reason) {
        if (!featureAvailable() || !claim.fence().enabled()) {
            return;
        }
        World world = plugin.getServer().getWorld(claim.worldId());
        if (world == null) {
            return;
        }
        Set<ClaimPoint> outline = desiredOutline(claim.shape());
        ClaimFence fence = claim.fence();

        // Columns that fell off the outline after a reshape: take them down and bank the material.
        List<ClaimPoint> obsolete = new ArrayList<>();
        for (ClaimPoint point : fence.segmentPoints()) {
            if (!outline.contains(point)) {
                obsolete.add(point);
            }
        }
        fence.pruneTo(outline);

        int removed = removeColumns(claim, world, obsolete, true);

        // New columns. Without a payer we only tidy up; building would charge nobody.
        if (payer == null) {
            if (removed > 0) {
                claims.saveAsync(claim);
            }
            logDebug(claim, reason, new FenceResult(0, obsolete.size(), 0, 0));
            return;
        }
        FenceResult built = build(claim, payer);
        logDebug(claim, reason, new FenceResult(built.placed(), obsolete.size(), built.skipped(),
                built.missingMaterial()));
    }

    /** Puts up every missing column, as far as the owner can pay for it. */
    public FenceResult build(Claim claim, Player payer) {
        if (!featureAvailable()) {
            return FenceResult.NOTHING;
        }
        World world = plugin.getServer().getWorld(claim.worldId());
        if (world == null) {
            return FenceResult.NOTHING;
        }
        ClaimFence fence = claim.fence();

        // The decision, recorded here rather than by each caller. It was set in exactly one place — claim
        // creation with auto-build on — so an owner who built a fence from the menu got the blocks and left
        // the flag false: the button went on reading "Not built" and the next click built again instead of
        // taking it down. The fence could be put up and never removed.
        //
        // The flag is INTENT, not a block count: "this claim is supposed to have a fence". That is what it
        // has to mean, because sync() refuses to touch a claim whose flag is false — so an owner who asks
        // for a fence while short of material must still end up enabled, or the fence will never be built
        // when the material turns up.
        //
        // Set and saved before the world work. Everything below can return early — nothing to do, nothing
        // affordable, the cap reached — and review caught that those paths skipped the save at the end,
        // leaving a flag that reverted on the next restart.
        if (!fence.enabled()) {
            fence.enabled(true);
            claims.saveAsync(claim);
        }

        Set<ClaimPoint> outline = desiredOutline(claim.shape());
        fence.pruneTo(outline);

        List<ClaimPoint> todo = new ArrayList<>();
        for (ClaimPoint point : outline) {
            if (!fence.segments().containsKey(point) && !fence.isSuppressed(point)) {
                todo.add(point);
            }
        }
        if (todo.isEmpty()) {
            return FenceResult.NOTHING;
        }

        int height = settings.fenceHeight();
        int cap = settings.fenceMaxColumns();
        int skippedByCap = 0;
        if (todo.size() > cap) {
            skippedByCap = todo.size() - cap;
            todo = todo.subList(0, cap);
        }

        // Work out the budget before touching the world, so a half-charged build is impossible.
        int affordableColumns = todo.size();
        int missing = 0;
        if (settings.fenceChargeMaterial() && !payer.hasPermission("rec.admin.nocost")) {
            int available = countMaterial(payer.getInventory(), fence.material());
            affordableColumns = Math.min(todo.size(), available / height);
            missing = (todo.size() - affordableColumns) * height;
            if (affordableColumns <= 0) {
                return new FenceResult(0, 0, skippedByCap, missing);
            }
            takeMaterial(payer.getInventory(), fence.material(), affordableColumns * height);
        }

        List<ClaimPoint> toPlace = new ArrayList<>(todo.subList(0, affordableColumns));
        placeColumns(claim, world, toPlace, height, settings.fenceChargeMaterial());
        claims.saveAsync(claim);
        return new FenceResult(toPlace.size(), 0, skippedByCap, missing);
    }

    /** Takes the whole fence down again, banking the material. */
    public FenceResult tearDown(Claim claim, boolean refundToBank) {
        World world = plugin.getServer().getWorld(claim.worldId());
        if (world == null) {
            return FenceResult.NOTHING;
        }
        List<ClaimPoint> all = new ArrayList<>(claim.fence().segmentPoints());
        int removed = removeColumns(claim, world, all, refundToBank);

        // Down, and recorded as down. A flag left true with no blocks behind it is a fence the next sync run
        // puts straight back up, which reads as the plugin refusing to take it down.
        claim.fence().enabled(false);
        claim.fence().clearSuppressions();
        claims.saveAsync(claim);
        return new FenceResult(0, removed, 0, 0);
    }

    /**
     * Swaps the fence material. Gates keep their own material — the owner placed those by hand and a
     * material change should not undo that decision.
     */
    public FenceResult changeMaterial(Claim claim, Material newMaterial, Player payer) {
        if (!ClaimFence.isFence(newMaterial)) {
            return FenceResult.NOTHING;
        }
        ClaimFence fence = claim.fence();
        if (fence.material() == newMaterial) {
            return FenceResult.NOTHING;
        }
        World world = plugin.getServer().getWorld(claim.worldId());
        if (world == null) {
            fence.material(newMaterial);
            return FenceResult.NOTHING;
        }

        List<ClaimPoint> plain = new ArrayList<>();
        for (Map.Entry<ClaimPoint, FenceSegment> entry : fence.segments().entrySet()) {
            if (!entry.getValue().gate()) {
                plain.add(entry.getKey());
            }
        }

        int height = settings.fenceHeight();
        int needed = plain.size() * height;
        int missing = 0;
        if (settings.fenceChargeMaterial() && !payer.hasPermission("rec.admin.nocost")) {
            int available = countMaterial(payer.getInventory(), newMaterial);
            if (available < needed) {
                // Refuse rather than leave a half-recoloured fence behind.
                return new FenceResult(0, 0, 0, needed - available);
            }
            takeMaterial(payer.getInventory(), newMaterial, needed);
        }

        // The old material is banked so the owner is not out of pocket for the swap.
        int removed = removeColumns(claim, world, plain, settings.fenceChargeMaterial());
        fence.material(newMaterial);
        placeColumns(claim, world, plain, height, settings.fenceChargeMaterial());
        claims.saveAsync(claim);
        return new FenceResult(plain.size(), removed, 0, missing);
    }

    // ------------------------------------------------------------ world work

    /**
     * Places the given columns.
     * <p>
     * Each chunk group runs on the region that owns it, then hops to the global region to write the
     * bookkeeping — so world state and {@link ClaimFence#segments()} are updated from a single thread and
     * cannot drift apart. Columns the terrain refuses have their material paid into the claim bank there.
     */
    private void placeColumns(Claim claim, World world, List<ClaimPoint> columns, int height,
                              boolean refundFailures) {
        if (columns.isEmpty()) {
            return;
        }
        ClaimFence fence = claim.fence();
        ClaimShape shape = claim.shape();
        Material fenceMaterial = fence.material();

        // Gate columns keep the material the owner chose for them.
        Map<ClaimPoint, Material> perColumn = new HashMap<>();
        Map<ClaimPoint, Boolean> gateFlags = new HashMap<>();
        for (ClaimPoint point : columns) {
            Optional<FenceSegment> existing = fence.segmentAt(point);
            if (existing.isPresent() && existing.get().gate()) {
                perColumn.put(point, existing.get().material());
                gateFlags.put(point, true);
            } else {
                perColumn.put(point, fenceMaterial);
                gateFlags.put(point, false);
            }
        }

        // Two passes are needed because a column's height depends on its neighbours: on sloping ground
        // adjacent posts would otherwise sit at different Y and could never touch. The terrain can only
        // be read on the region that owns it, so the heights are gathered first and the blocks placed
        // afterwards, once every base level is known.
        Map<Long, List<ClaimPoint>> byChunk = groupByChunk(columns);
        Map<ClaimPoint, Integer> baseHeights = new ConcurrentHashMap<>();
        AtomicInteger pending = new AtomicInteger(byChunk.size());

        for (Map.Entry<Long, List<ClaimPoint>> group : byChunk.entrySet()) {
            List<ClaimPoint> chunkColumns = group.getValue();
            Location anchor = new Location(world, chunkColumns.get(0).x() + 0.5D,
                    shape.minY(), chunkColumns.get(0).z() + 0.5D);
            Scheduling.region(plugin, anchor, () -> {
                for (ClaimPoint point : chunkColumns) {
                    if (!world.isChunkLoaded(point.x() >> 4, point.z() >> 4)) {
                        continue;
                    }
                    baseHeights.put(point, world.getHighestBlockYAt(point.x(), point.z()) + 1);
                }
                if (pending.decrementAndGet() == 0) {
                    Scheduling.global(plugin, () -> placeMeasuredColumns(claim, world, byChunk,
                            baseHeights, perColumn, gateFlags, height, refundFailures));
                }
            });
        }
    }

    /**
     * Second pass: works out how tall each column has to be to reach its neighbours, then places them.
     * <p>
     * A post only connects to a neighbour if both occupy the same Y. On a slope the lower post is
     * therefore stretched up to the higher neighbour's base, which is exactly how a fence is built up a
     * hill by hand. The stretch is capped so a cliff edge does not produce a tower.
     */
    private void placeMeasuredColumns(Claim claim, World world, Map<Long, List<ClaimPoint>> byChunk,
                                      Map<ClaimPoint, Integer> baseHeights,
                                      Map<ClaimPoint, Material> perColumn,
                                      Map<ClaimPoint, Boolean> gateFlags,
                                      int height, boolean refundFailures) {
        ClaimFence fence = claim.fence();
        ClaimShape shape = claim.shape();
        Material fenceMaterial = fence.material();
        int maxStep = settings.fenceMaxStep();

        // Neighbour levels also come from posts that are already standing, so extending an existing run
        // keeps lining up with it.
        Map<ClaimPoint, Integer> knownLevels = new HashMap<>(baseHeights);
        fence.segments().forEach((point, segment) -> knownLevels.putIfAbsent(point, segment.baseY()));

        Map<ClaimPoint, Integer> spans = new HashMap<>();
        for (Map.Entry<ClaimPoint, Integer> entry : baseHeights.entrySet()) {
            ClaimPoint point = entry.getKey();
            int base = entry.getValue();
            int top = base + height - 1;
            for (ClaimPoint neighbour : orthogonalNeighbours(point)) {
                Integer neighbourBase = knownLevels.get(neighbour);
                if (neighbourBase != null && neighbourBase > top) {
                    top = Math.min(neighbourBase, base + maxStep - 1);
                }
            }
            spans.put(point, Math.max(1, top - base + 1));
        }

        for (List<ClaimPoint> chunkColumns : byChunk.values()) {
            if (chunkColumns.isEmpty()) {
                continue;
            }
            Location anchor = new Location(world, chunkColumns.get(0).x() + 0.5D,
                    shape.minY(), chunkColumns.get(0).z() + 0.5D);
            Scheduling.region(plugin, anchor, () -> {
                List<FencePlacement> placed = new ArrayList<>();
                List<Block> placedBlocks = new ArrayList<>();
                int failed = 0;
                for (ClaimPoint point : chunkColumns) {
                    Integer base = baseHeights.get(point);
                    if (base == null) {
                        failed += height;
                        continue;
                    }
                    Material material = perColumn.get(point);
                    int span = spans.getOrDefault(point, height);
                    OptionalPlacement placement = placeColumn(world, shape, point, material, base, span);
                    if (placement.success()) {
                        placed.add(new FencePlacement(point,
                                new FenceSegment(placement.baseY(), placement.placedHeight(), material,
                                        Boolean.TRUE.equals(gateFlags.get(point)))));
                        for (int offset = 0; offset < placement.placedHeight(); offset++) {
                            placedBlocks.add(world.getBlockAt(point.x(),
                                    placement.baseY() + offset, point.z()));
                        }
                    } else {
                        failed += height;
                    }
                }
                // Stitch the run together once per chunk group. Without this every block keeps the
                // default state it was written with and the fence comes out as unconnected posts.
                BlockConnector.connectAll(placedBlocks);
                int failedBlocks = failed;
                Scheduling.global(plugin, () -> {
                    for (FencePlacement placement : placed) {
                        fence.put(placement.point(), placement.segment());
                    }
                    if (failedBlocks > 0 && refundFailures) {
                        depositToBank(claim, fenceMaterial, failedBlocks);
                    }
                    claim.markDirty();
                });
            });
        }
    }

    /** The four columns a fence post can actually reach; diagonals are not neighbours in Minecraft. */
    private static List<ClaimPoint> orthogonalNeighbours(ClaimPoint point) {
        return List.of(
                new ClaimPoint(point.x() + 1, point.z()),
                new ClaimPoint(point.x() - 1, point.z()),
                new ClaimPoint(point.x(), point.z() + 1),
                new ClaimPoint(point.x(), point.z() - 1));
    }

    private record FencePlacement(ClaimPoint point, FenceSegment segment) {
    }

    private record OptionalPlacement(boolean success, int baseY, int placedHeight) {
        static OptionalPlacement failed() {
            return new OptionalPlacement(false, 0, 0);
        }
    }

    /** Places one column at a known base level, inside the claim's vertical range. */
    private OptionalPlacement placeColumn(World world, ClaimShape shape, ClaimPoint point,
                                          Material material, int baseY, int height) {
        if (!world.isChunkLoaded(point.x() >> 4, point.z() >> 4)) {
            return OptionalPlacement.failed();
        }
        // A fence that would sit outside the claim's own vertical range makes no sense.
        if (baseY < shape.minY() || baseY > shape.maxY()) {
            return OptionalPlacement.failed();
        }
        int placeable = 0;
        for (int offset = 0; offset < height; offset++) {
            int y = baseY + offset;
            if (y > shape.maxY() || y >= world.getMaxHeight()) {
                break;
            }
            if (!isReplaceable(world.getBlockAt(point.x(), y, point.z()))) {
                break;
            }
            placeable++;
        }
        if (placeable <= 0) {
            return OptionalPlacement.failed();
        }
        for (int offset = 0; offset < placeable; offset++) {
            world.getBlockAt(point.x(), baseY + offset, point.z())
                    .setBlockData(material.createBlockData(), false);
        }
        return new OptionalPlacement(true, baseY, placeable);
    }

    /**
     * Removes the recorded columns. Returns how many blocks came down, which is what gets refunded.
     * <p>
     * Only blocks that still match the record are cleared, so a player build that replaced a fence block
     * is never destroyed by a resize.
     */
    private int removeColumns(Claim claim, World world, List<ClaimPoint> columns, boolean refundToBank) {
        if (columns.isEmpty()) {
            return 0;
        }
        ClaimFence fence = claim.fence();
        Map<ClaimPoint, FenceSegment> snapshot = new HashMap<>();
        for (ClaimPoint point : columns) {
            fence.segmentAt(point).ifPresent(segment -> snapshot.put(point, segment));
        }
        if (snapshot.isEmpty()) {
            return 0;
        }

        for (Map.Entry<Long, List<ClaimPoint>> group : groupByChunk(new ArrayList<>(snapshot.keySet()))
                .entrySet()) {
            List<ClaimPoint> chunkColumns = group.getValue();
            Location anchor = new Location(world, chunkColumns.get(0).x() + 0.5D,
                    claim.shape().minY(), chunkColumns.get(0).z() + 0.5D);
            Scheduling.region(plugin, anchor, () -> {
                Map<Material, Integer> local = new HashMap<>();
                List<Block> removed = new ArrayList<>();
                for (ClaimPoint point : chunkColumns) {
                    FenceSegment segment = snapshot.get(point);
                    if (segment == null || !world.isChunkLoaded(point.x() >> 4, point.z() >> 4)) {
                        continue;
                    }
                    for (int y = segment.baseY(); y <= segment.topY(); y++) {
                        Block block = world.getBlockAt(point.x(), y, point.z());
                        if (block.getType() != segment.material()) {
                            continue;
                        }
                        block.setType(Material.AIR, false);
                        removed.add(block);
                        local.merge(segment.material(), 1, Integer::sum);
                    }
                }
                // Neighbours that stay must drop the connection towards the removed blocks.
                BlockConnector.connectAll(removed);
                Scheduling.global(plugin, () -> {
                    for (ClaimPoint point : chunkColumns) {
                        fence.remove(point);
                    }
                    if (refundToBank) {
                        local.forEach((material, count) -> depositToBank(claim, material, count));
                    }
                    claim.markDirty();
                });
            });
        }
        int totalBlocks = 0;
        for (FenceSegment segment : snapshot.values()) {
            totalBlocks += segment.height();
        }
        return totalBlocks;
    }

    private Map<Long, List<ClaimPoint>> groupByChunk(List<ClaimPoint> columns) {
        Map<Long, List<ClaimPoint>> byChunk = new HashMap<>();
        for (ClaimPoint point : columns) {
            byChunk.computeIfAbsent(ClaimShape.chunkKey(point.x() >> 4, point.z() >> 4),
                    key -> new ArrayList<>()).add(point);
        }
        return byChunk;
    }

    private boolean isReplaceable(Block block) {
        Material type = block.getType();
        if (type.isAir()) {
            return true;
        }
        return Tag.REPLACEABLE.isTagged(type) && !type.equals(Material.WATER) && !type.equals(Material.LAVA);
    }

    // ------------------------------------------------------------ owner edits picked up from the world

    /**
     * The owner broke a fence block. The gap is remembered so a resize does not close it again.
     *
     * @return {@code true} when the block really was part of the claim fence
     */
    public boolean handleBreak(Claim claim, int x, int y, int z) {
        ClaimPoint point = new ClaimPoint(x, z);
        Optional<FenceSegment> segment = claim.fence().segmentAt(point);
        if (segment.isEmpty() || !segment.get().coversY(y)) {
            return false;
        }
        // The block drops normally, so the player already has the material back — no bank refund here.
        claim.fence().suppress(point);
        claim.markDirty();
        claims.saveAsync(claim);
        return true;
    }

    /**
     * The owner placed a fence or a gate on the outline. It is adopted into the fence so it survives
     * reshaping — this is how "replace a fence with a gate" is meant to work.
     *
     * @return {@code true} when the block was adopted
     */
    public boolean handlePlace(Claim claim, int x, int y, int z, Material material) {
        if (!featureAvailable() || !claim.fence().enabled()) {
            return false;
        }
        boolean gate = ClaimFence.isGate(material);
        if (!gate && !ClaimFence.isFence(material)) {
            return false;
        }
        ClaimPoint point = new ClaimPoint(x, z);
        if (!desiredOutline(claim.shape()).contains(point)) {
            return false;
        }
        Optional<FenceSegment> existing = claim.fence().segmentAt(point);
        int height = existing.map(FenceSegment::height).orElse(1);
        int baseY = existing.filter(segment -> segment.coversY(y)).map(FenceSegment::baseY).orElse(y);
        claim.fence().put(point, new FenceSegment(baseY, Math.max(height, y - baseY + 1), material, gate));
        claim.markDirty();
        claims.saveAsync(claim);
        return true;
    }

    /** Whether the block belongs to the claim's fence — used to protect it from outsiders. */
    public boolean isFenceBlock(Claim claim, int x, int y, int z) {
        return claim.fence().segmentAt(new ClaimPoint(x, z))
                .filter(segment -> segment.coversY(y))
                .isPresent();
    }

    // ------------------------------------------------------------ material handling

    private void depositToBank(Claim claim, Material material, int amount) {
        if (amount <= 0 || material == null || material.isAir()) {
            return;
        }
        ItemStack stack = new ItemStack(material, Math.min(amount, material.getMaxStackSize()));
        int remaining = amount;
        while (remaining > 0) {
            int size = Math.min(remaining, material.getMaxStackSize());
            ItemStack portion = stack.clone();
            portion.setAmount(size);
            claim.bank().depositItem(portion);
            remaining -= size;
        }
        claim.markDirty();
    }

    private int countMaterial(PlayerInventory inventory, Material material) {
        int count = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private void takeMaterial(PlayerInventory inventory, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int taken = Math.min(remaining, stack.getAmount());
            remaining -= taken;
            if (stack.getAmount() - taken <= 0) {
                contents[slot] = null;
            } else {
                stack.setAmount(stack.getAmount() - taken);
            }
        }
        inventory.setStorageContents(contents);
    }

    /**
     * The material the standing fence is worth, derived from the records rather than from the world.
     * <p>
     * Used when a claim is deleted: the block removal itself runs on the region schedulers a tick later,
     * far too late to pay an owner who is being handed their bank contents right now.
     */
    public Map<Material, Integer> reclaimableMaterials(Claim claim) {
        Map<Material, Integer> totals = new HashMap<>();
        for (FenceSegment segment : claim.fence().segments().values()) {
            totals.merge(segment.material(), segment.height(), Integer::sum);
        }
        return totals;
    }

    /** Pays the standing fence's material into the claim bank immediately. */
    public void bankStandingFence(Claim claim) {
        reclaimableMaterials(claim).forEach((material, count) -> depositToBank(claim, material, count));
    }

    /** Every fence and wall material a claim owner may pick, fences first, then walls. */
    public List<Material> availableMaterials() {
        return BlockConnector.barrierMaterials();
    }

    private void logDebug(Claim claim, String reason, FenceResult result) {
        if (!settings.debug() || !result.changedAnything()) {
            return;
        }
        plugin.getLogger().info("Fence sync (" + reason + ") for " + claim.name()
                + ": +" + result.placed() + " -" + result.removed()
                + " skipped=" + result.skipped() + " missing=" + result.missingMaterial());
    }
}
