package de.raindancer.modules.economy.rules;

import de.raindancer.modules.economy.model.Card;

import java.util.List;

/**
 * Blackjack as casinos play it: aces one or eleven, the dealer draws to 16 and stands on every 17 (soft
 * included), a natural pays three to two unless the table says less, a double or a split pays even money. The house edge here is the
 * game's own — about half a percent against good play — not the configurable one.
 */
public final class BlackjackRule implements IEconomyRule {

    /** What a card counts: aces one, faces ten. */
    public int pips(Card card) {
        return Math.min(10, card.rank());
    }

    /** The best total of a hand, counting one ace as eleven when that does not bust it. */
    public int total(List<Card> hand) {
        int sum = 0;
        boolean ace = false;
        for (Card card : hand) {
            sum += pips(card);
            ace |= card.rank() == 1;
        }
        return ace && sum + 10 <= 21 ? sum + 10 : sum;
    }

    public boolean soft(List<Card> hand) {
        int hard = hand.stream().mapToInt(this::pips).sum();
        return total(hand) != hard;
    }

    public boolean natural(List<Card> hand) {
        return hand.size() == 2 && total(hand) == 21;
    }

    public boolean bust(List<Card> hand) {
        return total(hand) > 21;
    }

    public boolean dealerDraws(List<Card> hand) {
        return dealerDraws(hand, false);
    }

    /** @param hitsSoft17 whether the table has the dealer draw to a soft 17 too */
    public boolean dealerDraws(List<Card> hand, boolean hitsSoft17) {
        int total = total(hand);
        return total < 17 || hitsSoft17 && total == 17 && soft(hand);
    }

    public boolean canSplit(List<Card> hand) {
        return hand.size() == 2 && pips(hand.get(0)) == pips(hand.get(1));
    }

    public boolean canDouble(List<Card> hand) {
        return hand.size() == 2;
    }

    /**
     * What a finished hand returns per unit staked, stake included: 0 lost, 1 pushed, 2 won, 2.5 a natural.
     *
     * @param fromSplit a 21 on two cards after a split is 21, not a natural
     */
    public double returns(List<Card> player, List<Card> dealer, boolean fromSplit) {
        return returns(player, dealer, fromSplit, 2.5);
    }

    /** @param naturalReturns what a natural returns per unit staked, stake included — 2.5 at three to two */
    public double returns(List<Card> player, List<Card> dealer, boolean fromSplit, double naturalReturns) {
        if (bust(player)) {
            return 0;
        }
        boolean playerNatural = natural(player) && !fromSplit;
        boolean dealerNatural = natural(dealer);
        if (playerNatural && dealerNatural) {
            return 1;
        }
        if (playerNatural) {
            return naturalReturns;
        }
        if (dealerNatural) {
            return 0;
        }
        if (bust(dealer)) {
            return 2;
        }
        return Integer.compare(total(player), total(dealer)) > 0 ? 2
                : total(player) == total(dealer) ? 1 : 0;
    }

    @Override
    public String describe() {
        return "blackjack hand values, the dealer's draw and what a hand returns";
    }
}
