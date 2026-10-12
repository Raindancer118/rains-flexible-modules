package de.raindancer.modules.arena.service;

import de.raindancer.core.world.npc.Attendants;
import de.raindancer.modules.arena.model.NpcRole;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.function.Function;

/**
 * The gladiators: Core's attendants — player-shaped, still, unhurtable, saved with the chunk — in a gladiator's kit:
 * a golden helmet, trimmed iron and gold, a weapon in hand. Which one is which is a mark on the entity, so a placed
 * gladiator needs no file of its own and survives any restart.
 */
public final class NpcService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final NamespacedKey mark;
    private final Function<NpcRole, String> skinOf;

    /** @param skinOf the account whose skin a role wears, or blank for Core's suit under the armour */
    public NpcService(Plugin plugin, Function<NpcRole, String> skinOf) {
        this.mark = new NamespacedKey(plugin, "npc");
        this.skinOf = skinOf;
    }

    public Mannequin place(Player admin, NpcRole role) {
        ResolvableProfile look = Attendants.look(skinOf.apply(role));
        Mannequin gladiator = Attendants.place(admin, MINI.deserialize("<gold><bold>" + role.title()),
                MINI.deserialize("<gray>" + role.purpose()), look,
                entity -> entity.getPersistentDataContainer().set(mark, PersistentDataType.STRING, role.name()));
        dress(gladiator, role);
        return gladiator;
    }

    /** Removes the nearest gladiator within five blocks. @return its role, if there was one */
    public Optional<NpcRole> removeNearest(Player admin) {
        Optional<Entity> nearest = Attendants.nearest(admin, 5, entity -> role(entity).isPresent());
        nearest.ifPresent(Entity::remove);
        return nearest.flatMap(this::role);
    }

    public Optional<NpcRole> role(Entity entity) {
        if (!(entity instanceof Mannequin)) {
            return Optional.empty();
        }
        String role = entity.getPersistentDataContainer().get(mark, PersistentDataType.STRING);
        return role == null ? Optional.empty() : NpcRole.parse(role);
    }

    private static void dress(Mannequin gladiator, NpcRole role) {
        EntityEquipment equipment = gladiator.getEquipment();
        equipment.setHelmet(trimmed(Material.GOLDEN_HELMET, "sentry", "gold"));
        equipment.setChestplate(trimmed(Material.IRON_CHESTPLATE, "dune", "gold"));
        equipment.setLeggings(trimmed(Material.IRON_LEGGINGS, "dune", "copper"));
        equipment.setBoots(trimmed(Material.GOLDEN_BOOTS, "sentry", "iron"));
        equipment.setItemInMainHand(new ItemStack(switch (role) {
            case LANISTA -> Material.IRON_SWORD;
            case BEASTMASTER -> Material.TRIDENT;
            case ARMORER -> Material.IRON_AXE;
            case HERALD -> Material.GOAT_HORN;
            case GATEKEEPER -> Material.SPYGLASS;
        }));
        equipment.setItemInOffHand(new ItemStack(role == NpcRole.HERALD ? Material.WRITABLE_BOOK : Material.SHIELD));
    }

    private static ItemStack trimmed(Material piece, String pattern, String material) {
        ItemStack item = new ItemStack(piece);
        TrimPattern trimPattern = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(pattern));
        TrimMaterial trimMaterial = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(material));
        if (trimPattern != null && trimMaterial != null && item.getItemMeta() instanceof ArmorMeta meta) {
            meta.setTrim(new ArmorTrim(trimMaterial, trimPattern));
            item.setItemMeta(meta);
        }
        return item;
    }
}
