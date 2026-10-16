package de.raindancer.modules.ctf.service;

import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.world.spawn.PickupShow;
import de.raindancer.core.world.spawn.Pickups;
import de.raindancer.modules.ctf.CtfSettings;
import de.raindancer.modules.ctf.model.Point;
import de.raindancer.modules.ctf.model.PowerUp;
import de.raindancer.modules.ctf.model.Round;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.function.BiPredicate;

/**
 * Power-ups lying on the map's power-up spots, and supply drops falling onto its drop spots — both Core
 * {@link Pickups}, shown by Core's {@link PickupShow}. A power-up spot gives a random kind each time it comes back.
 */
public final class PowerUpService implements ICtfService {

    public static final String SUPPLY = "supply";

    private final Plugin plugin;
    private final Effects effects;
    private final RoleService roles;
    private final Random random = new Random();
    private final Pickups pickups;
    private final PickupShow show;
    private volatile CtfSettings settings;
    private volatile BiPredicate<Player, String> taken = (player, kind) -> false;

    public PowerUpService(Plugin plugin, Effects effects, RoleService roles, java.util.function.LongSupplier clock,
                          CtfSettings settings) {
        this.plugin = plugin;
        this.effects = effects;
        this.roles = roles;
        this.settings = settings;
        this.pickups = new Pickups(clock);
        this.show = new PickupShow(plugin, pickups, PowerUpService::look, PowerUpService::label,
                (player, pickup) -> taken.test(player, pickup.kind()) && give(player, pickup));
    }

    @Override
    public void settings(CtfSettings settings) {
        this.settings = settings;
    }

    /** Asked first whether somebody may take one — alive and in the round. */
    public void mayTake(BiPredicate<Player, String> who) {
        this.taken = who;
    }

    public void start() {
        show.start();
    }

    public void stop() {
        show.stop();
        pickups.clear();
    }

    /** Puts a round's power-ups on its spots. */
    public void place(Round round) {
        clear();
        if (!settings.powerUps()) {
            return;
        }
        List<Point> spots = round.map().powerUps();
        for (int index = 0; index < spots.size(); index++) {
            Point at = round.abs(spots.get(index));
            pickups.place(new Pickups.Pickup("power-" + index, randomKind(), round.world(), at.x() + 0.5, at.y(),
                    at.z() + 0.5, Duration.ofSeconds(settings.powerUpSeconds())));
        }
    }

    /** A supply drop has landed here. */
    public void landSupply(String world, Point at) {
        pickups.place(new Pickups.Pickup("supply", SUPPLY, world, at.x(), at.y(), at.z(), Duration.ofDays(1)));
    }

    public boolean supplyWaiting() {
        return pickups.isAvailable("supply");
    }

    /** Everything off the map — the end of a round. */
    public void clear() {
        for (Pickups.Pickup pickup : pickups.all()) {
            show.hide(pickup);
        }
        pickups.clear();
    }

    private String randomKind() {
        PowerUp[] kinds = PowerUp.values();
        return kinds[random.nextInt(kinds.length)].name();
    }

    private static ItemStack look(String kind) {
        if (SUPPLY.equals(kind)) {
            return new ItemStack(Material.CHEST);
        }
        return new ItemStack(PowerUp.parse(kind).map(PowerUp::icon).orElse(Material.NETHER_STAR));
    }

    private static net.kyori.adventure.text.Component label(String kind) {
        if (SUPPLY.equals(kind)) {
            return MiniMessage.miniMessage().deserialize("<gold><bold>Supply drop</bold>");
        }
        return PowerUp.parse(kind).map(power -> MiniMessage.miniMessage().deserialize(power.colour() + power.title()))
                .orElse(net.kyori.adventure.text.Component.empty());
    }

    /** What taking one does. Their own thread. */
    private boolean give(Player player, Pickups.Pickup pickup) {
        if (SUPPLY.equals(pickup.kind())) {
            supply(player);
            effects.play(player.getUniqueId(), Cues.REWARD);
            return true;
        }
        PowerUp kind = PowerUp.parse(pickup.kind()).orElse(PowerUp.SPEED);
        switch (kind) {
            case SPEED -> player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1));
            case STRENGTH -> player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 0));
            case SHIELD -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 300, 1));
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 160, 0));
            }
            case GRAPPLE -> player.getInventory().addItem(roles.grapplingHook());
            case DOUBLE_JUMP -> {
                player.setAllowFlight(true);
                doubleJumps.accept(player);
            }
            case HEAL -> {
                var max = player.getAttribute(Attribute.MAX_HEALTH);
                double full = max == null ? 20 : max.getValue();
                if (player.getHealth() >= full) {
                    // Nothing to heal: four hearts on top instead, so nobody walks through a pickup that does nothing.
                    player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 400, 1));
                } else {
                    player.setHealth(full);
                }
            }
        }
        // The next one on this spot is something else.
        pickups.place(pickup.as(randomKind()));
        pickups.take(pickup.id());
        effects.play(player.getUniqueId(), Cues.MAGIC);
        return true;
    }

    private volatile java.util.function.Consumer<Player> doubleJumps = player -> { };

    /** Told when somebody picks up double jumps, to count them on the fighter. */
    public void onDoubleJump(java.util.function.Consumer<Player> counter) {
        this.doubleJumps = counter;
    }

    private void supply(Player player) {
        List<ItemStack> loot = List.of(new ItemStack(Material.GOLDEN_APPLE, 2), new ItemStack(Material.ENDER_PEARL, 2),
                new ItemStack(Material.ARROW, 16), new ItemStack(Material.COOKED_BEEF, 6),
                new ItemStack(Material.WIND_CHARGE, 3), roles.grapplingHook());
        for (int pick = 0; pick < 3; pick++) {
            player.getInventory().addItem(loot.get(random.nextInt(loot.size())).clone());
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
    }

    public Plugin plugin() {
        return plugin;
    }
}
