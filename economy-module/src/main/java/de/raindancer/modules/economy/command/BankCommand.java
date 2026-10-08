package de.raindancer.modules.economy.command;

import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.util.PermissionNodes;
import org.bukkit.command.CommandSender;

import java.util.function.Supplier;

/** {@code /bank} opens the bank; {@code /casino}, {@code /baltop} and {@code /daily} are its doors typed. */
public final class BankCommand extends EconomyCommand {

    public enum Door { BANK, CASINO, BALTOP, DAILY, SLOTS }

    private final Door door;

    public BankCommand(Supplier<EconomyServices> services, Door door) {
        super(services);
        this.door = door;
    }

    @Override
    void run(EconomyServices live, CommandSender sender, String[] args) {
        player(live, sender).ifPresent(player -> {
            switch (door) {
                case BANK -> {
                    if (!allowed(live, sender, PermissionNodes.BALANCE)) {
                        return;
                    }
                    if (args.length > 0 && args[0].equalsIgnoreCase("sidebar")) {
                        live.messages().send(player, live.sidebar().toggle(player)
                                ? "economy.sidebar.on" : "economy.sidebar.off");
                        return;
                    }
                    live.screens().bank(player);
                }
                case CASINO, SLOTS -> {
                    if (!allowed(live, sender, PermissionNodes.GAMBLE)) {
                        return;
                    }
                    if (!live.config().gamblingEnabled()) {
                        live.messages().send(player, "economy.gamble.off");
                        return;
                    }
                    if (door == Door.SLOTS) {
                        live.screens().slots(player);
                    } else {
                        live.screens().casino(player);
                    }
                }
                case BALTOP -> {
                    if (!allowed(live, sender, PermissionNodes.BALTOP)) {
                        return;
                    }
                    if (!live.config().baltopEnabled()) {
                        live.messages().send(player, "economy.baltop.off");
                        return;
                    }
                    live.screens().baltop(player);
                }
                case DAILY -> {
                    if (allowed(live, sender, PermissionNodes.DAILY)) {
                        live.daily().claim(player);
                    }
                }
            }
        });
    }

    @Override
    public String describe() {
        return switch (door) {
            case BANK -> "opening the bank";
            case CASINO -> "opening the casino";
            case SLOTS -> "opening the slot machine";
            case BALTOP -> "the richest players";
            case DAILY -> "claiming the daily reward";
        };
    }
}
