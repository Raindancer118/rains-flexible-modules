package de.raindancer.modules.economy.util;

import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.util.List;

/**
 * What this module asks about somebody. Registered in code so a module hosted in another plugin still
 * has its nodes — an unregistered node answers "operators only", which would switch the whole economy off
 * for every ordinary player and look exactly like a bug.
 */
public final class PermissionNodes {

    public static final String BALANCE = "rainseconomy.balance";
    public static final String BALANCE_OTHERS = "rainseconomy.balance.others";
    public static final String PAY = "rainseconomy.pay";
    public static final String BILL = "rainseconomy.bill";
    public static final String HIRE = "rainseconomy.hire";
    public static final String LOAN = "rainseconomy.loan";
    public static final String CASH = "rainseconomy.cash";
    public static final String SHOP = "rainseconomy.shop";
    public static final String SELL = "rainseconomy.sell";
    public static final String BALTOP = "rainseconomy.baltop";
    public static final String DAILY = "rainseconomy.daily";
    public static final String EARN = "rainseconomy.earn";
    public static final String GAMBLE = "rainseconomy.gamble";
    public static final String AUCTION = "rainseconomy.auction";
    public static final String HISTORY_OTHERS = "rainseconomy.history.others";
    public static final String ADMIN = "rainseconomy.admin";
    public static final String ALERTS = "rainseconomy.alerts";

    private PermissionNodes() {
    }

    public static List<Permission> declared() {
        return List.of(
                new Permission(BALANCE, "See your own balance and open the bank", PermissionDefault.TRUE),
                new Permission(BALANCE_OTHERS, "See somebody else's balance", PermissionDefault.TRUE),
                new Permission(PAY, "Pay other players", PermissionDefault.TRUE),
                new Permission(BILL, "Ask other players to pay you", PermissionDefault.TRUE),
                new Permission(HIRE, "Hire players for a wage, and be hired", PermissionDefault.TRUE),
                new Permission(LOAN, "Borrow money from the bank", PermissionDefault.TRUE),
                new Permission(CASH, "Withdraw coins, notes and cheques, and pay them in", PermissionDefault.TRUE),
                new Permission(SHOP, "Buy from the shop", PermissionDefault.TRUE),
                new Permission(SELL, "Sell to the shop", PermissionDefault.TRUE),
                new Permission(BALTOP, "See the richest players", PermissionDefault.TRUE),
                new Permission(DAILY, "Claim the daily reward", PermissionDefault.TRUE),
                new Permission(EARN, "Be paid passive income and for advancements", PermissionDefault.TRUE),
                new Permission(GAMBLE, "Play the games of chance", PermissionDefault.TRUE),
                new Permission(AUCTION, "Put items up for auction and bid on them; raffle items off and buy tickets",
                        PermissionDefault.TRUE),
                new Permission(HISTORY_OTHERS, "Read somebody else's statement", PermissionDefault.OP),
                new Permission(ADMIN, "Give, take, set and freeze money; change the economy", PermissionDefault.OP),
                new Permission(ALERTS, "Be told about counterfeit notes", PermissionDefault.OP));
    }

    public static int register(Server server) {
        if (server == null) {
            return 0;
        }
        int added = 0;
        for (Permission permission : declared()) {
            if (server.getPluginManager().getPermission(permission.getName()) != null) {
                continue;
            }
            try {
                server.getPluginManager().addPermission(permission);
                added++;
            } catch (IllegalArgumentException alreadyThere) {
                // Registered by another copy in between.
            }
        }
        return added;
    }
}
