package de.raindancer.modules.xaeromap.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.xaeromap.XaeroMapSettings;
import de.raindancer.modules.xaeromap.claims.ClaimSource;
import de.raindancer.modules.xaeromap.model.ClaimFacts;
import de.raindancer.modules.xaeromap.model.ClaimMapSnapshot;
import de.raindancer.modules.xaeromap.model.MapClaim;
import de.raindancer.modules.xaeromap.model.MapDiff;
import de.raindancer.modules.xaeromap.model.OpacPackets;
import de.raindancer.modules.xaeromap.model.RegionPage;
import de.raindancer.modules.xaeromap.rules.ChunkCoverageRule;
import de.raindancer.modules.xaeromap.rules.ClaimColourRule;
import de.raindancer.modules.xaeromap.rules.ClaimVisibilityRule;
import de.raindancer.modules.xaeromap.store.ClaimMirror;
import de.raindancer.modules.xaeromap.store.SyncIndexTable;
import de.raindancer.modules.xaeromap.util.ChunkKeys;
import de.raindancer.modules.xaeromap.util.Wire;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Keeps every client's idea of this server's claims up to date, in the format Xaero's two map mods
 * already read.
 *
 * <h2>How a client is brought up to date</h2>
 * <ol>
 *   <li>The client's mod registers its channel. We answer with the handshake — claims exist here — and
 *       a {@code regionsStart} probe.</li>
 *   <li>The mod echoes that probe back, which is the only signal a server gets that it is talking to a
 *       real Open Parties and Claims client rather than shouting into a channel somebody else opened.
 *       Only then does anything else get sent.</li>
 *   <li>The claims that player may see go out as whole regions — 1024 chunks in one packet — because a
 *       first sync of a busy server is thousands of chunks and one packet each would be absurd.</li>
 *   <li>From then on the refresh timer sends the difference, chunk by chunk. A claim made, resized or
 *       deleted is a handful of packets rather than a resync.</li>
 * </ol>
 *
 * <h2>What it will not do</h2>
 * Nothing here handles the mod's <em>serverbound</em> claim requests. A player pressing the mod's own
 * claim key is asking Open Parties and Claims to claim a chunk, and this server has its own idea of what
 * a claim is, who may make one, what it costs and how big it may be. Answering that request would be a
 * second way to make a claim that knows none of those rules. So the map is read-only, deliberately, and
 * claim limits are never sent either — the mod's own claiming UI has nothing to offer with them at zero.
 */
public final class ClaimSyncService implements IXaeroMapService {

    /**
     * Above this many chunks to send at once, whole regions are cheaper than one packet per chunk.
     *
     * <p>Not "is this a first sync": a player who has just been granted access to two hundred claims is
     * in exactly the same position as one who has just joined, and the reason to prefer regions is the
     * size of the difference rather than how it came about.
     */
    private static final int REGION_MODE_FROM = 96;

    private final Wire wire;
    private final Supplier<ClaimSource> source;
    private final SyncIndexTable indices;
    private final ClaimMirror mirror;
    private final LogChannel log;

    /** Players whose client has answered the probe. Nothing is sent to anybody else. */
    private final Set<UUID> ready = ConcurrentHashMap.newKeySet();

    private volatile XaeroMapSettings settings;
    private volatile ChunkCoverageRule coverage;
    private volatile ClaimVisibilityRule visibility;
    private volatile ClaimColourRule colours;
    private volatile ClaimMapSnapshot snapshot = ClaimMapSnapshot.EMPTY;

    public ClaimSyncService(Wire wire, Supplier<ClaimSource> source, SyncIndexTable indices,
                            ClaimMirror mirror, LogChannel log, XaeroMapSettings settings) {
        this.wire = wire;
        this.source = source;
        this.indices = indices;
        this.mirror = mirror;
        this.log = log;
        settings(settings);
    }

