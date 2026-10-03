package de.raindancer.modules.manhunt.stats;

import de.raindancer.modules.manhunt.model.Hunt;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("the moments of a hunt, read off the game")
class HuntRecorderTest {

    private static final UUID RUNNER = UUID.nameUUIDFromBytes("runner".getBytes());
    private static final UUID HUNTER = UUID.nameUUIDFromBytes("hunter".getBytes());

    private final Hunt hunt = Hunt.of(Set.of(RUNNER, HUNTER), Set.of(RUNNER));
    private final List<String> reached = new ArrayList<>();
    private final List<UUID> portals = new ArrayList<>();
    private HuntRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new HuntRecorder(hunt, world -> world.startsWith("speedrun"),
                (milestone, who) -> reached.add(milestone + ":" + (who == null ? "-" : who.getUniqueId())),
                portals::add, entity -> 200.0);
    }

    private static Player player(UUID id, World.Environment in) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        World world = mock(World.class);
        when(world.getEnvironment()).thenReturn(in);
        when(player.getWorld()).thenReturn(world);
        return player;
    }

    @Test
    @DisplayName("a Runner arriving in the Nether or the End; a Hunter or a caught Runner does not count")
    void dimensions() {
        recorder.onWorld(new PlayerChangedWorldEvent(player(RUNNER, World.Environment.NETHER), mock(World.class)));
        recorder.onWorld(new PlayerChangedWorldEvent(player(HUNTER, World.Environment.THE_END), mock(World.class)));
        recorder.onWorld(new PlayerChangedWorldEvent(player(RUNNER, World.Environment.THE_END), mock(World.class)));
        hunt.eliminate(RUNNER);
        recorder.onWorld(new PlayerChangedWorldEvent(player(RUNNER, World.Environment.NETHER), mock(World.class)));

        assertThat(reached).containsExactly("NETHER:" + RUNNER, "END:" + RUNNER);
    }

    @Test
    @DisplayName("a Runner picking up a blaze rod; anything else is not news")
    void blazeRod() {
        recorder.onPickup(pickup(Material.BLAZE_ROD));
        recorder.onPickup(pickup(Material.STICK));

        assertThat(reached).containsExactly("BLAZE_ROD:" + RUNNER);
    }

    private EntityPickupItemEvent pickup(Material material) {
        Item item = mock(Item.class);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(item.getItemStack()).thenReturn(stack);
        return new EntityPickupItemEvent(player(RUNNER, World.Environment.NORMAL), item, 0);
    }

    @Test
    @DisplayName("the fortress and the stronghold by their advancement")
    void advancements() {
        recorder.onAdvancement(advancement("nether/find_fortress"));
        recorder.onAdvancement(advancement("story/follow_ender_eye"));
        recorder.onAdvancement(advancement("story/mine_stone"));

        assertThat(reached).containsExactly("FORTRESS:" + RUNNER, "STRONGHOLD:" + RUNNER);
    }

    private PlayerAdvancementDoneEvent advancement(String key) {
        Advancement advancement = mock(Advancement.class);
        when(advancement.getKey()).thenReturn(NamespacedKey.minecraft(key));
        return new PlayerAdvancementDoneEvent(player(RUNNER, World.Environment.NORMAL), advancement);
    }

    @Test
    @DisplayName("the dragon at half its health, once, by whoever hit it — in this run's End only")
    void dragonHalf() {
        Player hitter = player(RUNNER, World.Environment.THE_END);

        recorder.onDragonHurt(hurt("speedrun_the_end", 120, 10, hitter));
        recorder.onDragonHurt(hurt("speedrun_the_end", 104, 10, hitter));
        recorder.onDragonHurt(hurt("other_end", 104, 10, hitter));

        assertThat(reached).containsExactly("DRAGON_HALF:" + RUNNER);
    }

    private EntityDamageEvent hurt(String worldName, double health, double damage, Player by) {
        EnderDragon dragon = mock(EnderDragon.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn(worldName);
        when(dragon.getWorld()).thenReturn(world);
        when(dragon.getHealth()).thenReturn(health);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(dragon);
        when(event.getDamager()).thenReturn(by);
        when(event.getFinalDamage()).thenReturn(damage);
        return event;
    }

    @Test
    @DisplayName("the dragon's death, with its killer")
    void dragonKilled() {
        EnderDragon dragon = mock(EnderDragon.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("speedrun_the_end");
        when(dragon.getWorld()).thenReturn(world);
        Player killer = player(RUNNER, World.Environment.THE_END);
        when(dragon.getKiller()).thenReturn(killer);
        EntityDeathEvent death = mock(EntityDeathEvent.class);
        when(death.getEntity()).thenReturn(dragon);

        recorder.onDragonDeath(death);

        assertThat(reached).containsExactly("DRAGON_KILLED:" + RUNNER);
    }

    @Test
    @DisplayName("every portal somebody in the hunt takes is counted")
    void portalsCounted() {
        PlayerPortalEvent portal = mock(PlayerPortalEvent.class);
        Player hunter = player(HUNTER, World.Environment.NORMAL);
        when(portal.getPlayer()).thenReturn(hunter);
        PlayerPortalEvent stranger = mock(PlayerPortalEvent.class);
        Player outsider = player(UUID.randomUUID(), World.Environment.NORMAL);
        when(stranger.getPlayer()).thenReturn(outsider);

        recorder.onPortal(portal);
        recorder.onPortal(stranger);

        assertThat(portals).containsExactly(HUNTER);
    }
}
