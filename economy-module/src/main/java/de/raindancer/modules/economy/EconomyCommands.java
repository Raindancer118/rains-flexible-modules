package de.raindancer.modules.economy;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.economy.command.BalanceCommand;
import de.raindancer.modules.economy.command.BankCommand;
import de.raindancer.modules.economy.command.BillCommand;
import de.raindancer.modules.economy.command.CashCommand;
import de.raindancer.modules.economy.command.EcoCommand;
import de.raindancer.modules.economy.command.GambleCommand;
import de.raindancer.modules.economy.command.PayCommand;
import de.raindancer.modules.economy.command.ShopCommand;

import java.util.List;

/**
 * What this module declares at bootstrap. Paper registers commands before any module runs, so each holds
 * a supplier of the services and the state to design for is "registered, module not running".
 */
public final class EconomyCommands {

    private static volatile EconomyServices services;

    private EconomyCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("balance", "Your balance, or somebody else's",
                        new BalanceCommand(EconomyCommands::require)).aliased("bal", "money").taking("[player]"),
                ModuleCommand.of("pay", "Pay another player", new PayCommand(EconomyCommands::require))
                        .taking("<player> <amount>"),
                ModuleCommand.of("bill", "Ask another player to pay you", new BillCommand(EconomyCommands::require))
                        .aliased("request").taking("<player> <amount> [what for]"),
                ModuleCommand.of("hire", "Hire somebody for a wage paid at an interval",
                        new de.raindancer.modules.economy.command.HireCommand(EconomyCommands::require))
                        .aliased("jobs").taking("<player> <wage> <every> [job]", "list", "fire|quit <player>"),
                ModuleCommand.of("contract", "Pay somebody regularly for a service, like rent — either side proposes it",
                        new de.raindancer.modules.economy.command.ContractCommand(EconomyCommands::require))
                        .aliased("contracts", "rent")
                        .taking("pay <player> <amount> <every> <what> — you pay them",
                                "charge <player> <amount> <every> <what> — they pay you, e.g. rent",
                                "list", "end <player>"),
                ModuleCommand.of("loan", "Borrow from the bank, and pay it back",
                        new de.raindancer.modules.economy.command.LoanCommand(EconomyCommands::require))
                        .aliased("loans").taking("[take <amount>]", "repay [amount|all]"),
                ModuleCommand.of("bank", "Open the bank",
                        new BankCommand(EconomyCommands::require, BankCommand.Door.BANK)).aliased("economy")
                        .taking("[sidebar]"),
                ModuleCommand.of("withdraw", "Take money out as coins, notes or a cheque",
                        new CashCommand(EconomyCommands::require, true)).taking("[amount] [cheque]"),
                ModuleCommand.of("deposit", "Pay cash back in",
                        new CashCommand(EconomyCommands::require, false)).taking("[hand|all]"),
                ModuleCommand.of("shop", "Buy from the server's shop",
                        new ShopCommand(EconomyCommands::require, false)).aliased("market")
                        .taking("[category|item|search <text>]"),
                ModuleCommand.of("sell", "Sell to the server's shop",
                        new ShopCommand(EconomyCommands::require, true)).taking("[hand|all]"),
                ModuleCommand.of("baltop", "The richest players",
                        new BankCommand(EconomyCommands::require, BankCommand.Door.BALTOP)).aliased("richest"),
                ModuleCommand.of("daily", "Claim the daily reward",
                        new BankCommand(EconomyCommands::require, BankCommand.Door.DAILY)),
                ModuleCommand.of("claimadvancements", "Once: pay for advancements made before they paid",
                        new BankCommand(EconomyCommands::require, BankCommand.Door.BACKPAY)).aliased("backpay"),
                ModuleCommand.of("casino", "Games of chance",
                        new BankCommand(EconomyCommands::require, BankCommand.Door.CASINO)).taking("[insure]"),
                ModuleCommand.of("slots", "The slot machine",
                        new BankCommand(EconomyCommands::require, BankCommand.Door.SLOTS)),
                ModuleCommand.of("coinflip", "Heads or tails, against the house or a player",
                        new GambleCommand(EconomyCommands::require, GambleCommand.Game.COINFLIP)).aliased("cf")
                        .taking("<amount> [heads|tails]", "<player> <amount>"),
                ModuleCommand.of("dice", "Roll over or under a number",
                        new GambleCommand(EconomyCommands::require, GambleCommand.Game.DICE))
                        .taking("<amount> <over|under> <number>"),
                ModuleCommand.of("roulette", "Red, black, green and numbers",
                        new GambleCommand(EconomyCommands::require, GambleCommand.Game.ROULETTE)).taking("[bet]"),
                ModuleCommand.of("lottery", "Pick numbers, the pot and the next draw",
                        new GambleCommand(EconomyCommands::require, GambleCommand.Game.LOTTERY)).aliased("lotto")
                        .taking("[buy [tickets] | buy <numbers…> | info]"),
                ModuleCommand.of("blackjack", "Blackjack against the dealer",
                        new GambleCommand(EconomyCommands::require, GambleCommand.Game.BLACKJACK)).aliased("bj"),
                ModuleCommand.of("baccarat", "Baccarat: player, banker or tie",
                        new GambleCommand(EconomyCommands::require, GambleCommand.Game.BACCARAT)),
                ModuleCommand.of("hilo", "Higher or lower than the card showing",
                        new GambleCommand(EconomyCommands::require, GambleCommand.Game.HILO)),
                ModuleCommand.of("mines", "Clear tiles, avoid the mines",
                        new GambleCommand(EconomyCommands::require, GambleCommand.Game.MINES)),
                ModuleCommand.of("crash", "The server's crash round",
                        new GambleCommand(EconomyCommands::require, GambleCommand.Game.CRASH)),
                ModuleCommand.of("race", "The server's horse race",
                        new GambleCommand(EconomyCommands::require, GambleCommand.Game.RACE)).aliased("horserace"),
                ModuleCommand.of("scratch", "Buy scratch cards",
                        new GambleCommand(EconomyCommands::require, GambleCommand.Game.SCRATCH)).taking("[buy <count>]"),
                ModuleCommand.of("auction", "The auction house: sell to the highest bidder, bid, pick up",
                        new de.raindancer.modules.economy.command.AuctionCommand(EconomyCommands::require))
                        .aliased("ah", "auctions")
                        .taking("[sell <start> [buy it now] [length] | bid [amount] | cancel | claim | mute | info]"),
                ModuleCommand.of("raffle", "Raffles: raffle an item off, buy tickets",
                        new de.raindancer.modules.economy.command.RaffleCommand(EconomyCommands::require))
                        .aliased("raffles")
                        .taking("[start <ticket price> [length] [most tickets] [per player] | buy <number> [tickets] "
                                + "| cancel <number> | info]"),
                ModuleCommand.of("giveaway", "Giveaways: give something away, or join one",
                        new de.raindancer.modules.economy.command.GiveawayCommand(EconomyCommands::require))
                        .aliased("giveaways")
                        .taking("[start [length] | money <amount> [length] | join <number> | cancel <number>]"),
                ModuleCommand.of("treasury", "How much money there is, and what the treasury holds",
                        new de.raindancer.modules.economy.command.TreasuryCommand(EconomyCommands::require)),
                ModuleCommand.of("fund", "Community funds: give toward a goal everybody shares",
                        new de.raindancer.modules.economy.command.FundCommand(EconomyCommands::require))
                        .taking("[name] [amount]"),
                ModuleCommand.of("repair", "Repair the item in your hand for money",
                        new de.raindancer.modules.economy.command.RepairCommand(EconomyCommands::require)),
                ModuleCommand.of("season", "Your season points",
                        new de.raindancer.modules.economy.command.SeasonCommand(EconomyCommands::require)),
                ModuleCommand.of("eco", "Staff: run the economy", new EcoCommand(EconomyCommands::require))
                        .taking("give|take|set <player> <amount> [reason]", "reset|freeze|unfreeze|history <player>",
                                "menu|reprice|draw|calm|coin", "leaderboard place|remove", "dealer place <game>|remove",
                                "auction stop|clear", "raffle <prize> <ticket price> [length] | cancel <number>",
                                "giveaway <prize> [length]", "tax <percent> [confirm]", "packs [give <player> <pack>]")
                        .auditUsage());
    }

    static void ready(EconomyServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    private static EconomyServices require() {
        EconomyServices live = services;
        if (live == null) {
            throw new IllegalStateException("the economy module is not running");
        }
        return live;
    }
}
