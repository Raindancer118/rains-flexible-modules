package de.raindancer.modules.xaeromap.store;

import de.raindancer.modules.xaeromap.Facts;
import de.raindancer.modules.xaeromap.model.ClaimFacts;
import de.raindancer.modules.xaeromap.model.MapClaim;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the handles a client refers to claims by mean the same claim for as long as the client holds
 * them.
 *
 * <p>The failure this prevents is specific and nasty: hand out an index a deleted claim used to have,
 * and every chunk the client still has from the old palette is drawn under the new claim's name. It
 * looks like a working map, in somebody else's living room.
 */
class SyncIndexTableTest {

    private static final UUID OWNER = UUID.randomUUID();

    @Test
    @DisplayName("a claim keeps its handle across refreshes")
    void handlesAreStable() {
        SyncIndexTable table = new SyncIndexTable();
        ClaimFacts claim = Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0));

        MapClaim first = table.mapClaim(claim, 0x00FF00);
        MapClaim again = table.mapClaim(claim, 0x00FF00);

        assertThat(again.syncIndex()).isEqualTo(first.syncIndex());
        assertThat(again.subIndex()).isEqualTo(first.subIndex());
        assertThat(table.syncIndexOf(claim.id())).isEqualTo(first.syncIndex());
    }

    @Test
    @DisplayName("no handle is ever handed out twice, even after a claim is gone")
    void handlesAreNeverReused() {
        SyncIndexTable table = new SyncIndexTable();
        Set<Integer> seen = new HashSet<>();

        for (int i = 0; i < 500; i++) {
            ClaimFacts claim = Facts.claim("Claim " + i, UUID.randomUUID(), Facts.OVERWORLD,
                    Facts.chunk(i, 0));
            assertThat(seen.add(table.mapClaim(claim, 0).syncIndex()))
                    .as("handle handed out twice at claim %d", i)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("no handle is zero, because zero means nobody in a region palette")
    void zeroIsNotAHandle() {
        SyncIndexTable table = new SyncIndexTable();

        for (int i = 0; i < 50; i++) {
            ClaimFacts claim = Facts.claim("Claim " + i, OWNER, Facts.OVERWORLD, Facts.chunk(i, 0));
            assertThat(table.mapClaim(claim, 0).syncIndex())
                    .as("slot 0 of a palette is the empty one; a claim there is a claim erased")
                    .isNotZero();
        }
    }

    @Test
    @DisplayName("one owner's claims each get their own sub-index, so each has its own name")
    void oneOwnersClaimsAreToldApart() {
        SyncIndexTable table = new SyncIndexTable();
        ClaimFacts first = Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0));
        ClaimFacts second = Facts.claim("Shop", OWNER, Facts.OVERWORLD, Facts.chunk(1, 0));

        MapClaim one = table.mapClaim(first, 0);
        MapClaim two = table.mapClaim(second, 0);

        assertThat(one.subIndex())
                .as("the mod keys a claim's name and colour on (owner, sub-index); shared, one "
                        + "player's claims would all be drawn under whichever name arrived last")
                .isNotEqualTo(two.subIndex());
    }

    @Test
    @DisplayName("a transferred claim becomes a different identity rather than keeping its owner's")
    void aTransferredClaimIsRenamedProperly() {
        SyncIndexTable table = new SyncIndexTable();
        UUID claimId = UUID.randomUUID();
        UUID somebodyElse = UUID.randomUUID();
        ClaimFacts before = new ClaimFacts(claimId, "Home", OWNER, "Rain", Set.of(),
                UUID.randomUUID(), Facts.OVERWORLD, 0L, java.util.Map.of(Facts.chunk(0, 0), 256));
        ClaimFacts after = new ClaimFacts(claimId, "Home", somebodyElse, "Somebody", Set.of(),
                UUID.randomUUID(), Facts.OVERWORLD, 0L, java.util.Map.of(Facts.chunk(0, 0), 256));

        MapClaim first = table.mapClaim(before, 0);
        MapClaim second = table.mapClaim(after, 0);

        assertThat(second.owner()).isEqualTo(somebodyElse);
        assertThat(second.syncIndex())
                .as("the (owner, sub-index) pair has to change, or the claim keeps being drawn "
                        + "under the previous owner's name")
                .isNotEqualTo(first.syncIndex());
    }

    @Test
    @DisplayName("claims arriving from several threads at once still get one handle each")
    void itIsSafeUnderConcurrency() throws InterruptedException {
        SyncIndexTable table = new SyncIndexTable();
        ClaimFacts claim = Facts.claim("Home", OWNER, Facts.OVERWORLD, Facts.chunk(0, 0));
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        Set<Integer> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < 200; round++) {
                        seen.add(table.mapClaim(claim, 0).syncIndex());
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(seen)
                .as("a claim that picks up a second handle under load is a claim drawn twice, in "
                        + "two colours, on the same chunks")
                .hasSize(1);
        assertThat(table.size()).isEqualTo(1);
    }
}
