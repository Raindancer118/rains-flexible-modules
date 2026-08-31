package de.raindancer.modules.manhunt.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private FakeGateway gateway;
    private ManhuntWhitelistService service;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        service = new ManhuntWhitelistService(gateway);
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
}
