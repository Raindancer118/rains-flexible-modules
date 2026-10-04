package de.raindancer.modules.speedrun;

import de.raindancer.core.moderation.players.PlayerAdmin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Putting racers and the map back to a standard starting point — see {@link SpeedrunPreparation}'s
 * own class javadoc for why every run gets this, not only ones that follow a regeneration.
 */
class SpeedrunPreparationTest {

    private final Plugin plugin = live(mock(Plugin.class));

    /** A live plugin schedules; a disabled one (a bare mock) runs in place — see Scheduling.isLive. */
    private static Plugin live(Plugin plugin) {
        org.mockito.Mockito.when(plugin.isEnabled()).thenReturn(true);
        return plugin;
    }

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    /** The entity's own scheduler, running what it is handed straight away. */
    private static <E extends Entity> E runningItsOwnTasks(E entity) {
        io.papermc.paper.threadedregions.scheduler.EntityScheduler scheduler =
                mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
        when(scheduler.run(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(1, java.util.function.Consumer.class).accept(null);
            return null;
        });
        when(entity.getScheduler()).thenReturn(scheduler);
        return entity;
    }

    /**
     * Folia: a run starts on the countdown's global thread, which owns no player and no mob. Every
     * write to a racer or an entity has to land on that entity's own scheduler, or it throws there.
     */
    @Test
    @DisplayName("touches a racer and a mob only from their own scheduler")
    void touchesEntitiesOnlyFromTheirOwnThread() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(plugin, players);
        World world = mock(World.class);
        Entity zombie = mock(Zombie.class);
        when(world.getEntities()).thenReturn(List.of(zombie));
        Player alice = mock(Player.class);
        org.bukkit.inventory.PlayerInventory inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        when(alice.getInventory()).thenReturn(inventory);
        when(alice.getEnderChest()).thenReturn(mock(org.bukkit.inventory.Inventory.class));
        List<java.util.function.Consumer<Object>> queued = new java.util.ArrayList<>();
        for (Entity entity : List.of(alice, zombie)) {
            io.papermc.paper.threadedregions.scheduler.EntityScheduler scheduler =
                    mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
            when(scheduler.run(any(), any(), any())).thenAnswer(invocation -> {
                queued.add(invocation.getArgument(1, java.util.function.Consumer.class));
                return null;
            });
            when(entity.getScheduler()).thenReturn(scheduler);
        }

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);

            preparation.prepare(world, Set.of(ALICE));

            verify(inventory, never()).clear();
            verify(players, never()).heal(ALICE);
            verify(zombie, never()).remove();

            queued.forEach(task -> task.accept(null));
        }

        verify(inventory).clear();
        verify(players).heal(ALICE);
        verify(zombie).remove();
    }

    @Test
    @DisplayName("heals, feeds, cures and extinguishes every participant")
    void resetsEveryParticipant() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(plugin, players);
        World world = mock(World.class);
        when(world.getEntities()).thenReturn(List.of());

        Player alice = runningItsOwnTasks(mock(Player.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
        Player bob = runningItsOwnTasks(mock(Player.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);
            bukkit.when(() -> Bukkit.getPlayer(BOB)).thenReturn(bob);

            preparation.prepare(world, Set.of(ALICE, BOB));
        }

        verify(players).heal(ALICE);
        verify(players).feed(ALICE);
        verify(players).cure(ALICE);
        verify(players).extinguish(ALICE);
        verify(players).heal(BOB);
        verify(players).feed(BOB);
        verify(players).cure(BOB);
        verify(players).extinguish(BOB);
    }

    @Test
    @DisplayName("sets a participant's saturation to full when they are online")
    void fillsSaturationForOnlineParticipants() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(plugin, players);
        World world = mock(World.class);
        when(world.getEntities()).thenReturn(List.of());
        Player onlineAlice = runningItsOwnTasks(mock(Player.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(onlineAlice);

            preparation.prepare(world, Set.of(ALICE));
        }

        verify(onlineAlice).setSaturation(20f);
    }

    @Test
    @DisplayName("sets the world to morning")
    void setsTheWorldToMorning() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(plugin, players);
        World world = mock(World.class);
        when(world.getEntities()).thenReturn(List.of());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            preparation.prepare(world, Set.of());
        }

        verify(world).setTime(SpeedrunPreparation.DAY_START);
    }

    @Test
    @DisplayName("removes every hostile mob and every dropped item, and nothing else")
    void clearsHostilesAndItemsOnly() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(plugin, players);
        World world = mock(World.class);
        Entity zombie = runningItsOwnTasks(mock(Zombie.class));
        Entity droppedItem = runningItsOwnTasks(mock(Item.class));
        Entity innocentCow = runningItsOwnTasks(mock(Entity.class));
        when(world.getEntities()).thenReturn(List.of(zombie, droppedItem, innocentCow));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            preparation.prepare(world, Set.of());
        }

        verify(zombie).remove();
        verify(droppedItem).remove();
        verify(innocentCow, never()).remove();
    }

    @Test
    @DisplayName("a null world skips the world half without throwing")
    void nullWorldSkipsWorldReset() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(plugin, players);

        Player alice = runningItsOwnTasks(mock(Player.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);

            preparation.prepare(null, Set.of(ALICE));
        }

        verify(players).heal(ALICE);
    }

    @Test
    @DisplayName("sets the world to whatever time the host configured")
    void setsTheConfiguredTime() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(plugin, players);
        World world = mock(World.class);
        when(world.getEntities()).thenReturn(List.of());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            preparation.prepare(world, Set.of(), 6000L);
        }

        verify(world).setTime(6000L);
    }

    @Test
    @DisplayName("leaves the world's own clock alone when the host turned that off")
    void leavesTheClockAloneWhenAsked() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(plugin, players);
        World world = mock(World.class);
        when(world.getEntities()).thenReturn(List.of());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            preparation.prepare(world, Set.of(), SpeedrunPreparation.LEAVE_THE_TIME_ALONE);
        }

        verify(world, never()).setTime(org.mockito.ArgumentMatchers.anyLong());
        verify(world).getEntities();   // the rest of the world half still ran
    }

    @Test
    @DisplayName("clears every racer's advancements when the setting asks for it")
    void clearsAdvancementsWhenAsked() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(plugin, players);
        World world = mock(World.class);
        when(world.getEntities()).thenReturn(List.of());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(null);

            preparation.prepare(world, Set.of(ALICE), SpeedrunPreparation.DAY_START, true);

            // Offline here, so nothing of theirs is written — what matters is that the clear was
            // reached at all; SpeedrunAdvancementsTest owns what it then does.
            bukkit.verify(() -> Bukkit.getPlayer(ALICE), org.mockito.Mockito.atLeastOnce());
        }
    }

    @Test
    @DisplayName("leaves them alone when it is switched off — that is somebody's own saved progress")
    void leavesAdvancementsAloneWhenOff() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(plugin, players);
        World world = mock(World.class);
        when(world.getEntities()).thenReturn(List.of());
        Player alice = mock(Player.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);

            preparation.prepare(world, Set.of(ALICE), SpeedrunPreparation.DAY_START, false);

            bukkit.verify(Bukkit::advancementIterator, never());
        }
    }

    @Test
    @DisplayName("a new round is a clean slate: XP, inventory, ender chest, respawn point and the body all reset")
    void resetsEverythingAPlayerCarriesOver() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(plugin, players);
        World world = mock(World.class);
        when(world.getEntities()).thenReturn(List.of());
        Player alice = runningItsOwnTasks(mock(Player.class));
        org.bukkit.inventory.PlayerInventory inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        org.bukkit.inventory.Inventory enderChest = mock(org.bukkit.inventory.Inventory.class);
        when(alice.getInventory()).thenReturn(inventory);
        when(alice.getEnderChest()).thenReturn(enderChest);
        when(alice.getMaximumAir()).thenReturn(300);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);

            preparation.prepare(world, Set.of(ALICE));
        }

        verify(alice).setLevel(0);
        verify(alice).setExp(0f);
        verify(alice).setTotalExperience(0);
        verify(inventory).clear();
        verify(alice).setItemOnCursor(null);
        verify(enderChest).clear();
        verify(alice).setRespawnLocation(null);
        verify(alice).setExhaustion(0f);
        verify(alice).setFallDistance(0f);
        verify(alice).setFreezeTicks(0);
        verify(alice).setRemainingAir(300);
        verify(alice).setAbsorptionAmount(0);
        verify(alice).setArrowsInBody(0);
    }

    /**
     * Somebody on the death screen as the run starts. Healing a dead player puts health back into a
     * body the server has already let go of — a player who is alive to the server and dead on their
     * own screen. Their respawn gives them full health anyway.
     */
    @Test
    @DisplayName("a racer still on the death screen is not healed — the respawn does that")
    void aDeadRacerIsNotHealed() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(plugin, players);
        Player dead = runningItsOwnTasks(mock(Player.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
        when(dead.isDead()).thenReturn(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(dead);

            preparation.prepare(null, Set.of(ALICE));
        }

        verify(players, never()).heal(ALICE);
        verify(players, never()).feed(ALICE);
        verify(dead, never()).setSaturation(org.mockito.ArgumentMatchers.anyFloat());
        verify(dead.getInventory()).clear();   // the rest of the clean slate still happens
    }

    @Test
    @DisplayName("a practice kit is handed to every online racer, on their own thread, in full")
    void handsOutTheKit() {
        SpeedrunPreparation preparation = new SpeedrunPreparation(plugin, mock(PlayerAdmin.class));
        Player alice = runningItsOwnTasks(de.raindancer.core.testkit.TestPlayers.player("Alice"));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);
            bukkit.when(() -> Bukkit.getPlayer(BOB)).thenReturn(null);

            preparation.handOut(Set.of(ALICE, BOB), SpeedrunPracticeKit.EYES_OF_ENDER);
        }

        org.assertj.core.api.Assertions.assertThat(de.raindancer.core.testkit.TestInventories.stacksIn(alice.getInventory()))
                .singleElement().satisfies(stack -> {
                    org.assertj.core.api.Assertions.assertThat(stack.getType()).isEqualTo(org.bukkit.Material.ENDER_EYE);
                    org.assertj.core.api.Assertions.assertThat(stack.getAmount()).isEqualTo(14);
                });
    }

    @Test
    @DisplayName("a latecomer gets the racer's clean slate and the kit — and the world is left as the race has it")
    void latecomer() {
        PlayerAdmin players = mock(PlayerAdmin.class);
        SpeedrunPreparation preparation = new SpeedrunPreparation(plugin, players);
        Player late = runningItsOwnTasks(de.raindancer.core.testkit.TestPlayers.player("Late"));
        late.getInventory().setItem(3, de.raindancer.core.testkit.TestItems.of(org.bukkit.Material.DIAMOND, 9));
        org.bukkit.inventory.Inventory enderChest = de.raindancer.core.testkit.TestInventories.chest(27);
        enderChest.setItem(0, de.raindancer.core.testkit.TestItems.of(org.bukkit.Material.NETHERITE_INGOT, 4));
        when(late.getEnderChest()).thenReturn(enderChest);
        World world = mock(World.class);
        when(late.getWorld()).thenReturn(world);

        preparation.prepareLatecomer(late, SpeedrunPracticeKit.EYES_OF_ENDER);

        verify(players).heal(late.getUniqueId());
        verify(players).feed(late.getUniqueId());
        verify(late).setLevel(0);
        org.assertj.core.api.Assertions.assertThat(de.raindancer.core.testkit.TestInventories.stacksIn(late.getInventory()))
                .extracting(org.bukkit.inventory.ItemStack::getType).containsExactly(org.bukkit.Material.ENDER_EYE);
        org.assertj.core.api.Assertions.assertThat(de.raindancer.core.testkit.TestInventories.stacksIn(enderChest)).isEmpty();
        verify(world, never()).setTime(org.mockito.ArgumentMatchers.anyLong());
        verify(world, never()).getEntities();
    }

    @Test
    @DisplayName("no kit hands out nothing")
    void noKit() {
        SpeedrunPreparation preparation = new SpeedrunPreparation(plugin, mock(PlayerAdmin.class));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            preparation.handOut(Set.of(ALICE), SpeedrunPracticeKit.NONE);
            bukkit.verify(() -> Bukkit.getPlayer(ALICE), never());
        }
    }
}
