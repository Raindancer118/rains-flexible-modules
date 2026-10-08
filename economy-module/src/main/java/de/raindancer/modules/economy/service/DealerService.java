package de.raindancer.modules.economy.service;

import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.DealerGame;
import com.destroystokyo.paper.profile.ProfileProperty;
import de.raindancer.modules.economy.model.DealerSuit;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.Optional;

/**
 * Dealers standing in the casino: player-shaped, still, unhurtable, and dressed for the job. Right click one
 * and its table opens. A dealer is an ordinary saved entity tagged with its game, so it stays where it was
 * put across restarts without a file of its own.
 */
public final class DealerService implements IEconomyService {

    public static final NamespacedKey GAME = Objects.requireNonNull(NamespacedKey.fromString("rainseconomy:dealer"));

    private volatile EconomySettings settings;

    public DealerService(EconomySettings settings) {
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    /** A dealer where the player stands, facing them. On the player's own region thread. */
    public Mannequin place(Player player, DealerGame game) {
        Location at = player.getLocation();
        Location facing = at.clone();
        facing.setYaw(at.getYaw() + 180);
        facing.setPitch(0);
        return at.getWorld().spawn(facing, Mannequin.class, dealer -> {
            dealer.setImmovable(true);
            dealer.setInvulnerable(true);
            dealer.setSilent(true);
            dealer.setPersistent(true);
            dealer.setRemoveWhenFarAway(false);
            dealer.customName(Component.text("Dealer · " + game.title(), NamedTextColor.GOLD));
            dealer.setCustomNameVisible(true);
            dealer.setDescription(Component.text("Right click to play", NamedTextColor.GRAY));
            dealer.getPersistentDataContainer().set(GAME, PersistentDataType.STRING, game.name());
            dress(dealer);
        });
    }

    /**
     * The suit, or the skin of the player the owner named. Also for dealers placed before: they wore a plain
     * leather chestplate over the default look, which would hide the suit, so that comes off.
     */
    public void dress(Mannequin dealer) {
        String skin = settings.dealerSkin();
        ResolvableProfile.Builder profile = ResolvableProfile.resolvableProfile();
        if (skin != null && !skin.isBlank()) {
            profile.name(skin.strip());
        } else {
            profile.name("Dealer").addProperty(new ProfileProperty("textures", DealerSuit.VALUE, DealerSuit.SIGNATURE));
        }
        dealer.setProfile(profile.build());
        ItemStack chest = dealer.getEquipment().getChestplate();
        if (chest != null && chest.getType() == Material.LEATHER_CHESTPLATE && !chest.hasItemMeta()) {
            dealer.getEquipment().setChestplate(null);
        }
    }

    public static Optional<DealerGame> gameOf(Entity entity) {
        if (!(entity instanceof Mannequin)) {
            return Optional.empty();
        }
        return DealerGame.read(entity.getPersistentDataContainer().get(GAME, PersistentDataType.STRING));
    }

    /** Takes away the nearest dealer within a few blocks; whether there was one. */
    public boolean remove(Player player) {
        Entity nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity nearby : player.getNearbyEntities(4, 4, 4)) {
            if (gameOf(nearby).isPresent()) {
                double distance = nearby.getLocation().distanceSquared(player.getLocation());
                if (distance < best) {
                    best = distance;
                    nearest = nearby;
                }
            }
        }
        if (nearest == null) {
            return false;
        }
        nearest.remove();
        return true;
    }
}