    @Override
    public void settings(XaeroMapSettings settings) {
        this.settings = settings;
        this.coverage = new ChunkCoverageRule(settings.coveragePercentClamped());
        this.visibility = new ClaimVisibilityRule(settings.audience());
        this.colours = new ClaimColourRule(settings.ownColour(), settings.sharedColour());
    }

    // ---------------------------------------------------------------- what the listeners call

    /** A client has opened the channel: say what this server is, and ask whether anybody is there. */
    public void offer(Player player) {
        if (!settings.claims() || player == null) {
            return;
        }
        wire.send(player, OpacPackets.CHANNEL, OpacPackets.handshake(true));
        wire.send(player, OpacPackets.CHANNEL, OpacPackets.regionsStart());
    }

    /**
     * Something arrived from a client on the claims channel.
     *
     * <p>Only one message means anything to us: the echo of our own probe. Everything else the mod sends
     * is a request about parties, player configs or claiming chunks, and is ignored on purpose rather
     * than half-answered.
     */
    public void onClientMessage(Player player, byte[] message) {
        if (player == null || OpacPackets.indexOf(message) != OpacPackets.REGIONS_START) {
            return;
        }
        if (ready.add(player.getUniqueId())) {
            log.debug("{} is running a claims-capable map mod.", player.getName());
        }
        begin(player);
    }

