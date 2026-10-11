package de.raindancer.modules.economy;

import de.raindancer.core.social.desk.DeskOffer;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.modules.economy.util.PermissionNodes;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.function.Supplier;

/** Bet insurance at an insurance agent: the same switch as in the casino. */
public final class BetInsuranceOffer implements DeskOffer {

    private final Supplier<EconomyServices> services;

    public BetInsuranceOffer(Supplier<EconomyServices> services) {
        this.services = services;
    }

    @Override
    public boolean available() {
        EconomyServices live = services.get();
        return live != null && live.config().gamblingEnabled() && live.gambling().insuranceOffered();
    }

    @Override
    public ItemStack icon(Player viewer) {
        return viewer.hasPermission(PermissionNodes.GAMBLE) ? button(services.get(), viewer) : null;
    }

    /** Its button, here and in the casino. */
    public static ItemStack button(EconomyServices live, Player viewer) {
        boolean insured = live.gambling().insures(viewer.getUniqueId());
        var supplied = live.supply().current();
        return Icons.of(insured ? Material.TOTEM_OF_UNDYING : Material.ARMOR_STAND,
                "<white>Insure my bets: " + (insured ? "<green>on" : "<gray>off"),
                "<gray>Every stake costs " + supplied.gambleInsurancePremium() + "% more;",
                "<gray>a lost stake pays " + supplied.gambleInsurancePayback() + "% of it back.",
                "<yellow>Click<gray> to switch it " + (insured ? "off" : "on"));
    }

    @Override
    public void use(Player viewer, Runnable backToDesk) {
        if (!viewer.hasPermission(PermissionNodes.GAMBLE)) {
            return;
        }
        services.get().gambling().toggleInsurance(viewer);
        backToDesk.run();
    }
}
