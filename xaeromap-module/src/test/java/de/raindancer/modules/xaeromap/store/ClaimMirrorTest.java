package de.raindancer.modules.xaeromap.store;

import de.raindancer.modules.xaeromap.Facts;
import de.raindancer.modules.xaeromap.model.ClaimFacts;
import de.raindancer.modules.xaeromap.model.ClaimMapSnapshot;
import de.raindancer.modules.xaeromap.model.MapDiff;
import de.raindancer.modules.xaeromap.rules.ChunkCoverageRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a client is told what it is missing and nothing else — and, when a send is cut short, that it is
 * still behind by exactly the part that did not go out.
 */
class ClaimMirrorTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    private static ClaimMapSnapshot snapshotOf(List<ClaimFacts> claims) {
        Map<UUID, ClaimFacts> byId = claims.stream()
                .collect(Collectors.toMap(ClaimFacts::id, claim -> claim, (a, b) -> a,
                        LinkedHashMap::new));
        Map<String, List<ClaimFacts>> perDimension = new LinkedHashMap<>();
        claims.forEach(claim -> perDimension
                .computeIfAbsent(claim.dimensionKey(), key -> new java.util.ArrayList<>()).add(claim));
        Map<String, Map<Long, UUID>> chunks = new LinkedHashMap<>();
        ChunkCoverageRule coverage = new ChunkCoverageRule(1);
        perDimension.forEach((dimension, inThere) -> chunks.put(dimension, coverage.chunksOf(inThere)));
        return new ClaimMapSnapshot(byId, chunks);
    }

    private static Set<UUID> all(ClaimMapSnapshot snapshot) {
        return snapshot.claims().keySet();
    }

    @Test
    @DisplayName("a client that has been told nothing is missing everything")
    void thefirstSyncIsThewholePicture() {
        ClaimMapSnapshot snapshot = snapshotOf(List.of(
                Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0), Facts.chunk(1, 0))));
        ClaimMirror mirror = new ClaimMirror();

        MapDiff diff = mirror.diff(PLAYER, snapshot, all(snapshot));

        assertThat(mirror.knows(PLAYER)).isFalse();
        assertThat(diff.changed()).hasSize(1);
        assertThat(diff.chunkChanges()).isEqualTo(2);
    }

    @Test
    @DisplayName("asking twice without sending anything gives the same answer")
    void askingDoesNotRecord() {
        ClaimMapSnapshot snapshot = snapshotOf(List.of(
                Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0))));
        ClaimMirror mirror = new ClaimMirror();

        MapDiff first = mirror.diff(PLAYER, snapshot, all(snapshot));
        MapDiff second = mirror.diff(PLAYER, snapshot, all(snapshot));

        assertThat(second.chunkChanges())
                .as("a diff that recorded itself would mark a sync as done whether or not the "
                        + "packets went out — a player who dropped mid-sync would never get the rest")
                .isEqualTo(first.chunkChanges());
    }

    @Test
    @DisplayName("nothing has changed, nothing is sent")
    void anUnchangedServerIsQuiet() {
        ClaimFacts home = Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0));
        ClaimMapSnapshot snapshot = snapshotOf(List.of(home));
        ClaimMirror mirror = new ClaimMirror();
        sendEverything(mirror, snapshot);

        assertThat(mirror.diff(PLAYER, snapshot, all(snapshot)).isEmpty())
                .as("the whole point of the mirror: a quiet server costs nothing per refresh")
                .isTrue();
    }

    @Test
    @DisplayName("one chunk changing hands is one chunk in the difference")
    void onlyTheDifferenceTravels() {
        ClaimFacts before = Facts.claim("Home", OWNER, Facts.OVERWORLD,
                Facts.chunk(0, 0), Facts.chunk(1, 0));
        ClaimMapSnapshot first = snapshotOf(List.of(before));
        ClaimMirror mirror = new ClaimMirror();
        sendEverything(mirror, first);

        ClaimFacts grown = new ClaimFacts(before.id(), before.name(), before.owner(),
                before.ownerName(), before.members(), before.worldId(), before.dimensionKey(),
                before.createdAt(), Map.of(Facts.chunk(0, 0), 256, Facts.chunk(1, 0), 256,
                        Facts.chunk(2, 0), 256));
        ClaimMapSnapshot second = snapshotOf(List.of(grown));

        MapDiff diff = mirror.diff(PLAYER, second, all(second));

        assertThat(diff.chunkChanges()).isEqualTo(1);
        assertThat(diff.chunks().get(Facts.OVERWORLD))
                .containsExactly(Map.entry(Facts.chunk(2, 0), grown.id()));
        assertThat(diff.changed())
                .as("the claim's own footprint changed, so its identity has to be re-sent too")
                .hasSize(1);
    }

    @Test
    @DisplayName("a deleted claim's chunks are handed back as nobody's")
    void deletedClaimsFreeTheirChunks() {
        ClaimFacts home = Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0));
        ClaimMapSnapshot before = snapshotOf(List.of(home));
        ClaimMirror mirror = new ClaimMirror();
        sendEverything(mirror, before);

        ClaimMapSnapshot after = snapshotOf(List.of());
        MapDiff diff = mirror.diff(PLAYER, after, Set.of());

        assertThat(diff.gone()).containsExactly(home.id());
        assertThat(diff.chunks().get(Facts.OVERWORLD))
                .as("telling the client the claim is gone does not free its chunks; the mod holds "
                        + "them until it is told each one is unclaimed, which is what a null owner "
                        + "here becomes on the wire")
                .containsOnlyKeys(Facts.chunk(0, 0))
                .containsEntry(Facts.chunk(0, 0), null);
    }

    @Test
    @DisplayName("a rename is re-sent; something the map does not draw is not")
    void onlyWhatIsDrawnCounts() {
        ClaimFacts home = Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0));
        ClaimMapSnapshot before = snapshotOf(List.of(home));
        ClaimMirror mirror = new ClaimMirror();
        sendEverything(mirror, before);

        ClaimFacts renamed = new ClaimFacts(home.id(), "Somewhere else", home.owner(),
                home.ownerName(), home.members(), home.worldId(), home.dimensionKey(),
                home.createdAt(), home.chunkCoverage());
        ClaimMapSnapshot afterRename = snapshotOf(List.of(renamed));

        assertThat(mirror.diff(PLAYER, afterRename, all(afterRename)).changed()).hasSize(1);

        ClaimFacts sameAgain = new ClaimFacts(home.id(), home.name(), home.owner(), home.ownerName(),
                home.members(), home.worldId(), home.dimensionKey(), home.createdAt() + 5_000,
                home.chunkCoverage());
        ClaimMapSnapshot afterNothing = snapshotOf(List.of(sameAgain));

        assertThat(mirror.diff(PLAYER, afterNothing, all(afterNothing)).changed())
                .as("when a claim was created is not drawn anywhere; treating it as a change would "
                        + "mean re-sending claims for nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("a claim shared with somebody is re-sent, because it changes colour on their map")
    void trustingSomebodyIsAVisibleChange() {
        ClaimFacts home = Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0));
        ClaimMapSnapshot before = snapshotOf(List.of(home));
        ClaimMirror mirror = new ClaimMirror();
        sendEverything(mirror, before);

        ClaimFacts shared = new ClaimFacts(home.id(), home.name(), home.owner(), home.ownerName(),
                Set.of(PLAYER), home.worldId(), home.dimensionKey(), home.createdAt(),
                home.chunkCoverage());
        ClaimMapSnapshot after = snapshotOf(List.of(shared));

        assertThat(mirror.diff(PLAYER, after, all(after)).changed())
                .as("a claim shared with you is drawn in your shared colour, not a stranger's")
                .hasSize(1);
    }

    @Test
    @DisplayName("half a sync leaves the client behind by exactly the other half")
    void apartialSendIsRecordedAsPartial() {
        ClaimFacts home = Facts.claim("Home", OWNER, Facts.OVERWORLD,
                Facts.chunk(0, 0), Facts.chunk(1, 0), Facts.chunk(2, 0), Facts.chunk(3, 0));
        ClaimMapSnapshot snapshot = snapshotOf(List.of(home));
        ClaimMirror mirror = new ClaimMirror();

        MapDiff diff = mirror.diff(PLAYER, snapshot, all(snapshot));
        Map<Long, UUID> half = new HashMap<>();
        diff.chunks().get(Facts.OVERWORLD).entrySet().stream().limit(2)
                .forEach(entry -> half.put(entry.getKey(), entry.getValue()));
        mirror.applyClaims(PLAYER, diff.changed(), diff.gone());
        mirror.applyChunks(PLAYER, Facts.OVERWORLD, half);

        MapDiff rest = mirror.diff(PLAYER, snapshot, all(snapshot));

        assertThat(mirror.chunksKnownBy(PLAYER)).isEqualTo(2);
        assertThat(rest.chunkChanges())
                .as("whatever a budget cut short has to come on the next refresh, not be lost")
                .isEqualTo(2);
        assertThat(rest.changed())
                .as("the claim's identity did go out, so it is not re-sent")
                .isEmpty();
    }

    @Test
    @DisplayName("two players are behind by different amounts, and neither one covers for the other")
    void mirrorsArePerPlayer() {
        UUID other = UUID.randomUUID();
        ClaimFacts home = Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0));
        ClaimMapSnapshot snapshot = snapshotOf(List.of(home));
        ClaimMirror mirror = new ClaimMirror();
        sendEverything(mirror, snapshot);

        assertThat(mirror.diff(PLAYER, snapshot, all(snapshot)).isEmpty()).isTrue();
        assertThat(mirror.diff(other, snapshot, all(snapshot)).isEmpty())
                .as("two players can be shown different claims, so one shared record would count a "
                        + "claim sent to one of them as sent to the other")
                .isFalse();
    }

    @Test
    @DisplayName("a claim the viewer may not see is neither sent nor recorded")
    void invisibleClaimsAreNotInTheDiff() {
        ClaimFacts theirs = Facts.claim("Theirs", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0));
        ClaimMapSnapshot snapshot = snapshotOf(List.of(theirs));
        ClaimMirror mirror = new ClaimMirror();

        MapDiff diff = mirror.diff(PLAYER, snapshot, Set.of());

        assertThat(diff.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a claim that stops being visible frees its chunks on that client")
    void losingAccessTakesTheClaimOffTheMap() {
        ClaimFacts theirs = Facts.claim("Theirs", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0));
        ClaimMapSnapshot snapshot = snapshotOf(List.of(theirs));
        ClaimMirror mirror = new ClaimMirror();
        sendEverything(mirror, snapshot);

        MapDiff diff = mirror.diff(PLAYER, snapshot, Set.of());

        assertThat(diff.gone()).containsExactly(theirs.id());
        assertThat(diff.chunks().get(Facts.OVERWORLD)).containsKey(Facts.chunk(0, 0));
    }

    @Test
    @DisplayName("forgetting a player really forgets them")
    void forgettingIsForgetting() {
        ClaimMapSnapshot snapshot = snapshotOf(List.of(
                Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0))));
        ClaimMirror mirror = new ClaimMirror();
        sendEverything(mirror, snapshot);

        mirror.forget(PLAYER);

        assertThat(mirror.knows(PLAYER)).isFalse();
        assertThat(mirror.playerCount()).isZero();
        assertThat(mirror.diff(PLAYER, snapshot, all(snapshot)).isEmpty()).isFalse();
    }

    /** Records everything the diff offered, as a sender with an unlimited budget would. */
    private static void sendEverything(ClaimMirror mirror, ClaimMapSnapshot snapshot) {
        MapDiff diff = mirror.diff(PLAYER, snapshot, all(snapshot));
        mirror.applyClaims(PLAYER, diff.changed(), diff.gone());
        diff.chunks().forEach((dimension, changes) ->
                mirror.applyChunks(PLAYER, dimension, changes));
    }
}
