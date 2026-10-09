package de.raindancer.modules.economy.model;

import java.util.List;

/**
 * Where this module's own money comes from and goes, as Core's levers and the money-supply report name it.
 * Kept in one place so every payout and fee says the same word, and so they are never mistaken for message keys.
 */
public final class Sources {

    public static final String REWARD = "economy.reward";
    public static final String INCOME = "economy.income";
    public static final String INTEREST = "economy.interest";
    public static final String DAILY = "economy.daily";
    public static final String XP = "economy.xp";
    public static final String SELL = "economy.sell";
    public static final String PAY_TAX = "economy.pay-tax";
    public static final String WEALTH_TAX = "economy.wealth-tax";
    public static final String AUCTION_JUMP = "economy.auction-jump";
    public static final String FUND = "economy.fund";
    public static final String REPAIR = "economy.repair";
    public static final String DEATH = "economy.death";
    public static final String BET_INSURANCE = "economy.bet-insurance";

    public static final List<String> ALL = List.of(REWARD, INCOME, INTEREST, DAILY, XP, SELL, PAY_TAX, WEALTH_TAX,
            AUCTION_JUMP, FUND, REPAIR, DEATH, BET_INSURANCE);

    private Sources() {
    }
}
