package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Card;
import de.raindancer.modules.economy.model.Shoe;
import de.raindancer.modules.economy.rules.BaccaratRule;
import de.raindancer.modules.economy.rules.BlackjackRule;
import de.raindancer.modules.economy.rules.HiLoRule;
import de.raindancer.modules.economy.rules.MinesRule;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The dealer's tables: blackjack, baccarat, hi-lo and mines. Each player has their own shoe, shuffled
 * afresh when it runs low, and at most one hand of each game in play. A hand left open — the window closed,
 * the player gone, the module stopping — is finished the way that costs the player least: blackjack stands,
 * hi-lo and mines cash out.
 */
public final class TableService implements IEconomyService {

    /** One blackjack round: up to two hands after a split, and the dealer's. */
    public static final class Blackjack {
        public final List<List<Card>> hands = new ArrayList<>();
        public final List<Money> stakes = new ArrayList<>();
        public final List<Card> dealer = new ArrayList<>();
        public int active;
        public boolean split;
        public boolean finished;
        public Money paid = Money.ZERO;
        public String says = "Hit or stand?";
        /** Whether the result has been told; the window tells it once the dealer's cards are turned. */
        public boolean told;

        public Money staked() {
            Money total = Money.ZERO;
            for (Money stake : stakes) {
                total = total.plus(stake);
            }
            return total;
        }
    }

    /** One hi-lo run: the card showing, what the stake has grown to, and how many right in a row. */
    public static final class HiLo {
        public Card showing;
        public double multiplier = 1;
        public int streak;
        public Money stake;
        public boolean finished;
        public Card last;
    }

    /** One mines field. */
    public static final class Mines {
        public final Set<Integer> mines = new HashSet<>();
        public final Set<Integer> cleared = new HashSet<>();
        public int count;
        public Money stake;
        public boolean finished;
        public int boom = -1;
    }

    /** A baccarat coup, already paid. */
    public record Coup(List<Card> player, List<Card> banker, BaccaratRule.Side winner, BaccaratRule.Side bet,
                       Money stake, Money payout) {
    }

    private final Server server;
    private final GamblingService gambling;
    private final BlackjackRule blackjackRule = new BlackjackRule();
    private final BaccaratRule baccaratRule = new BaccaratRule();
    private final HiLoRule hiLoRule = new HiLoRule();
    private final MinesRule minesRule = new MinesRule();
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Shoe> shoes = new ConcurrentHashMap<>();
    private final Map<UUID, Blackjack> blackjack = new ConcurrentHashMap<>();
    private final Map<UUID, HiLo> hiLo = new ConcurrentHashMap<>();
    private final Map<UUID, Mines> mines = new ConcurrentHashMap<>();
    private volatile EconomySettings settings;

