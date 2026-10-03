package de.raindancer.modules.speedrun;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpeedrunMilestoneListenerTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID STRANGER = UUID.nameUUIDFromBytes("stranger".getBytes());

    private SpeedrunSession session;
    private SpeedrunSplitTracker tracker;
    private SpeedrunMilestoneListener listener;

    @BeforeEach
    void setUp() {
        session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        tracker = new SpeedrunSplitTracker(session);
        listener = new SpeedrunMilestoneListener(session, tracker, SpeedrunWorlds.around("speedrun"), () -> 12,
                (java.util.function.ToDoubleFunction<EnderDragon>) dragon -> 200.0);
    }

    private static World world(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        return world;
    }

    private static Player player(UUID id, World in) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getWorld()).thenReturn(in);
        return player;
    }

    private boolean split(SpeedrunMilestone milestone) {
        return session.timeline().splitAt(milestone.id()).isPresent();
    }

    @Test
    @DisplayName("into the run's nether, back out of it, and into its End are three splits")
    void worldChanges() {
        listener.onWorldChange(new PlayerChangedWorldEvent(player(ALICE, world("speedrun_nether")), world("speedrun")));
        listener.onWorldChange(new PlayerChangedWorldEvent(player(ALICE, world("speedrun")), world("speedrun_nether")));
        listener.onWorldChange(new PlayerChangedWorldEvent(player(ALICE, world("speedrun_the_end")), world("speedrun")));

        assertThat(split(SpeedrunMilestones.ENTER_NETHER)).isTrue();
        assertThat(split(SpeedrunMilestones.LEAVE_NETHER)).isTrue();
        assertThat(split(SpeedrunMilestones.ENTER_END)).isTrue();
    }

    @Test
    @DisplayName("the server's own nether, or a non-racer in the run's, splits nothing")
    void notTheRunsOrNotARacer() {
        listener.onWorldChange(new PlayerChangedWorldEvent(player(ALICE, world("world_nether")), world("speedrun")));
        listener.onWorldChange(new PlayerChangedWorldEvent(player(STRANGER, world("speedrun_nether")), world("speedrun")));

        assertThat(session.timeline().entries()).isEmpty();
    }

    @Test
    @DisplayName("the fortress, the bastion and the stronghold come from their advancements")
    void advancements() {
        for (String key : new String[]{"nether/find_fortress", "nether/find_bastion", "story/follow_ender_eye"}) {
            Advancement advancement = mock(Advancement.class);
            when(advancement.getKey()).thenReturn(NamespacedKey.minecraft(key));
            listener.onAdvancement(new PlayerAdvancementDoneEvent(player(ALICE, world("speedrun_nether")), advancement));
        }

        assertThat(split(SpeedrunMilestones.FORTRESS)).isTrue();
        assertThat(split(SpeedrunMilestones.BASTION)).isTrue();
        assertThat(split(SpeedrunMilestones.STRONGHOLD)).isTrue();
    }

    @Test
    @DisplayName("an advancement earned outside the run's worlds splits nothing")
    void advancementElsewhere() {
        Advancement advancement = mock(Advancement.class);
        when(advancement.getKey()).thenReturn(NamespacedKey.minecraft("nether/find_fortress"));

        listener.onAdvancement(new PlayerAdvancementDoneEvent(player(ALICE, world("world_nether")), advancement));

        assertThat(split(SpeedrunMilestones.FORTRESS)).isFalse();
    }

    private EntityPickupItemEvent pickup(Material material, int amount) {
        Item item = mock(Item.class);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(item.getItemStack()).thenReturn(stack);
        return new EntityPickupItemEvent(player(ALICE, world("speedrun_nether")), item, 0);
    }

    @Test
    @DisplayName("a blaze rod picked up splits even when the advancement was earned in an earlier run")
    void blazeRodByPickup() {
        listener.onPickup(pickup(Material.BLAZE_ROD, 1));

        assertThat(split(SpeedrunMilestones.BLAZE_ROD)).isTrue();
    }

    @Test
    @DisplayName("pearls picked up count toward the pearl split")
    void pearls() {
        listener.onPickup(pickup(Material.ENDER_PEARL, 8));
        assertThat(split(SpeedrunMilestones.PEARLS)).isFalse();
        listener.onPickup(pickup(Material.ENDER_PEARL, 4));

        assertThat(split(SpeedrunMilestones.PEARLS)).isTrue();
        assertThat(tracker.pearls()).isEqualTo(12);
    }

    private EnderDragon dragonIn(String world, double health) {
        EnderDragon dragon = mock(EnderDragon.class);
        World end = world(world);
        when(dragon.getWorld()).thenReturn(end);
        when(dragon.getHealth()).thenReturn(health);
        return dragon;
    }

    @Test
    @DisplayName("the run's dragon at half health, then dead, are two splits — the server's own dragon is none")
    void dragon() {
        EnderDragon elsewhere = dragonIn("world_the_end", 10);
        EntityDamageEvent hitElsewhere = mock(EntityDamageEvent.class);
        when(hitElsewhere.getEntity()).thenReturn(elsewhere);
        when(hitElsewhere.getFinalDamage()).thenReturn(50.0);
        listener.onDragonHurt(hitElsewhere);
        assertThat(split(SpeedrunMilestones.DRAGON_HALF)).isFalse();

        EnderDragon dragon = dragonIn("speedrun_the_end", 120);
        EntityDamageEvent graze = mock(EntityDamageEvent.class);
        when(graze.getEntity()).thenReturn(dragon);
        when(graze.getFinalDamage()).thenReturn(10.0);
        listener.onDragonHurt(graze);
        assertThat(split(SpeedrunMilestones.DRAGON_HALF)).isFalse();

        EntityDamageEvent big = mock(EntityDamageEvent.class);
        when(big.getEntity()).thenReturn(dragon);
        when(big.getFinalDamage()).thenReturn(30.0);
        listener.onDragonHurt(big);
        assertThat(split(SpeedrunMilestones.DRAGON_HALF)).isTrue();

        EntityDeathEvent death = mock(EntityDeathEvent.class);
        when(death.getEntity()).thenReturn(dragon);
        listener.onDragonDeath(death);
        assertThat(split(SpeedrunMilestones.DRAGON_KILL)).isTrue();
    }

    @Test
    @DisplayName("a racer's death goes on the timeline with what killed them")
    void deaths() {
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        Player alice = player(ALICE, world("speedrun"));
        when(death.getEntity()).thenReturn(alice);
        when(death.deathMessage()).thenReturn(Component.text("Alice was slain by Zombie"));

        listener.onDeath(death);

        assertThat(session.timeline().of(SpeedrunTimeline.Kind.DEATH)).singleElement().satisfies(entry -> {
            assertThat(entry.who()).isEqualTo(ALICE);
            assertThat(entry.detail()).isEqualTo("Alice was slain by Zombie");
        });
    }

    /**
     * In a hunt only a Runner still running makes news — a Hunter in the Nether, or a caught Runner
     * walking into the End, splits nothing. The game says who counts (ManhuntMode.countsForGoal).
     */
    @Test
    @DisplayName("only whoever the game counts splits the run — every racer in a race, a Runner still running in a hunt")
    void onlyWhoTheGameCounts() {
        UUID hunter = UUID.nameUUIDFromBytes("hunter".getBytes());
        SpeedrunSession hunt = new SpeedrunSession(Set.of(ALICE, hunter));
        hunt.start();
        SpeedrunSplitTracker splits = new SpeedrunSplitTracker(hunt);
        java.util.concurrent.atomic.AtomicBoolean aliceCaught = new java.util.concurrent.atomic.AtomicBoolean();
        SpeedrunMilestoneListener listener = new SpeedrunMilestoneListener(hunt, splits,
                SpeedrunWorlds.around("speedrun"), () -> 12,
                (java.util.function.Predicate<UUID>) id -> ALICE.equals(id) && !aliceCaught.get());

        listener.onWorldChange(new PlayerChangedWorldEvent(player(hunter, world("speedrun_the_end")), world("speedrun")));
        assertThat(hunt.timeline().splitAt("enter-end")).as("a Hunter is no news").isEmpty();
        aliceCaught.set(true);
        listener.onWorldChange(new PlayerChangedWorldEvent(player(ALICE, world("speedrun_the_end")), world("speedrun")));
        assertThat(hunt.timeline().splitAt("enter-end")).as("a caught Runner is no news").isEmpty();
        aliceCaught.set(false);
        listener.onWorldChange(new PlayerChangedWorldEvent(player(ALICE, world("speedrun_nether")), world("speedrun")));
        assertThat(hunt.timeline().splitAt("enter-nether")).isPresent();
    }
}
