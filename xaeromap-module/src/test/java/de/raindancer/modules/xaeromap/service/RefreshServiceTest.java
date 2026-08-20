package de.raindancer.modules.xaeromap.service;

import de.raindancer.modules.xaeromap.Facts;
import de.raindancer.modules.xaeromap.XaeroMapSettings;
import de.raindancer.modules.xaeromap.claims.ClaimSource;
import de.raindancer.modules.xaeromap.model.ClaimFacts;
import de.raindancer.modules.xaeromap.model.OpacPackets;
import de.raindancer.modules.xaeromap.rules.RefreshDueRule;
import de.raindancer.modules.xaeromap.store.ClaimMirror;
import de.raindancer.modules.xaeromap.store.SyncIndexTable;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the refresh clock does the work when it is due, and — more to the point — that it does none
 * when there is nobody to do it for.
 */
class RefreshServiceTest {

    private final Instant now = Instant.parse("2026-08-20T12:00:00Z");
    private final List<ClaimFacts> claims = new ArrayList<>();
    private final AtomicInteger builds = new AtomicInteger();

    private FakeWire wire;
    private ClaimSyncService sync;
    private RefreshService refresh;
    private Player player;

    @BeforeEach
    void setUp() {
        wire = new FakeWire();
        ClaimSource counting = new ClaimSource() {

            @Override
            public String name() {
                return "a test";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<ClaimFacts> claims() {
                builds.incrementAndGet();
                return List.copyOf(claims);
            }
        };
        sync = new ClaimSyncService(wire, () -> counting, new SyncIndexTable(), new ClaimMirror(),
                Mockito.mock(de.raindancer.core.platform.log.LogChannel.class),
                XaeroMapSettings.DEFAULTS);
        refresh = new RefreshService(sync, new RefreshDueRule(), XaeroMapSettings.DEFAULTS);
        player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        claims.add(Facts.claim("Home", UUID.randomUUID(), Facts.OVERWORLD, Facts.chunk(0, 0)));
    }

    private void clientIsReady() {
        sync.offer(player);
        sync.onClientMessage(player, OpacPackets.regionsStart());
        builds.set(0);
        wire.clear();
    }

    @Test
    @DisplayName("nobody online, nothing is walked")
    void anEmptyServerCostsNothing() {
        refresh.tick(List.of(), now);

        assertThat(builds.get()).isZero();
        assertThat(refresh.lastRefresh()).isNull();
    }

    @Test
    @DisplayName("nobody running a map mod, nothing is walked either")
    void aServerWithNoMapModsCostsNothing() {
        refresh.tick(List.of(player), now);

        assertThat(builds.get())
                .as("walking every claim on the server to send it to nobody is the cost this "
                        + "module must not have on a server where nobody has the mod")
                .isZero();
    }

    @Test
    @DisplayName("with somebody listening, a due tick does the work")
    void aDueTickRefreshes() {
        clientIsReady();

        refresh.tick(List.of(player), now);

        assertThat(builds.get()).isEqualTo(1);
        assertThat(refresh.lastRefresh()).isEqualTo(now);
    }

    @Test
    @DisplayName("ticks between one refresh and the next do nothing")
    void theIntervalIsKept() {
        clientIsReady();
        refresh.tick(List.of(player), now);
        builds.set(0);

        refresh.tick(List.of(player), now.plusSeconds(1));
        refresh.tick(List.of(player), now.plusSeconds(2));

        assertThat(builds.get()).isZero();

        refresh.tick(List.of(player), now.plusSeconds(5));

        assertThat(builds.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a changed interval takes effect without a restart")
    void reloadingChangesThePace() {
        clientIsReady();
        refresh.tick(List.of(player), now);
        refresh.settings(XaeroMapSettings.DEFAULTS.withRefreshSeconds(60));
        builds.set(0);

        refresh.tick(List.of(player), now.plusSeconds(10));

        assertThat(builds.get())
                .as("the whole reason the clock is a fixed short poll asking a rule, rather than a "
                        + "timer scheduled at the configured interval")
                .isZero();

        refresh.tick(List.of(player), now.plusSeconds(61));

        assertThat(builds.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("claims switched off stops the clock rather than sending nothing repeatedly")
    void switchingClaimsOffStopsTheWork() {
        clientIsReady();
        refresh.settings(XaeroMapSettings.DEFAULTS.withClaims(false));

        refresh.tick(List.of(player), now);

        assertThat(builds.get()).isZero();
    }
}
