package de.raindancer.modules.manhunt.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ManhuntWhitelistServiceTest {

    /** A whitelist and an online roster, both in memory — no Bukkit anywhere near this. */
    private static final class FakeGateway implements WhitelistGateway {
        final Set<UUID> online = new LinkedHashSet<>();
        final Set<UUID> whitelisted = new LinkedHashSet<>();
        boolean enabled;

        @Override
        public Collection<UUID> onlinePlayerIds() {
            return Set.copyOf(online);
        }

        @Override
        public Collection<UUID> whitelistedIds() {
            return Set.copyOf(whitelisted);
        }

        @Override
        public boolean isWhitelisted(UUID id) {
            return whitelisted.contains(id);
        }

        @Override
        public void setWhitelisted(UUID id, boolean value) {
            if (value) {
                whitelisted.add(id);
            } else {
                whitelisted.remove(id);
            }
        }

        @Override
        public boolean isWhitelistEnabled() {
            return enabled;
        }

        @Override
        public void setWhitelistEnabled(boolean value) {
            enabled = value;
        }
    }

    private static final UUID ONLINE_1 = UUID.randomUUID();
    private static final UUID ONLINE_2 = UUID.randomUUID();
    private static final UUID ALREADY_WHITELISTED_BY_HAND = UUID.randomUUID();

    private static final UUID VIP = UUID.randomUUID();

    @TempDir
    Path directory;

    private FakeGateway gateway;
    private WhitelistVips vips;
    private ManhuntWhitelistService service;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        vips = new WhitelistVips(directory.resolve("whitelist-vips.yml"));
        service = new ManhuntWhitelistService(gateway, vips);
    }

    @Test
    void openTurnsTheFlagOffAndTouchesNoEntries() {
        gateway.enabled = true;
        gateway.whitelisted.add(ALREADY_WHITELISTED_BY_HAND);

        service.open();

        assertThat(service.isClosed()).isFalse();
        assertThat(gateway.whitelisted).containsExactly(ALREADY_WHITELISTED_BY_HAND);
    }

    @Test
    void closeWhitelistsEverybodyCurrentlyOnlineAndTurnsTheFlagOn() {
        gateway.online.add(ONLINE_1);
        gateway.online.add(ONLINE_2);

        int added = service.close();

        assertThat(added).isEqualTo(2);
        assertThat(gateway.whitelisted).containsExactlyInAnyOrder(ONLINE_1, ONLINE_2);
        assertThat(service.isClosed()).isTrue();
    }

    @Test
    void closeNeverRemovesAnExistingEntry() {
        gateway.whitelisted.add(ALREADY_WHITELISTED_BY_HAND);
        gateway.online.add(ONLINE_1);

        service.close();

        assertThat(gateway.whitelisted)
                .containsExactlyInAnyOrder(ALREADY_WHITELISTED_BY_HAND, ONLINE_1);
    }

    @Test
    void closeDoesNotCountSomebodyAlreadyWhitelisted() {
        gateway.online.add(ONLINE_1);
        gateway.whitelisted.add(ONLINE_1);

        int added = service.close();

        assertThat(added).isZero();
        assertThat(gateway.whitelisted).containsExactly(ONLINE_1);
    }

    @Test
    void closingAnEmptyServerStillShutsTheDoor() {
        int added = service.close();

        assertThat(added).isZero();
        assertThat(service.isClosed()).isTrue();
    }

    /**
     * The people a hunt's door never shuts on, and a clear never sweeps up — the server's owner, the
     * friend who is always allowed in, whoever is running the evening from outside the game.
     */
    @Nested
    @DisplayName("VIPs")
    class Vips {

        @Test
        @DisplayName("closing lets a VIP in even though they are not online to be swept up")
        void closeWhitelistsVipsToo() {
            vips.add(VIP, "Vip");
            gateway.online.add(ONLINE_1);

            int added = service.close();

            assertThat(added).isEqualTo(2);
            assertThat(gateway.whitelisted).containsExactlyInAnyOrder(ONLINE_1, VIP);
            assertThat(service.isClosed()).isTrue();
        }

        @Test
        @DisplayName("clearing empties the whitelist but leaves every VIP on it")
        void clearKeepsVips() {
            vips.add(VIP, "Vip");
            gateway.whitelisted.add(VIP);
            gateway.whitelisted.add(ONLINE_1);
            gateway.whitelisted.add(ALREADY_WHITELISTED_BY_HAND);

            int removed = service.clear();

            assertThat(removed).isEqualTo(2);
            assertThat(gateway.whitelisted).containsExactly(VIP);
        }

        @Test
        @DisplayName("clearing leaves the door exactly as open or shut as it was")
        void clearDoesNotTouchTheFlag() {
            gateway.enabled = true;
            gateway.whitelisted.add(ONLINE_1);

            service.clear();

            assertThat(service.isClosed()).isTrue();
            assertThat(gateway.whitelisted).isEmpty();
        }

        @Test
        @DisplayName("clearing an already-empty whitelist is a no-op that says nothing happened")
        void clearingNothingRemovesNothing() {
            assertThat(service.clear()).isZero();
        }

        @Test
        @DisplayName("a VIP added while the door is already shut is let in straight away")
        void addingAVipWhileClosedWhitelistsThem() {
            gateway.enabled = true;

            assertThat(service.addVip(VIP, "Vip")).isTrue();

            assertThat(gateway.whitelisted).containsExactly(VIP);
        }

        @Test
        @DisplayName("a VIP added while the door is open is not whitelisted for no reason")
        void addingAVipWhileOpenTouchesNothing() {
            service.addVip(VIP, "Vip");

            assertThat(gateway.whitelisted).isEmpty();
            assertThat(service.vips().isVip(VIP)).isTrue();
        }

        @Test
        @DisplayName("removing a VIP takes the badge off and leaves the whitelist entry alone")
        void removingAVipLeavesTheEntry() {
            gateway.enabled = true;
            service.addVip(VIP, "Vip");

            assertThat(service.removeVip(VIP)).isTrue();

            assertThat(service.vips().isVip(VIP)).isFalse();
            // Still whitelisted: they were let in, and taking that back is a separate decision —
            // /whitelist remove, or the next clear, which no longer spares them.
            assertThat(gateway.whitelisted).containsExactly(VIP);
        }
    }
}
