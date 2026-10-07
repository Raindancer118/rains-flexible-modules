package de.raindancer.modules.veintoggle;

import de.miraculixx.veinminer.VeinMinerEvent;
import de.raindancer.modules.veintoggle.listener.VeinListener;
import de.raindancer.modules.veintoggle.rules.VeinRule;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The one place Veinminer is stopped: its extra blocks, for players who switched it off. */
class VeinListenerTest {

    private final Block block = mock(Block.class);
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<Player> told = new ArrayList<>();

    private Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    private VeinListener listener(Set<UUID> switchedOff) {
        return new VeinListener(new VeinRule(), who -> !switchedOff.contains(who.getUniqueId()), told::add,
                clock::get);
    }

    @Test
    @DisplayName("a player who switched it off gets no extra blocks; their own break goes ahead")
    void off() {
        Player off = player(UUID.randomUUID());
        VeinListener listener = listener(Set.of(off.getUniqueId()));
        VeinMinerEvent.VeinminerEvent extra = new VeinMinerEvent.VeinminerEvent(block, off);
        BlockBreakEvent own = new BlockBreakEvent(block, off);

        listener.onBreak(extra);
        listener.onBreak(own);

        assertThat(extra.isCancelled()).isTrue();
        assertThat(own.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("everybody else's veins are left to Veinminer")
    void on() {
        Player on = player(UUID.randomUUID());
        VeinMinerEvent.VeinminerEvent extra = new VeinMinerEvent.VeinminerEvent(block, on);

        listener(Set.of()).onBreak(extra);

        assertThat(extra.isCancelled()).isFalse();
        assertThat(told).isEmpty();
    }

    @Test
    @DisplayName("they are told once for a whole vein, not once a block — and again for the next vein later")
    void toldOnce() {
        Player off = player(UUID.randomUUID());
        VeinListener listener = listener(Set.of(off.getUniqueId()));

        for (int block = 0; block < 12; block++) {
            listener.onBreak(new VeinMinerEvent.VeinminerEvent(this.block, off));
        }
        assertThat(told).hasSize(1);

        clock.addAndGet(VeinListener.QUIET_FOR_MILLIS + 1);
        listener.onBreak(new VeinMinerEvent.VeinminerEvent(block, off));
        assertThat(told).hasSize(2);
    }
}