    /** Everything from scratch: forget what they had, tell them to as well, and send it all again. */
    public void begin(Player player) {
        if (!settings.claims() || player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        wire.send(player, OpacPackets.CHANNEL, OpacPackets.claimsReset());
        mirror.startFresh(id);
        if (snapshot.isEmpty()) {
            // Nothing has been built yet — the first player to arrive after a restart, or the first
            // one at all. Without this they would sit looking at a blank map until the refresh clock
            // came round, which on a server with no claims is indistinguishable from this being
            // broken. A rebuild here is cheap for exactly the case that triggers it.
            snapshot = build();
        }
        push(player);
    }

    /** The timer: rebuild the picture once, then tell everybody what they are missing. */
    public void refresh(Collection<? extends Player> online) {
        if (!settings.claims()) {
            return;
        }
        snapshot = build();
        for (Player player : online) {
            if (ready.contains(player.getUniqueId())) {
                push(player);
            }
        }
    }

    public void forget(UUID player) {
        ready.remove(player);
        mirror.forget(player);
    }

    public boolean isReady(UUID player) {
        return ready.contains(player);
    }

    public int readyCount() {
        return ready.size();
    }

    public ClaimMapSnapshot current() {
        return snapshot;
    }

    // ---------------------------------------------------------------- the picture

    /** Every claim there is, reduced to which claim holds which chunk, per world. */
    public ClaimMapSnapshot build() {
        ClaimSource claims = source.get();
        if (claims == null || !claims.available()) {
            return ClaimMapSnapshot.EMPTY;
        }
        Map<UUID, ClaimFacts> byId = new LinkedHashMap<>();
        Map<String, List<ClaimFacts>> perDimension = new LinkedHashMap<>();
        for (ClaimFacts claim : claims.claims()) {
            byId.put(claim.id(), claim);
            perDimension.computeIfAbsent(claim.dimensionKey(), key -> new ArrayList<>()).add(claim);
        }
        Map<String, Map<Long, UUID>> chunks = new LinkedHashMap<>();
        // Per dimension, never all at once: a chunk key says nothing about which world it is in, so a
        // shared pass would let a claim in the nether win a chunk in the overworld.
        perDimension.forEach((dimension, inThatWorld) ->
                chunks.put(dimension, coverage.chunksOf(inThatWorld)));
        return new ClaimMapSnapshot(byId, chunks);
    }

    // ---------------------------------------------------------------- one player

    private void push(Player player) {
        UUID id = player.getUniqueId();
        ClaimMapSnapshot picture = snapshot;
        Set<UUID> visible = visibleTo(id, picture);
        MapDiff diff = mirror.diff(id, picture, visible);
        if (diff.isEmpty()) {
            return;
        }

        wire.send(player, OpacPackets.CHANNEL, OpacPackets.loading(true));
        tellAbout(player, diff.changed());

        int budget = settings.chunkBudget();
        boolean asRegions = diff.chunkChanges() >= REGION_MODE_FROM;
        for (Map.Entry<String, Map<Long, UUID>> perDimension : diff.chunks().entrySet()) {
            if (budget <= 0) {
                break;
            }
            budget -= asRegions
                    ? sendRegions(player, picture, visible, perDimension.getKey(),
                            perDimension.getValue(), budget)
                    : sendUpdates(player, picture, perDimension.getKey(), perDimension.getValue(),
                            budget);
        }
        if (asRegions) {
            // Closes the run of regions. Left out, the mod keeps filing whatever comes next under the
            // last dimension named — which draws the nether's claims onto the overworld.
            wire.send(player, OpacPackets.CHANNEL, OpacPackets.dimension(null));
        }
        wire.send(player, OpacPackets.CHANNEL, OpacPackets.loading(false));

        mirror.applyClaims(id, diff.changed(), diff.gone());
    }

    private Set<UUID> visibleTo(UUID viewer, ClaimMapSnapshot picture) {
        Set<UUID> visible = new HashSet<>();
        picture.claims().forEach((claimId, claim) -> {
            if (visibility.maySee(viewer, claim)) {
                visible.add(claimId);
            }
        });
        return visible;
    }

    /** Names, colours and identities, in the batch sizes the mod refuses to exceed. */
    private void tellAbout(Player player, Collection<ClaimFacts> claims) {
        if (claims.isEmpty()) {
            return;
        }
        UUID viewer = player.getUniqueId();
        List<MapClaim> mapped = new ArrayList<>(claims.size());
        Map<UUID, String> owners = new LinkedHashMap<>();
        for (ClaimFacts claim : claims) {
            mapped.add(indices.mapClaim(claim, colours.colourFor(viewer, claim)));
            owners.putIfAbsent(claim.owner(), claim.ownerName() == null || claim.ownerName().isBlank()
                    ? claim.owner().toString().substring(0, 8)
                    : claim.ownerName());
        }
        List<OpacPackets.ClaimOwner> ownerList = new ArrayList<>(owners.size());
        owners.forEach((id, name) -> ownerList.add(new OpacPackets.ClaimOwner(id, name)));

        // Owners first, then the claims' own names and colours, then the identities the regions refer
        // to. The mod files each of these under the one before it; sent the other way round, a claim
        // arrives owned by somebody it has never heard of and is dropped.
        for (List<OpacPackets.ClaimOwner> batch : batched(ownerList, OpacPackets.MAX_PROPERTIES_PER_PACKET)) {
            wire.send(player, OpacPackets.CHANNEL, OpacPackets.ownerProperties(batch));
        }
        for (List<MapClaim> batch : batched(mapped, OpacPackets.MAX_PROPERTIES_PER_PACKET)) {
            wire.send(player, OpacPackets.CHANNEL, OpacPackets.subClaimProperties(batch));
        }
        for (List<MapClaim> batch : batched(mapped, OpacPackets.MAX_STATES_PER_PACKET)) {
            wire.send(player, OpacPackets.CHANNEL, OpacPackets.claimStates(batch));
        }
    }

    /**
     * Whole regions, for a difference too big to send chunk by chunk.
     *
     * <p>A region packet replaces all 1024 of a region's chunks at once, so it is built from the
     * <em>whole</em> picture of every region it touches rather than from the difference — built from the
     * difference alone it would blank every unchanged claim that happens to share a region.
     *
     * @return how much of the budget this used, in chunks
     */
    private int sendRegions(Player player, ClaimMapSnapshot picture, Set<UUID> visible,
                            String dimension, Map<Long, UUID> changes, int budget) {
        Set<Long> touched = new LinkedHashSet<>();
        changes.keySet().forEach(chunk -> touched.add(ChunkKeys.regionOf(chunk)));

        Map<Long, RegionPage> pages = new LinkedHashMap<>();
        Map<Long, Map<Long, UUID>> applied = new LinkedHashMap<>();
        picture.chunksIn(dimension).forEach((chunk, claimId) -> {
            long region = ChunkKeys.regionOf(chunk);
            if (!touched.contains(region) || !visible.contains(claimId)) {
                return;
            }
            int syncIndex = identityOf(picture, claimId);
            if (syncIndex == 0) {
                return;
            }
            pages.computeIfAbsent(region, RegionPage::of).put(chunk, syncIndex);
            applied.computeIfAbsent(region, key -> new HashMap<>()).put(chunk, claimId);
        });
        // Chunks that have gone back to nobody: no page of their own if their region has no claims
        // left, but the mirror still has to be told they are free.
        changes.forEach((chunk, claimId) -> {
            if (claimId == null) {
                long region = ChunkKeys.regionOf(chunk);
                if (touched.contains(region)) {
                    applied.computeIfAbsent(region, key -> new HashMap<>()).put(chunk, null);
                    pages.computeIfAbsent(region, RegionPage::of);
                }
            }
        });

        wire.send(player, OpacPackets.CHANNEL, OpacPackets.dimension(dimension));
        int spent = 0;
        for (Map.Entry<Long, RegionPage> page : pages.entrySet()) {
            int cost = Math.max(1, page.getValue().chunkCount());
            if (spent > 0 && spent + cost > budget) {
                // Whatever is left comes on the next refresh — the mirror only records what went out.
                break;
            }
            wire.send(player, OpacPackets.CHANNEL, page.getValue().encode());
            mirror.applyChunks(player.getUniqueId(), dimension,
                    applied.getOrDefault(page.getKey(), Map.of()));
            spent += cost;
        }
        return spent;
    }

    /** One packet per chunk that changed hands, which is what a claim being made actually is. */
    private int sendUpdates(Player player, ClaimMapSnapshot picture, String dimension,
                            Map<Long, UUID> changes, int budget) {
        int spent = 0;
        Map<Long, UUID> applied = new HashMap<>();
        for (Map.Entry<Long, UUID> change : changes.entrySet()) {
            if (spent >= budget) {
                break;
            }
            long chunk = change.getKey();
            UUID claimId = change.getValue();
            if (claimId == null) {
                wire.send(player, OpacPackets.CHANNEL, OpacPackets.unclaimed(dimension,
                        ChunkKeys.chunkX(chunk), ChunkKeys.chunkZ(chunk)));
                applied.put(chunk, null);
                spent++;
                continue;
            }
            ClaimFacts claim = picture.claim(claimId);
            if (claim == null) {
                continue;
            }
            MapClaim mapped = indices.mapClaim(claim, colours.colourFor(player.getUniqueId(), claim));
            wire.send(player, OpacPackets.CHANNEL, OpacPackets.claimed(dimension,
                    ChunkKeys.chunkX(chunk), ChunkKeys.chunkZ(chunk), mapped));
            applied.put(chunk, claimId);
            spent++;
        }
        mirror.applyChunks(player.getUniqueId(), dimension, applied);
        return spent;
    }

    /**
     * The handle a region palette refers to a claim by, allocating one if the claim somehow has none.
     *
     * <p>It should always have one by this point — a claim in a region is a claim whose name and colour
     * went out with {@link #tellAbout} — but a palette entry of 0 means "nobody", so a missing handle
     * would quietly erase the claim rather than fail.
     */
    private int identityOf(ClaimMapSnapshot picture, UUID claimId) {
        int existing = indices.syncIndexOf(claimId);
        if (existing != 0) {
            return existing;
        }
        ClaimFacts claim = picture.claim(claimId);
        return claim == null ? 0 : indices.mapClaim(claim, 0).syncIndex();
    }

    private static <T> List<List<T>> batched(List<T> items, int size) {
        List<List<T>> batches = new ArrayList<>();
        for (int from = 0; from < items.size(); from += size) {
            batches.add(items.subList(from, Math.min(items.size(), from + size)));
        }
        return batches;
    }
}
