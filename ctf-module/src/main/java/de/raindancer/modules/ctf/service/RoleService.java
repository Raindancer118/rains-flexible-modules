package de.raindancer.modules.ctf.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.modules.ctf.CtfSettings;
import de.raindancer.modules.ctf.model.Building;
import de.raindancer.modules.ctf.model.Mutator;
import de.raindancer.modules.ctf.model.Role;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The classes: what each fights with, and the abilities beside it — the ninja's vanishing, the medic's healing, the
 * engineer's turret and pads, the trapper's webs and alarms. The kits are built here in code; an ability's item says
 * which ability it is, so the listener only asks {@link #abilityOf}.
 */
public final class RoleService implements ICtfService {

    public static final String ABILITY_KEY = "ability";
    public static final String VANISH = "vanish";
    public static final String MEND = "mend";
    public static final String TURRET = "turret";
    public static final String PAD = "pad";
    public static final String BARRICADE = "barricade";
    public static final String WEB = "web";
    public static final String ALARM = "alarm";
    public static final String GRAPPLE = "grapple";

    /** Seconds a ninja stays unseen, and before they can vanish again. */
    public static final int VANISH_SECONDS = 4;
    public static final int VANISH_COOLDOWN = 15;
    /** How near a teammate has to be for the medic's aura, blocks, and how much it heals each turn. */
    public static final double AURA_RADIUS = 6;
    public static final double AURA_HEAL = 1.0;

    private final Plugin plugin;
    private final Effects effects;
    private final LongSupplier clock;
    private final NamespacedKey abilityKey;
    private final Map<UUID, Long> vanishedUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> vanishReady = new ConcurrentHashMap<>();
    private volatile CtfSettings settings;

    public RoleService(Plugin plugin, Effects effects, LongSupplier clock, CtfSettings settings) {
        this.plugin = plugin;
        this.effects = effects;
        this.clock = clock;
        this.settings = settings;
        this.abilityKey = new NamespacedKey(plugin, ABILITY_KEY);
    }

    @Override
    public void settings(CtfSettings settings) {
        this.settings = settings;
    }

    /** Whether a class may be played in a round with this building setting and twist. */
    public boolean playable(Role role, Building building, Mutator mutator) {
        if (!settings.classes()) {
            return role == settings.defaultClass();
        }
        return !role.needsBuilding() || building != Building.NONE || mutator.needsBreaking();
    }

    /** Why a class cannot be played, as a message key; empty when it can. */
    public Optional<String> whyNot(Role role, Building building, Mutator mutator) {
        if (!settings.classes() && role != settings.defaultClass()) {
            return Optional.of("ctf.class.classes-off");
        }
        if (role.needsBuilding() && building == Building.NONE && !mutator.needsBreaking()) {
            return Optional.of("ctf.class.needs-building");
        }
        return Optional.empty();
    }

    /**
     * Dresses and arms somebody for their class, their side's colour on their head. Clears what they held. Their own
     * thread.
     */
    public void equip(Player player, Role role, TeamColour colour, Mutator mutator) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        player.getActivePotionEffects().stream().map(PotionEffect::getType).forEach(player::removePotionEffect);
        inventory.setItem(EquipmentSlot.HEAD, leather(Material.LEATHER_HELMET, colour));
        switch (role) {
            case SOLDIER -> {
                inventory.setItem(0, named(Material.IRON_SWORD, "<white>Sword"));
                inventory.setItem(1, named(Material.BOW, "<white>Bow"));
                inventory.setItem(9, new ItemStack(Material.ARROW, 16));
                inventory.setItem(EquipmentSlot.CHEST, new ItemStack(Material.IRON_CHESTPLATE));
                inventory.setItem(EquipmentSlot.LEGS, new ItemStack(Material.IRON_LEGGINGS));
                inventory.setItem(EquipmentSlot.FEET, new ItemStack(Material.IRON_BOOTS));
                inventory.setItem(2, new ItemStack(Material.GOLDEN_CARROT, 8));
            }
            case SCOUT -> {
                inventory.setItem(0, named(Material.STONE_SWORD, "<white>Knife"));
                inventory.setItem(1, new ItemStack(Material.WIND_CHARGE, 4));
                inventory.setItem(EquipmentSlot.CHEST, leather(Material.LEATHER_CHESTPLATE, colour));
                inventory.setItem(EquipmentSlot.LEGS, leather(Material.LEATHER_LEGGINGS, colour));
                inventory.setItem(EquipmentSlot.FEET, enchanted(new ItemStack(Material.LEATHER_BOOTS),
                        Enchantment.FEATHER_FALLING, 3));
                inventory.setItem(2, new ItemStack(Material.GOLDEN_CARROT, 8));
                endless(player, PotionEffectType.SPEED, 1);
            }
            case TANK -> {
                inventory.setItem(0, named(Material.IRON_AXE, "<white>Axe"));
                inventory.setItem(EquipmentSlot.OFF_HAND, new ItemStack(Material.SHIELD));
                inventory.setItem(EquipmentSlot.CHEST, new ItemStack(Material.DIAMOND_CHESTPLATE));
                inventory.setItem(EquipmentSlot.LEGS, new ItemStack(Material.DIAMOND_LEGGINGS));
                inventory.setItem(EquipmentSlot.FEET, new ItemStack(Material.IRON_BOOTS));
                inventory.setItem(2, new ItemStack(Material.COOKED_BEEF, 8));
                endless(player, PotionEffectType.SLOWNESS, 0);
            }
            case ARCHER -> {
                inventory.setItem(0, named(Material.WOODEN_SWORD, "<white>Dagger"));
                ItemStack bow = enchanted(named(Material.BOW, "<gold>Longbow"), Enchantment.POWER, 2);
                bow.addUnsafeEnchantment(Enchantment.FLAME, 1);
                inventory.setItem(1, bow);
                inventory.setItem(9, new ItemStack(Material.ARROW, 48));
                inventory.setItem(EquipmentSlot.CHEST, new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                inventory.setItem(EquipmentSlot.LEGS, leather(Material.LEATHER_LEGGINGS, colour));
                inventory.setItem(EquipmentSlot.FEET, new ItemStack(Material.CHAINMAIL_BOOTS));
                inventory.setItem(2, new ItemStack(Material.GOLDEN_CARROT, 8));
            }
            case MEDIC -> {
                inventory.setItem(0, named(Material.IRON_SWORD, "<white>Sword"));
                inventory.setItem(1, ability(Material.GOLDEN_APPLE, "<light_purple>Mend <gray>(right click a teammate)",
                        MEND));
                inventory.setItem(2, new ItemStack(Material.GOLDEN_APPLE, 2));
                inventory.setItem(EquipmentSlot.CHEST, new ItemStack(Material.IRON_CHESTPLATE));
                inventory.setItem(EquipmentSlot.LEGS, leather(Material.LEATHER_LEGGINGS, colour));
                inventory.setItem(EquipmentSlot.FEET, new ItemStack(Material.IRON_BOOTS));
            }
            case ENGINEER -> {
                inventory.setItem(0, named(Material.STONE_SWORD, "<white>Wrench"));
                inventory.setItem(1, ability(Material.OAK_PLANKS, "<gold>Barricade", BARRICADE, 32));
                inventory.setItem(2, ability(Material.SLIME_BLOCK, "<green>Jump pad <gray>(place it)", PAD, 3));
                inventory.setItem(3, ability(Material.DISPENSER, "<red>Turret <gray>(place it)", TURRET, 1));
                inventory.setItem(4, new ItemStack(Material.IRON_PICKAXE));
                inventory.setItem(EquipmentSlot.CHEST, new ItemStack(Material.IRON_CHESTPLATE));
                inventory.setItem(EquipmentSlot.LEGS, new ItemStack(Material.IRON_LEGGINGS));
                inventory.setItem(EquipmentSlot.FEET, leather(Material.LEATHER_BOOTS, colour));
                inventory.setItem(5, new ItemStack(Material.GOLDEN_CARROT, 8));
            }
            case NINJA -> {
                inventory.setItem(0, enchanted(named(Material.IRON_SWORD, "<dark_gray>Katana"), Enchantment.SHARPNESS, 1));
                inventory.setItem(1, ability(Material.ENDER_EYE, "<dark_purple>Vanish <gray>(sneak, or right click)",
                        VANISH));
                inventory.setItem(2, new ItemStack(Material.ENDER_PEARL, 1));
                inventory.setItem(EquipmentSlot.CHEST, leather(Material.LEATHER_CHESTPLATE, Color.fromRGB(0x202020)));
                inventory.setItem(EquipmentSlot.LEGS, leather(Material.LEATHER_LEGGINGS, Color.fromRGB(0x202020)));
                inventory.setItem(EquipmentSlot.FEET, leather(Material.LEATHER_BOOTS, Color.fromRGB(0x202020)));
                inventory.setItem(3, new ItemStack(Material.GOLDEN_CARROT, 8));
            }
            case TRAPPER -> {
                inventory.setItem(0, named(Material.STONE_SWORD, "<white>Machete"));
                inventory.setItem(1, ability(Material.COBWEB, "<white>Web trap <gray>(right click a block)", WEB, 4));
                inventory.setItem(2, ability(Material.TRIPWIRE_HOOK, "<yellow>Alarm <gray>(right click a block)",
                        ALARM, 2));
                inventory.setItem(3, named(Material.CROSSBOW, "<white>Crossbow"));
                inventory.setItem(9, new ItemStack(Material.ARROW, 12));
                inventory.setItem(EquipmentSlot.CHEST, new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                inventory.setItem(EquipmentSlot.LEGS, new ItemStack(Material.CHAINMAIL_LEGGINGS));
                inventory.setItem(EquipmentSlot.FEET, leather(Material.LEATHER_BOOTS, colour));
                inventory.setItem(4, new ItemStack(Material.GOLDEN_CARROT, 8));
            }
        }
        if (mutator == Mutator.ELYTRA) {
            inventory.setItem(EquipmentSlot.CHEST, new ItemStack(Material.ELYTRA));
            inventory.setItem(7, new ItemStack(Material.FIREWORK_ROCKET, 16));
        }
        if (mutator == Mutator.SPLEEF) {
            inventory.setItem(6, enchanted(named(Material.DIAMOND_SHOVEL, "<aqua>Spleef shovel"),
                    Enchantment.EFFICIENCY, 5));
        }
        if (mutator == Mutator.KNOCKBACK) {
            inventory.setItem(6, enchanted(named(Material.STICK, "<gold>Knockback stick"), Enchantment.KNOCKBACK, 3));
        }
        inventory.setHeldItemSlot(0);
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth == null ? 20 : maxHealth.getValue());
        player.setFoodLevel(20);
        player.setSaturation(10);
        player.setFireTicks(0);
        vanishedUntil.remove(player.getUniqueId());
    }

    private static void endless(Player player, PotionEffectType type, int amplifier) {
        player.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, amplifier, true, false, true));
    }

    private static ItemStack named(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.itemName(MiniMessage.miniMessage().deserialize(name));
            meta.setUnbreakable(true);
        });
        return stack;
    }

    private static ItemStack enchanted(ItemStack stack, Enchantment enchantment, int level) {
        stack.addUnsafeEnchantment(enchantment, level);
        stack.editMeta(meta -> meta.setUnbreakable(true));
        return stack;
    }

    private static ItemStack leather(Material material, TeamColour colour) {
        return leather(material, colour.armourColour());
    }

    private static ItemStack leather(Material material, Color colour) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(LeatherArmorMeta.class, meta -> {
            meta.setColor(colour);
            meta.setUnbreakable(true);
        });
        return stack;
    }

    private ItemStack ability(Material material, String name, String ability) {
        return ability(material, name, ability, 1);
    }

    private ItemStack ability(Material material, String name, String ability, int amount) {
        ItemStack stack = new ItemStack(material, amount);
        stack.editMeta(meta -> {
            meta.itemName(MiniMessage.miniMessage().deserialize(name));
            meta.getPersistentDataContainer().set(abilityKey, PersistentDataType.STRING, ability);
        });
        return stack;
    }

    public ItemStack grapplingHook() {
        ItemStack hook = ability(Material.FISHING_ROD, "<gold>Grappling hook <gray>(cast, then reel in)", GRAPPLE);
        hook.editMeta(meta -> meta.setUnbreakable(true));
        return hook;
    }

    public Optional<String> abilityOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        return Optional.ofNullable(stack.getItemMeta().getPersistentDataContainer().get(abilityKey,
                PersistentDataType.STRING));
    }

    // ------------------------------------------------------------------ ninja

    /** @return the seconds until they may, or 0 if they vanished now */
    public long vanish(Player ninja) {
        long now = clock.getAsLong();
        long ready = vanishReady.getOrDefault(ninja.getUniqueId(), 0L);
        if (now < ready) {
            return (ready - now + 999) / 1000;
        }
        vanishedUntil.put(ninja.getUniqueId(), now + VANISH_SECONDS * 1000L);
        vanishReady.put(ninja.getUniqueId(), now + VANISH_COOLDOWN * 1000L);
        Scheduling.entity(plugin, ninja, () -> {
            ninja.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, VANISH_SECONDS * 20, 0, false,
                    false, true));
            ninja.getInventory().setItem(EquipmentSlot.HEAD, null);
        });
        effects.play(ninja.getUniqueId(), Cues.VANISH);
        return 0;
    }

    public boolean isVanished(UUID player) {
        Long until = vanishedUntil.get(player);
        return until != null && until > clock.getAsLong();
    }

    /** Seen again: they hit somebody, took a flag, or the time ran out. Their own thread. */
    public void reveal(Player ninja, TeamColour colour) {
        if (vanishedUntil.remove(ninja.getUniqueId()) == null) {
            return;
        }
        ninja.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (ninja.getInventory().getItem(EquipmentSlot.HEAD).getType().isAir()) {
            ninja.getInventory().setItem(EquipmentSlot.HEAD, leather(Material.LEATHER_HELMET, colour));
        }
    }

    /** Ninjas whose time is up; asked once a second. */
    public java.util.List<UUID> vanishOver() {
        long now = clock.getAsLong();
        return vanishedUntil.entrySet().stream().filter(entry -> entry.getValue() <= now).map(Map.Entry::getKey).toList();
    }

    public void forget(UUID player) {
        vanishedUntil.remove(player);
        vanishReady.remove(player);
    }

    public void clear() {
        vanishedUntil.clear();
        vanishReady.clear();
    }
}