    public TableService(Server server, GamblingService gambling, EconomySettings settings) {
        this.server = server;
        this.gambling = gambling;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    public BlackjackRule blackjackRule() {
        return blackjackRule;
    }

    public BaccaratRule baccaratRule() {
        return baccaratRule;
    }

    public HiLoRule hiLoRule() {
        return hiLoRule;
    }

    public MinesRule minesRule() {
        return minesRule;
    }

    public double edge() {
        return settings.houseEdge();
    }

    private Card draw(UUID player) {
        Shoe shoe = shoes.compute(player, (id, old) -> old == null || old.nearlyEmpty()
                ? new Shoe(settings.decks(), random) : old);
        return shoe.draw();
    }

    public int[] ranksLeft(UUID player) {
        Shoe shoe = shoes.computeIfAbsent(player, id -> new Shoe(settings.decks(), random));
        return shoe.ranksLeft();
    }

    // ---------------------------------------------------------------------------- blackjack

    public Optional<Blackjack> blackjack(UUID player) {
        return Optional.ofNullable(blackjack.get(player));
    }

    public Optional<Blackjack> deal(Player player, Money stake) {
        Blackjack open = blackjack.get(player.getUniqueId());
        if (open != null && !open.finished) {
            return Optional.of(open);
        }
        if (!gambling.mayBet(player, stake, settings.blackjackEnabled())
                || !gambling.takeStake(player, stake, "Blackjack")) {
            return Optional.empty();
        }
        UUID id = player.getUniqueId();
        Blackjack game = new Blackjack();
        List<Card> hand = new ArrayList<>();
        hand.add(draw(id));
        game.dealer.add(draw(id));
        hand.add(draw(id));
        game.dealer.add(draw(id));
        game.hands.add(hand);
        game.stakes.add(stake);
        blackjack.put(id, game);
        if (blackjackRule.natural(hand) || blackjackRule.natural(game.dealer)) {
            settle(player, game);
        }
        return Optional.of(game);
    }

    public void hit(Player player) {
        Blackjack game = blackjack.get(player.getUniqueId());
        if (game == null || game.finished) {
            return;
        }
        List<Card> hand = game.hands.get(game.active);
        hand.add(draw(player.getUniqueId()));
        if (blackjackRule.bust(hand)) {
            game.says = "Bust.";
            next(player, game);
        } else if (blackjackRule.total(hand) == 21) {
            next(player, game);
        } else {
            game.says = "Hit or stand?";
        }
    }

    public void stand(Player player) {
        Blackjack game = blackjack.get(player.getUniqueId());
        if (game != null && !game.finished) {
            next(player, game);
        }
    }

    public void doubleDown(Player player) {
        Blackjack game = blackjack.get(player.getUniqueId());
        if (game == null || game.finished || !blackjackRule.canDouble(game.hands.get(game.active))) {
            return;
        }
        Money stake = game.stakes.get(game.active);
        if (!gambling.takeStake(player, stake, "Blackjack, doubled")) {
            return;
        }
        game.stakes.set(game.active, stake.times(2));
        game.hands.get(game.active).add(draw(player.getUniqueId()));
        game.says = "Doubled — one card.";
        next(player, game);
    }

    public void split(Player player) {
        Blackjack game = blackjack.get(player.getUniqueId());
        if (game == null || game.finished || game.split || !blackjackRule.canSplit(game.hands.getFirst())) {
            return;
        }
        Money stake = game.stakes.getFirst();
        if (!gambling.takeStake(player, stake, "Blackjack, split")) {
            return;
        }
        UUID id = player.getUniqueId();
        List<Card> first = game.hands.getFirst();
        List<Card> second = new ArrayList<>();
        second.add(first.removeLast());
        first.add(draw(id));
        second.add(draw(id));
        game.hands.add(second);
        game.stakes.add(stake);
        game.split = true;
        game.says = "Two hands. Play the first.";
        if (first.getFirst().rank() == 1) {
            // Split aces take one card each and stand, as at any casino.
            game.active = 1;
            next(player, game);
        }
    }

    private void next(Player player, Blackjack game) {
        if (game.active < game.hands.size() - 1) {
            game.active++;
            game.says = "Now the second hand.";
            return;
        }
        settle(player, game);
    }

    private void settle(Player player, Blackjack game) {
        UUID id = player.getUniqueId();
        boolean allBust = game.hands.stream().allMatch(blackjackRule::bust);
        boolean naturalShown = !game.split && blackjackRule.natural(game.hands.getFirst());
        if (!allBust && !naturalShown) {
            while (blackjackRule.dealerDraws(game.dealer)) {
                game.dealer.add(draw(id));
            }
        }
        long paid = 0;
        for (int i = 0; i < game.hands.size(); i++) {
            double returns = blackjackRule.returns(game.hands.get(i), game.dealer, game.split);
            paid = Math.addExact(paid, game.stakes.get(i).share(returns).minor());
        }
        game.paid = Money.of(paid);
        game.finished = true;
        Money staked = game.staked();
        gambling.payOut(id, staked, game.paid, "Blackjack");
        int dealerTotal = blackjackRule.total(game.dealer);
        game.says = blackjackRule.natural(game.dealer) ? "Dealer has blackjack."
                : blackjackRule.bust(game.dealer) ? "Dealer busts with " + dealerTotal + "."
                : "Dealer has " + dealerTotal + ".";
    }

    /** Tells how a finished round went — once, when the window has shown it, or when the player leaves. */
    public void tell(Player player, Blackjack game) {
        if (!game.finished || game.told) {
            return;
        }
        game.told = true;
        Money staked = game.staked();
        if (game.paid.equals(staked)) {
            gambling.tell(player, "economy.gamble.blackjack-push", "amount", gambling.currency().render(staked));
            gambling.sounds().play(player.getUniqueId(), GameSounds.CHIPS);
        } else {
            gambling.finish(player, game.paid.isMoreThan(staked), staked, game.paid, "economy.gamble.blackjack",
                    "dealer", game.says);
        }
    }

    // ---------------------------------------------------------------------------- baccarat

    public Optional<Coup> baccarat(Player player, Money stake, BaccaratRule.Side bet) {
        if (!gambling.mayBet(player, stake, settings.baccaratEnabled())) {
            return Optional.empty();
        }
        UUID id = player.getUniqueId();
        List<Card> punto = new ArrayList<>(List.of(draw(id), draw(id)));
        List<Card> banco = new ArrayList<>(List.of(draw(id), draw(id)));
        if (!baccaratRule.natural(punto) && !baccaratRule.natural(banco)) {
            Card third = null;
            if (baccaratRule.playerDraws(punto)) {
                third = draw(id);
                punto.add(third);
            }
            if (baccaratRule.bankerDraws(banco, third)) {
                banco.add(draw(id));
            }
        }
        BaccaratRule.Side winner = baccaratRule.winner(punto, banco);
        Money payout = stake.share(baccaratRule.returns(bet, winner));
        return gambling.settle(player, stake, payout, "Baccarat: " + bet.name().toLowerCase())
                .map(result -> new Coup(punto, banco, winner, bet, stake, payout));
    }

    public void revealCoup(Player player, Coup coup) {
        String result = coup.winner() == BaccaratRule.Side.TIE ? "a tie"
                : coup.winner().name().charAt(0) + coup.winner().name().substring(1).toLowerCase() + " wins";
        if (coup.payout().equals(coup.stake())) {
            gambling.tell(player, "economy.gamble.baccarat-push", "result", result,
                    "amount", gambling.currency().render(coup.stake()));
            gambling.sounds().play(player.getUniqueId(), GameSounds.CHIPS);
            return;
        }
        gambling.finish(player, coup.payout().isMoreThan(coup.stake()), coup.stake(), coup.payout(),
                "economy.gamble.baccarat", "result", result,
                "points", baccaratRule.points(coup.player()) + " to " + baccaratRule.points(coup.banker()));
    }

    // ---------------------------------------------------------------------------- hi-lo

    public Optional<HiLo> hiLo(UUID player) {
        return Optional.ofNullable(hiLo.get(player));
    }

    public Optional<HiLo> startHiLo(Player player, Money stake) {
        HiLo open = hiLo.get(player.getUniqueId());
        if (open != null && !open.finished) {
            return Optional.of(open);
        }
        if (!gambling.mayBet(player, stake, settings.hiloEnabled()) || !gambling.takeStake(player, stake, "Hi-Lo")) {
            return Optional.empty();
        }
        HiLo game = new HiLo();
        game.stake = stake;
        game.showing = draw(player.getUniqueId());
        hiLo.put(player.getUniqueId(), game);
        return Optional.of(game);
    }

    /** One guess; the card is drawn and the run goes on or ends. */
    public void guess(Player player, boolean higher) {
        HiLo game = hiLo.get(player.getUniqueId());
        if (game == null || game.finished) {
            return;
        }
        double chance = hiLoRule.chance(game.showing.rank(), higher, ranksLeft(player.getUniqueId()));
        if (chance <= 0) {
            return;
        }
        Card next = draw(player.getUniqueId());
        game.last = game.showing;
        if (hiLoRule.wins(game.showing.rank(), next.rank(), higher)) {
            game.multiplier *= hiLoRule.step(chance, settings.houseEdge());
            game.streak++;
            game.showing = next;
            gambling.sounds().play(player.getUniqueId(), GameSounds.WIN_STEP);
        } else {
            game.showing = next;
            game.finished = true;
            gambling.payOut(player.getUniqueId(), game.stake, Money.ZERO, "Hi-Lo");
            gambling.finish(player, false, game.stake, Money.ZERO, "economy.gamble.hilo", "card", next.fullName());
        }
    }

    public void cashOutHiLo(Player player) {
        HiLo game = hiLo.get(player.getUniqueId());
        if (game == null || game.finished) {
            return;
        }
        game.finished = true;
        Money payout = game.stake.share(game.multiplier);
        gambling.payOut(player.getUniqueId(), game.stake, payout, "Hi-Lo");
        if (game.streak == 0) {
            gambling.tell(player, "economy.gamble.returned", "amount", gambling.currency().render(payout));
        } else {
            gambling.finish(player, true, game.stake, payout, "economy.gamble.hilo", "card", game.showing.fullName());
        }
    }

    // ---------------------------------------------------------------------------- mines

    public Optional<Mines> mines(UUID player) {
        return Optional.ofNullable(mines.get(player));
    }

    public Optional<Mines> startMines(Player player, Money stake, int count) {
        Mines open = mines.get(player.getUniqueId());
        if (open != null && !open.finished) {
            return Optional.of(open);
        }
        if (!minesRule.validMines(count)) {
            gambling.tell(player, "economy.gamble.mines-count");
            return Optional.empty();
        }
        if (!gambling.mayBet(player, stake, settings.minesEnabled()) || !gambling.takeStake(player, stake, "Mines")) {
            return Optional.empty();
        }
        Mines field = new Mines();
        field.count = count;
        field.stake = stake;
        while (field.mines.size() < count) {
            field.mines.add(random.nextInt(MinesRule.TILES));
        }
        mines.put(player.getUniqueId(), field);
        return Optional.of(field);
    }

    public void pick(Player player, int tile) {
        Mines field = mines.get(player.getUniqueId());
        if (field == null || field.finished || field.cleared.contains(tile) || tile < 0 || tile >= MinesRule.TILES) {
            return;
        }
        if (field.mines.contains(tile)) {
            field.finished = true;
            field.boom = tile;
            gambling.payOut(player.getUniqueId(), field.stake, Money.ZERO, "Mines");
            gambling.sounds().play(player.getUniqueId(), GameSounds.BOOM);
            gambling.finish(player, false, field.stake, Money.ZERO, "economy.gamble.mines",
                    "tiles", String.valueOf(field.cleared.size()));
            return;
        }
        field.cleared.add(tile);
        gambling.sounds().play(player.getUniqueId(), GameSounds.GEM);
        if (field.cleared.size() == MinesRule.TILES - field.count) {
            cashOutMines(player);
        }
    }

    public Money minesWorth(Mines field) {
        return field.stake.share(minesRule.multiplier(field.count, field.cleared.size(), settings.houseEdge()));
    }

    public void cashOutMines(Player player) {
        Mines field = mines.get(player.getUniqueId());
        if (field == null || field.finished) {
            return;
        }
        field.finished = true;
        Money payout = minesWorth(field);
        gambling.payOut(player.getUniqueId(), field.stake, payout, "Mines");
        if (field.cleared.isEmpty()) {
            gambling.tell(player, "economy.gamble.returned", "amount", gambling.currency().render(payout));
        } else {
            gambling.finish(player, true, field.stake, payout, "economy.gamble.mines",
                    "tiles", String.valueOf(field.cleared.size()));
        }
    }

    // ---------------------------------------------------------------------------- leaving

    /** Finishes whatever this player left open, the way that costs them least. */
    public void leave(Player player) {
        Blackjack game = blackjack.get(player.getUniqueId());
        while (game != null && !game.finished) {
            next(player, game);
        }
        if (game != null) {
            tell(player, game);
        }
        cashOutHiLo(player);
        cashOutMines(player);
    }

    public void leaveAll() {
        server.getOnlinePlayers().forEach(this::leave);
    }

    public void forget(UUID player) {
        shoes.remove(player);
        blackjack.remove(player);
        hiLo.remove(player);
        mines.remove(player);
    }
}
