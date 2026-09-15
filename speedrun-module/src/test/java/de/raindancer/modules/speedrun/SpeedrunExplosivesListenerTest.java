package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a player can still set off during a run, dimension by dimension — the events that ask
 * {@link SpeedrunExplosive}'s matrix. See {@link SpeedrunExplosiveTest} for the matrix itself.
 */
class SpeedrunExplosivesListenerTest {

    private SpeedrunLobby lobby;
    private Messages messages;
    private SpeedrunExplosivesListener listener;

    /** Defaults, with every one of the ten explosive switches turned off. */
    private static SpeedrunSettings nothingExplodes() {
        SpeedrunSettings base = SpeedrunSettings.DEFAULTS;
        return new SpeedrunSettings(base.gameMode(), "world", base.advancementKey(),
                base.deathPolicy(), base.requireExitPortalAfterDragon(), 0, 0, 0, 0,
                false, 0, 0, 0, 0, 0, true, true, true, true, true, 10, true, 1000,
                false, false, false, false, false, false, false, false, false, false,
                false, false);
    }

    private static SpeedrunSettings everythingExplodes() {
        SpeedrunSettings off = nothingExplodes();
        return new SpeedrunSettings(off.gameMode(), off.worldName(), off.advancementKey(),
                off.deathPolicy(), off.requireExitPortalAfterDragon(), 0, 0, 0, 0,
                false, 0, 0, 0, 0, 0, true, true, true, true, true, 10, true, 1000,
                true, true, true, true, true, true, true, true, true, true,
                false, false);
    }

    @BeforeEach
    void setUp() {
        lobby = mock(SpeedrunLobby.class);
        messages = mock(Messages.class);
        listener = new SpeedrunExplosivesListener(lobby, messages);
        when(lobby.config()).thenReturn(nothingExplodes());
    }

