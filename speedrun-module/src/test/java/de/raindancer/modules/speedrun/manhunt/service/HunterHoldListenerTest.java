package de.raindancer.modules.speedrun.manhunt.service;

import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import de.raindancer.modules.speedrun.Steps;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Runners' head start: the Hunters stand still and touch nothing until it is over. */
@DisplayName("the Hunters' hold during the head start")
class HunterHoldListenerTest {

    private static final UUID RUNNER = UUID.nameUUIDFromBytes("runner".getBytes());
    private static final UUID HUNTER = UUID.nameUUIDFromBytes("hunter".getBytes());

    private final World world = mock(World.class);
    private final Player hunter = player(HUNTER);
    private Hunt hunt;
    private HunterHoldListener hold;

    @BeforeEach
    void setUp() {
        hunt = Hunt.of(Set.of(RUNNER, HUNTER), Set.of(RUNNER));
        hold = new HunterHoldListener(hunt);
    }

    private static Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    private PlayerMoveEvent step(UUID who, double toX) {
        return new PlayerMoveEvent(player(who), new Location(world, 0.5, 64, 0.5),
                new Location(world, toX, 64, 0.5));
    }

    @Test
    @DisplayName("a Hunter cannot take a step, but may look around")
    void huntersStandStill() {
        PlayerMoveEvent walking = step(HUNTER, 3.5);
        PlayerMoveEvent looking = step(HUNTER, 0.7);

        hold.onMove(walking);
        hold.onMove(looking);

        assertThat(Steps.held(walking)).isTrue();
        assertThat(looking.isCancelled()).isFalse();
        assertThat(looking.getTo().getX()).isEqualTo(0.7);
        assertThat(Steps.heldLookingAround(walking, 0f)).isTrue();
    }

    @Test
    @DisplayName("the Runners run")
    void runnersRun() {
        PlayerMoveEvent running = step(RUNNER, 30.5);

        hold.onMove(running);

        assertThat(Steps.held(running)).isFalse();
    }

    @Test
    @DisplayName("a held Hunter cannot break, place or use anything")
    void huntersTouchNothing() {
        BlockBreakEvent breaking = mock(BlockBreakEvent.class);
        when(breaking.getPlayer()).thenReturn(hunter);
        BlockPlaceEvent placing = mock(BlockPlaceEvent.class);
        when(placing.getPlayer()).thenReturn(hunter);
        PlayerInteractEvent using = mock(PlayerInteractEvent.class);
        when(using.getPlayer()).thenReturn(hunter);

        hold.onBreak(breaking);
        hold.onPlace(placing);
        hold.onInteract(using);

        verify(breaking).setCancelled(true);
        verify(placing).setCancelled(true);
        verify(using).setCancelled(true);
    }

    @Test
    @DisplayName("a held Hunter cannot hurt anybody, in person or with an arrow")
    void huntersHurtNobody() {
        EntityDamageByEntityEvent punch = mock(EntityDamageByEntityEvent.class);
        when(punch.getDamager()).thenReturn(hunter);
        Arrow arrow = mock(Arrow.class);
        Player shooter = player(HUNTER);
        when(arrow.getShooter()).thenReturn(shooter);
        EntityDamageByEntityEvent shot = mock(EntityDamageByEntityEvent.class);
        when(shot.getDamager()).thenReturn(arrow);

        hold.onDamage(punch);
        hold.onDamage(shot);

        verify(punch).setCancelled(true);
        verify(shot).setCancelled(true);
    }

    @Test
    @DisplayName("released, everything is allowed again")
    void released() {
        hold.release();
        PlayerMoveEvent walking = step(HUNTER, 3.5);
        BlockBreakEvent breaking = mock(BlockBreakEvent.class);
        when(breaking.getPlayer()).thenReturn(hunter);

        hold.onMove(walking);
        hold.onBreak(breaking);

        assertThat(hold.isHolding()).isFalse();
        assertThat(Steps.held(walking)).isFalse();
        verify(breaking, never()).setCancelled(true);
    }

    @Test
    @DisplayName("a Runner who turns Hunter mid head start is held too — the roster is asked, not copied")
    void sideChangeIsFollowed() {
        // Two Runners, so one of them may give up running.
        hunt.join(UUID.randomUUID(), true);
        hunt.moveToHunters(RUNNER);
        PlayerMoveEvent formerRunner = step(RUNNER, 3.5);

        hold.onMove(formerRunner);

        assertThat(Steps.held(formerRunner)).isTrue();
    }

    @Test
    @DisplayName("after the head start, one Hunter can be held on their own until their time is up")
    void personalHold() {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(0);
        HunterHoldListener timed = new HunterHoldListener(hunt, now::get);
        timed.release();

        timed.holdFor(HUNTER, 5);
        PlayerMoveEvent walking = step(HUNTER, 3.5);
        timed.onMove(walking);
        assertThat(Steps.held(walking)).isTrue();
        assertThat(timed.secondsLeft(HUNTER)).isEqualTo(5);

        now.set(5_001);
        PlayerMoveEvent later = step(HUNTER, 3.5);
        timed.onMove(later);
        assertThat(Steps.held(later)).isFalse();
    }
}
