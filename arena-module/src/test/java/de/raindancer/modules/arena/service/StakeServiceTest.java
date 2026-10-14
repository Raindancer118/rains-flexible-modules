package de.raindancer.modules.arena.service;

import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.modules.arena.api.ArenaProtocol;
import de.raindancer.modules.arena.api.wire.NetworkWire;
import de.raindancer.modules.arena.api.wire.Wire;
import de.raindancer.modules.arena.store.SettlementBook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** The arena asking two survival servers for one fight's money — over the network, in one JVM. */
class StakeServiceTest {

    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BO = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CY = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<AutoCloseable> open = new ArrayList<>();
    private final Map<UUID, String> homes = Map.of(ADA, "smp-1", BO, "smp-1", CY, "smp-2");
    private final List<String> heard = new CopyOnWriteArrayList<>();

    private NetworkWire wire(String id) {
        NetworkCalls calls = new NetworkCalls(hub.bus(id));
        NetworkWire.Directory directory = new NetworkWire.Directory() {
            @Override
            public Set<String> servers() {
                return Set.of("arena", "smp-1", "smp-2");
            }

            @Override
            public Optional<Wire.Found> named(String name) {
                return Optional.empty();
            }

            @Override
            public Set<String> names() {
                return Set.of();
            }
        };
        NetworkWire wire = new NetworkWire(calls, id, directory, (player, server) -> { });
        open.add(wire::close);
        open.add(calls);
        return wire;
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable thing : open) {
            thing.close();
        }
    }

    private StakeService arena(Duration wait) {
        ArenaLink link = new ArenaLink(wire("arena"), player -> Optional.ofNullable(homes.get(player)), wait);
        return new StakeService(link, new SettlementBook(folder.resolve("settlements.yml")), UUID::toString);
    }

    private SettlementBook book() {
        return new SettlementBook(folder.resolve("settlements.yml"));
    }

    /** What an escrow does: says what it was asked, and answers {@code ok}. */
    private void escrow(String id, boolean accepts) {
        NetworkWire smp = wire(id);
        smp.onLater(ArenaProtocol.STAKE_HOLD, (peer, data) -> {
            heard.add(id + " hold " + data.get("players") + " " + data.get("amount"));
            return java.util.concurrent.CompletableFuture.completedFuture(
                    accepts ? Map.of("ok", "true") : Map.of("ok", "false", "who", "Cy"));
        });
        smp.onLater(ArenaProtocol.STAKE_PAY, (peer, data) -> {
            heard.add(id + " pay " + data.get("payouts") + " of " + data.get("participants"));
            return java.util.concurrent.CompletableFuture.completedFuture(Map.of("ok", "true"));
        });
        smp.onLater(ArenaProtocol.STAKE_REFUND, (peer, data) -> {
            heard.add(id + " refund");
            return java.util.concurrent.CompletableFuture.completedFuture(Map.of("ok", "true"));
        });
    }

    @Test
    @DisplayName("each survival server is asked to hold the stake of its own players only")
    void holdIsSplitByServer() throws Exception {
        escrow("smp-1", true);
        escrow("smp-2", true);
        StakeService stakes = arena(Duration.ofSeconds(5));

        assertThat(stakes.hold(UUID.randomUUID(), List.of(ADA, BO, CY), 100).get(10, TimeUnit.SECONDS)).isEmpty();

        assertThat(heard).containsExactlyInAnyOrder("smp-1 hold " + ADA + "," + BO + " 100", "smp-2 hold " + CY + " 100");
    }

    @Test
    @DisplayName("one server that cannot take the stake calls the fight off, naming who")
    void oneRefuses() throws Exception {
        escrow("smp-1", true);
        escrow("smp-2", false);
        StakeService stakes = arena(Duration.ofSeconds(5));

        assertThat(stakes.hold(UUID.randomUUID(), List.of(ADA, CY), 100).get(10, TimeUnit.SECONDS)).contains("Cy");
    }

    @Test
    @DisplayName("somebody nobody sent over has no survival server to take a stake from")
    void noHome() throws Exception {
        escrow("smp-1", true);
        StakeService stakes = arena(Duration.ofSeconds(5));

        assertThat(stakes.hold(UUID.randomUUID(), List.of(ADA, UUID.randomUUID()), 100).get(10, TimeUnit.SECONDS))
                .hasValueSatisfying(who -> assertThat(who).contains("no survival server"));
        assertThat(heard).isEmpty();
    }

    @Test
    @DisplayName("a winner from the other server is paid out of both pots, and each server pays only what it holds")
    void payAcrossServers() throws Exception {
        escrow("smp-1", true);
        escrow("smp-2", true);
        StakeService stakes = arena(Duration.ofSeconds(5));
        UUID fight = UUID.randomUUID();
        stakes.hold(fight, List.of(ADA, BO, CY), 100).get(10, TimeUnit.SECONDS);
        heard.clear();

        stakes.pay(fight, Map.of(CY, 300L));
        Thread.sleep(500);

        assertThat(heard).containsExactlyInAnyOrder(
                "smp-1 pay " + CY + "=200 of " + ADA + "," + BO + "," + CY,
                "smp-2 pay " + CY + "=100 of " + ADA + "," + BO + "," + CY);
        assertThat(book().open()).isEmpty();
    }

    @Test
    @DisplayName("a server that does not answer is asked again, and the settlement is kept until it does")
    void unansweredIsRetried() throws Exception {
        StakeService stakes = arena(Duration.ofMillis(400));
        UUID fight = UUID.randomUUID();
        stakes.hold(fight, List.of(ADA), 100);

        stakes.refund(fight);
        Thread.sleep(800);
        assertThat(book().open()).hasSize(1);

        escrow("smp-1", true);
        stakes.retry();
        Thread.sleep(500);

        assertThat(heard).contains("smp-1 refund");
        assertThat(book().open()).isEmpty();
    }

    @Test
    @DisplayName("a refund goes to every server that was asked to hold, even when its answer was lost")
    void refundReachesWhoWasAsked() throws Exception {
        escrow("smp-1", true);
        AtomicBoolean answered = new AtomicBoolean();
        NetworkWire silent = wire("smp-2");
        silent.on(ArenaProtocol.STAKE_REFUND, (peer, data) -> {
            answered.set(true);
            return Map.of("ok", "true");
        });
        StakeService stakes = arena(Duration.ofMillis(400));
        UUID fight = UUID.randomUUID();

        assertThat(stakes.hold(fight, List.of(ADA, CY), 100).get(10, TimeUnit.SECONDS)).isPresent();
        stakes.refund(fight);
        Thread.sleep(500);

        assertThat(heard).contains("smp-1 refund");
        assertThat(answered).isTrue();
        assertThat(book().open()).isEmpty();
    }

    @Test
    @DisplayName("the shares: own pot first, then the others, never more than a pot holds")
    void allocation() {
        Map<String, Long> pots = new LinkedHashMap<>();
        pots.put("smp-1", 200L);
        pots.put("smp-2", 100L);
        Map<UUID, String> homeOf = Map.of(ADA, "smp-1", BO, "smp-1", CY, "smp-2");

        assertThat(StakeService.allocate(pots, homeOf, Map.of(CY, 300L)))
                .containsEntry("smp-1", Map.of(CY, 200L)).containsEntry("smp-2", Map.of(CY, 100L));
        assertThat(StakeService.allocate(pots, homeOf, Map.of(ADA, 150L)))
                .containsEntry("smp-1", Map.of(ADA, 150L)).containsEntry("smp-2", Map.of());
        assertThat(StakeService.allocate(pots, homeOf, Map.of(ADA, 500L)).values().stream()
                .flatMap(share -> share.values().stream()).mapToLong(Long::longValue).sum()).isEqualTo(300L);
    }
}