    private World world(String name, World.Environment environment) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getEnvironment()).thenReturn(environment);
        return world;
    }

    private Player playerIn(World world) {
        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(world);
        return player;
    }

    private PlayerInteractEvent clickOn(Material material, World where) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        when(block.getWorld()).thenReturn(where);
        return new PlayerInteractEvent(playerIn(where), Action.RIGHT_CLICK_BLOCK, null, block,
                org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND);
    }

    @Nested
    @DisplayName("beds and respawn anchors — the two a click sets off")
    class ClickedBlocks {

        @Test
        @DisplayName("a bed in the run's Nether does nothing at all when they are switched off")
        void bedInTheNetherIsRefused() {
            PlayerInteractEvent event = clickOn(Material.RED_BED, world("world_nether", World.Environment.NETHER));

            listener.onInteract(event);

            assertThat(event.useInteractedBlock()).isEqualTo(org.bukkit.event.Event.Result.DENY);
            verify(messages).send(event.getPlayer(), "speedrun.explosives.refused");
        }

        @Test
        @DisplayName("the same bed explodes as always once the host leaves them on")
        void bedInTheNetherIsAllowedWhenOn() {
            when(lobby.config()).thenReturn(everythingExplodes());
            PlayerInteractEvent event = clickOn(Material.RED_BED, world("world_nether", World.Environment.NETHER));

            listener.onInteract(event);

            assertThat(event.useInteractedBlock()).isNotEqualTo(org.bukkit.event.Event.Result.DENY);
        }

        @Test
        @DisplayName("a bed in the Overworld is still a bed — nobody's rule touches it")
        void bedInTheOverworldIsUntouched() {
            PlayerInteractEvent event = clickOn(Material.RED_BED, world("world", World.Environment.NORMAL));

            listener.onInteract(event);

            assertThat(event.useInteractedBlock()).isNotEqualTo(org.bukkit.event.Event.Result.DENY);
        }

        @Test
        @DisplayName("a bed in some other world on the server is not this lobby's business")
        void bedOutsideTheRunsWorldsIsUntouched() {
            PlayerInteractEvent event =
                    clickOn(Material.RED_BED, world("survival_nether", World.Environment.NETHER));

            listener.onInteract(event);

            assertThat(event.useInteractedBlock()).isNotEqualTo(org.bukkit.event.Event.Result.DENY);
        }

        @Test
        @DisplayName("a respawn anchor in the run's End is refused the same way")
        void anchorInTheEndIsRefused() {
            PlayerInteractEvent event =
                    clickOn(Material.RESPAWN_ANCHOR, world("world_the_end", World.Environment.THE_END));

            listener.onInteract(event);

            assertThat(event.useInteractedBlock()).isEqualTo(org.bukkit.event.Event.Result.DENY);
        }

        @Test
        @DisplayName("an anchor in the Nether is a working anchor, never refused")
        void anchorInTheNetherIsUntouched() {
            PlayerInteractEvent event =
                    clickOn(Material.RESPAWN_ANCHOR, world("world_nether", World.Environment.NETHER));

            listener.onInteract(event);

            assertThat(event.useInteractedBlock()).isNotEqualTo(org.bukkit.event.Event.Result.DENY);
        }

        @Test
        @DisplayName("building against a bed while sneaking still places the block")
        void sneakingWithABlockInHandStillBuilds() {
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(Material.RED_BED);
            World nether = world("world_nether", World.Environment.NETHER);
            when(block.getWorld()).thenReturn(nether);
            Player player = playerIn(nether);
            when(player.isSneaking()).thenReturn(true);
            PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                    mock(ItemStack.class), block, org.bukkit.block.BlockFace.UP,
                    EquipmentSlot.HAND);

            listener.onInteract(event);

            assertThat(event.useInteractedBlock()).isNotEqualTo(org.bukkit.event.Event.Result.DENY);
        }

        @Test
        @DisplayName("an ordinary block is never this listener's business")
        void otherBlocksAreIgnored() {
            PlayerInteractEvent event =
                    clickOn(Material.CRAFTING_TABLE, world("world_nether", World.Environment.NETHER));

            listener.onInteract(event);

            assertThat(event.useInteractedBlock()).isNotEqualTo(org.bukkit.event.Event.Result.DENY);
            verify(messages, never()).send(event.getPlayer(), "speedrun.explosives.refused");
        }
    }

    @Nested
    @DisplayName("TNT, minecarts and end crystals — the ones that go off on their own")
    class PrimedEntities {

        private ExplosionPrimeEvent primeOf(org.bukkit.entity.Entity entity, World where) {
            when(entity.getWorld()).thenReturn(where);
            ExplosionPrimeEvent event = mock(ExplosionPrimeEvent.class);
            when(event.getEntity()).thenReturn(entity);
            return event;
        }

        @Test
        @DisplayName("primed TNT in the run's End never goes off")
        void tntIsRefused() {
            ExplosionPrimeEvent event = primeOf(mock(TNTPrimed.class),
                    world("world_the_end", World.Environment.THE_END));

            listener.onPrime(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("a TNT minecart is TNT, on the same switch")
        void minecartsCountAsTnt() {
            ExplosionPrimeEvent event = primeOf(mock(ExplosiveMinecart.class),
                    world("world", World.Environment.NORMAL));

            listener.onPrime(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("an end crystal is its own switch")
        void crystalsAreRefused() {
            ExplosionPrimeEvent event = primeOf(mock(EnderCrystal.class),
                    world("world_the_end", World.Environment.THE_END));

            listener.onPrime(event);

            verify(event).setCancelled(true);
        }

        @Test
        @DisplayName("a creeper is the hazard's business, never this listener's")
        void creepersAreLeftAlone() {
            ExplosionPrimeEvent event = primeOf(mock(Creeper.class),
                    world("world", World.Environment.NORMAL));

            listener.onPrime(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("everything goes off as always once the host leaves the switches on")
        void nothingIsRefusedWhenAllowed() {
            when(lobby.config()).thenReturn(everythingExplodes());
            ExplosionPrimeEvent event = primeOf(mock(TNTPrimed.class),
                    world("world", World.Environment.NORMAL));

            listener.onPrime(event);

            verify(event, never()).setCancelled(true);
        }

        @Test
        @DisplayName("a blast that got past the fuse still takes no blocks with it")
        void theBlastItselfIsRefusedToo() {
            World overworld = world("world", World.Environment.NORMAL);
            TNTPrimed tnt = mock(TNTPrimed.class);
            when(tnt.getWorld()).thenReturn(overworld);
            EntityExplodeEvent event = mock(EntityExplodeEvent.class);
            when(event.getEntity()).thenReturn(tnt);

            listener.onExplode(event);

            verify(event).setCancelled(true);
        }
    }
}
