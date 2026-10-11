package de.raindancer.modules.economy.service;

import de.raindancer.core.world.npc.Attendants;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.DealerGame;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
 * Dealers standing in the casino (Core's {@link Attendants}), dressed for the job. Right click one and its
 * table opens. A dealer is an ordinary saved entity tagged with its game, so it stays where it was put across
 * restarts without a file of its own.
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
        return Attendants.place(player, Component.text("Dealer · " + game.title(), NamedTextColor.GOLD),
                Component.text("Right click to play", NamedTextColor.GRAY), Attendants.look(settings.dealerSkin()),
                dealer -> dealer.getPersistentDataContainer().set(GAME, PersistentDataType.STRING, game.name()));
    }

    /**
     * The suit, or the skin of the player the owner named. Also for dealers placed before: they wore a plain
     * leather chestplate over the default look, which would hide the suit, so that comes off.
     */
    public void dress(Mannequin dealer) {
        dealer.setProfile(Attendants.look(settings.dealerSkin()));
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

    /** Whether a dealer of this game stands within reach of the player. */
    public static boolean near(Player player, DealerGame game) {
        return Attendants.near(player, entity -> gameOf(entity).filter(game::equals).isPresent());
    }

    /** Takes away the nearest dealer within a few blocks; whether there was one. */
    public boolean remove(Player player) {
        Optional<Entity> nearest = Attendants.nearest(player, 4, entity -> gameOf(entity).isPresent());
        nearest.ifPresent(Entity::remove);
        return nearest.isPresent();
    }
}
