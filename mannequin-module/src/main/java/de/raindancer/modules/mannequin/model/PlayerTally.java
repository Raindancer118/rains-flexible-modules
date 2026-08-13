package de.raindancer.modules.mannequin.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One player's whole record against one mannequin — every weapon they have hit it with, and what
 * each one did.
 */
public record PlayerTally(UUID player, Map<Material, WeaponTally> byWeapon) {

    public PlayerTally {
        if (player == null) {
            throw new IllegalArgumentException("a player tally needs a player");
        }
        byWeapon = byWeapon == null ? Map.of() : Map.copyOf(byWeapon);
    }

    public static PlayerTally empty(UUID player) {
        return new PlayerTally(player, Map.of());
    }

    public PlayerTally withHit(Material weapon, ItemStack sample, double damage) {
        Map<Material, WeaponTally> next = new LinkedHashMap<>(byWeapon);
        WeaponTally existing = next.get(weapon);
        next.put(weapon, existing == null
                ? WeaponTally.firstHit(weapon, sample, damage)
                : existing.hit(sample, damage));
        return new PlayerTally(player, next);
    }

    public double totalDamage() {
        return byWeapon.values().stream().mapToDouble(WeaponTally::totalDamage).sum();
    }

    public long totalHits() {
        return byWeapon.values().stream().mapToLong(WeaponTally::hits).sum();
    }

    public int weaponCount() {
        return byWeapon.size();
    }

    public List<WeaponTally> rankedByTotalDamage() {
        return byWeapon.values().stream()
                .sorted(Comparator.comparingDouble(WeaponTally::totalDamage).reversed())
                .toList();
    }
}
