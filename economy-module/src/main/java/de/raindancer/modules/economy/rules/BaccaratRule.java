package de.raindancer.modules.economy.rules;

import de.raindancer.modules.economy.model.Card;

import java.util.List;

/**
 * Punto banco, with the real third-card rules. Player pays even money, banker even money less five percent,
 * a tie eight to one; on a tie, player and banker bets are returned.
 */
public final class BaccaratRule implements IEconomyRule {

    public enum Side { PLAYER, BANKER, TIE }

    /** Faces and tens count nothing, aces one; a hand is worth its sum's last digit. */
    public int points(List<Card> hand) {
        int sum = 0;
        for (Card card : hand) {
            sum += card.rank() >= 10 ? 0 : card.rank();
        }
        return sum % 10;
    }

    public boolean natural(List<Card> hand) {
        return hand.size() == 2 && points(hand) >= 8;
    }

    /** The player draws on five or less. */
    public boolean playerDraws(List<Card> player) {
        return points(player) <= 5;
    }

    /**
     * Whether the banker draws.
     *
     * @param playerThird the player's third card, or null when the player stood
     */
    public boolean bankerDraws(List<Card> banker, Card playerThird) {
        int total = points(banker);
        if (playerThird == null) {
            return total <= 5;
        }
        int third = playerThird.rank() >= 10 ? 0 : playerThird.rank();
        return switch (total) {
            case 0, 1, 2 -> true;
            case 3 -> third != 8;
            case 4 -> third >= 2 && third <= 7;
            case 5 -> third >= 4 && third <= 7;
            case 6 -> third == 6 || third == 7;
            default -> false;
        };
    }

    public Side winner(List<Card> player, List<Card> banker) {
        int p = points(player);
        int b = points(banker);
        return p > b ? Side.PLAYER : b > p ? Side.BANKER : Side.TIE;
    }

    /** What a bet returns per unit staked, stake included. */
    public double returns(Side bet, Side winner) {
        return returns(bet, winner, 8, 0.05);
    }

    /**
     * @param tiePays          a tie bet's winnings to one: 8 at the usual table
     * @param bankerCommission taken from a banker win's winnings, as a fraction: 0.05 at the usual table
     */
    public double returns(Side bet, Side winner, int tiePays, double bankerCommission) {
        if (winner == Side.TIE) {
            return bet == Side.TIE ? 1 + tiePays : 1;
        }
        if (bet != winner) {
            return 0;
        }
        return bet == Side.BANKER ? 2 - Math.max(0, Math.min(0.5, bankerCommission)) : 2;
    }

    @Override
    public String describe() {
        return "baccarat points, the third-card rules and what a bet returns";
    }
}
