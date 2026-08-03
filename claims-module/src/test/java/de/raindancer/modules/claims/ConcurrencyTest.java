package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimPoint;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.store.ClaimRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim index, hit from several threads at once.
 *
 * <p>Not a hypothetical. On Folia every region has its own thread, and a claim being reshaped in one region while
 * another claim is created in another is an ordinary Tuesday. On plain Paper the async saver and the main thread
 * do the same thing to the same collections.
 *
 * <p>These reproduce by repetition rather than by contrivance: each runs the same race many times and fails when
 * the answer is wrong once. A single round would pass on a broken implementation nine times out of ten, which is
 * exactly how this class of bug survives a test suite.
 */
class ConcurrencyTest {

    private static final UUID WORLD = UUID.randomUUID();

    private static Claim claimAt(int x, int z, String name) {
        ClaimShape shape = new ClaimShape(List.of(
                new ClaimPoint(x, z), new ClaimPoint(x, z + 15),
                new ClaimPoint(x + 15, z + 15), new ClaimPoint(x + 15, z)), 0, 128);
        return new Claim(UUID.randomUUID(), name, WORLD, "world", shape, UUID.randomUUID());
    }

    @Test
    @DisplayName("a claim removed elsewhere never unindexes a claim being added")
    void addingAndRemovingDoNotLoseAClaim() throws Exception {
        // The race agy found: unindexSpatially pruned empty buckets with entrySet().removeIf, which could drop
        // a bucket another thread had just filled — leaving that claim indexed nowhere and its land silently
        // unprotected. Reproduced by adding and removing in the same chunk from two threads.
        ExecutorService threads = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 400; round++) {
                ClaimRegistry registry = new ClaimRegistry();
                Claim staying = claimAt(0, 0, "staying");
                Claim going = claimAt(0, 0, "going");
                registry.add(going);

                CountDownLatch go = new CountDownLatch(1);
                AtomicReference<Throwable> trouble = new AtomicReference<>();

                Runnable remover = () -> {
                    try {
                        go.await();
                        registry.remove(going);
                    } catch (Throwable caught) {
                        trouble.compareAndSet(null, caught);
                    }
                };
                Runnable adder = () -> {
                    try {
                        go.await();
                        registry.add(staying);
                    } catch (Throwable caught) {
                        trouble.compareAndSet(null, caught);
                    }
                };

                var first = threads.submit(remover);
                var second = threads.submit(adder);
                go.countDown();
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);

                assertThat(trouble.get()).as("round %s threw", round).isNull();
                assertThat(registry.at(worldOf(), 4, 64, 4))
                        .as("round %s lost the surviving claim: its ground is unprotected", round)
                        .isPresent();
            }
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("reading a claim's collections while it is changed does not blow up")
    void aClaimCanBeReadWhileItIsWrittenTo() throws Exception {
        // What the async saver does: walk a claim's members, bans and fence while the server thread is adding
        // to them. With plain HashMaps behind those, this is a ConcurrentModificationException — and because
        // the dirty flag is cleared before the write, the save is lost silently.
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Claim claim = claimAt(0, 0, "busy");
            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<Throwable> trouble = new AtomicReference<>();

            Runnable writer = () -> {
                try {
                    go.await();
                    for (int at = 0; at < 2_000; at++) {
                        claim.memberOrCreate(UUID.randomUUID());
                        claim.ban(de.raindancer.modules.claims.model.ClaimBan.permanent(
                                UUID.randomUUID(), UUID.randomUUID(), "spam"));
                    }
                } catch (Throwable caught) {
                    trouble.compareAndSet(null, caught);
                }
            };
            Runnable reader = () -> {
                try {
                    go.await();
                    for (int at = 0; at < 2_000; at++) {
                        // Exactly what the storage layer does to write one out.
                        new ArrayList<>(claim.members().values());
                        new ArrayList<>(claim.bans().values());
                        new ArrayList<>(claim.owners());
                    }
                } catch (Throwable caught) {
                    trouble.compareAndSet(null, caught);
                }
            };

            var first = threads.submit(writer);
            var second = threads.submit(reader);
            go.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

            assertThat(trouble.get())
                    .as("a claim being written out while it changes must not throw — the save is lost and "
                            + "the dirty flag has already been cleared")
                    .isNull();
        } finally {
            threads.shutdownNow();
        }
    }

    /** A world whose only useful answer is its id, which is all the registry asks of it. */
    private static org.bukkit.World worldOf() {
        return (org.bukkit.World) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.World.class.getClassLoader(),
                new Class<?>[]{org.bukkit.World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> WORLD;
                    case "getName" -> "world";
                    case "getMinHeight" -> -64;
                    case "getMaxHeight" -> 320;
                    case "toString" -> "a fake world";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
