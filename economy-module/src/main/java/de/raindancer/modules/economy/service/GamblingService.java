package de.raindancer.modules.economy.service;

import de.raindancer.modules.economy.model.Game;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.BetRefusal;
import de.raindancer.modules.economy.model.SlotSymbol;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.GambleRule;
import de.raindancer.modules.economy.rules.RouletteRule;
import de.raindancer.modules.economy.rules.SlotsRule;
import de.raindancer.modules.economy.model.RouletteBet;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The games of chance: coin flips against the house or another player, dice, roulette and the slot machine.
 *
 * <p>Every game is decided by a {@link SecureRandom} and settled in one ledger change before anything is
 * shown — so closing the slot machine mid-spin, or logging out, changes nothing about the result.
 */
public final class GamblingService implements IEconomyService {

    /** The outcome of one spin, already paid. */
    public record Spin(List<SlotSymbol> reels, Money stake, Money payout, Money balance) {
        public boolean won() {
            return payout.isPositive();
        }
    }

    /** A coin flip, already paid. */
    public record Flip(boolean landedHeads, boolean calledHeads, Money stake, Money payout) {
        public boolean won() {
            return payout.isPositive();
        }
    }

    /** A roulette spin, already paid. */
    public record Wheel(int pocket, RouletteBet bet, Money stake, Money payout) {
        public boolean won() {
            return payout.isPositive();
        }
    }

    /** A dice roll, already paid. */
    public record Roll(int roll, boolean over, int target, Money stake, Money payout) {
        public boolean won() {
            return payout.isPositive();
        }
    }

    private record Challenge(UUID from, String fromName, UUID to, Money stake, long expiresAt) {
    }

    private record DayLoss(long day, Money lost) {
    }

    private final Plugin plugin;
    private final Server server;
    private final RainEconomy economy;
    private final AccountBook book;
    private final Messages messages;
    private final Effects effects;
    private final ChatButtons buttons;
    private final Clock clock;
    private final GambleRule rule = new GambleRule();
    private final SlotsRule slots = new SlotsRule();
    private final RouletteRule roulette = new RouletteRule();
    private final GameSounds sounds;
    private final SecureRandom random = new SecureRandom();
    private final Cooldowns<UUID> between = new Cooldowns<>();
    private final Map<UUID, Challenge> challenges = new ConcurrentHashMap<>();
    private final Map<UUID, DayLoss> losses = new ConcurrentHashMap<>();
    private volatile EconomySettings settings;

    private volatile SupplyService supply;
    private final java.util.Set<UUID> insured = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Insured stakes of multi-step games, per player and game, until that game pays out — so a crash round and a
     * horse race running at once never pay back against each other's stake.
     */
    private final java.util.Map<String, Money> insuredStakes = new java.util.concurrent.ConcurrentHashMap<>();

    /** "Blackjack, doubled" and "Blackjack" are one game: the part before the first comma or dash-free word. */
    static String insuredKey(UUID player, String game) {
        String label = game == null ? "" : game;
        int comma = label.indexOf(',');
        String family = (comma < 0 ? label : label.substring(0, comma)).strip().toLowerCase(java.util.Locale.ROOT);
        if (family.startsWith("horse race")) {
            family = "horse race";
        }
        return player + "|" + family;
    }
    private final de.raindancer.modules.economy.rules.GambleInsuranceRule insurance =
            new de.raindancer.modules.economy.rules.GambleInsuranceRule();
    private volatile de.raindancer.modules.economy.store.SupplyBook insuranceStore;

    /** The money supply's settings; the shipped ones, which change nothing, until wired. */
    public void supply(SupplyService service) {
        this.supply = service;
    }

    private de.raindancer.modules.economy.SupplySettings supplied() {
        return SupplyService.settingsOf(supply);
    }
    private volatile java.util.function.Predicate<UUID> overdue = player -> false;

    public GamblingService(Plugin plugin, Server server, RainEconomy economy, Messages messages, Effects effects,
                           ChatButtons buttons, Clock clock, GameSounds sounds, EconomySettings settings) {
        this.sounds = sounds;
        this.plugin = plugin;
        this.server = server;
        this.economy = economy;
        this.book = economy.book();
        this.messages = messages;
        this.effects = effects;
        this.buttons = buttons;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
        between.every(Duration.ofSeconds(Math.max(0, this.settings.gambleCooldownSeconds())));
    }

    public EconomySettings config() {
        return settings;
    }

    /** Who has an overdue loan — handed over by the module, so gambling needs no reference to loans. */
    public void overdue(java.util.function.Predicate<UUID> overdue) {
        this.overdue = overdue == null ? player -> false : overdue;
    }

    // ---------------------------------------------------------------------------- the checks every game shares

    /** Whether an overdue loan keeps this player from gambling; says so when it does. */
    public boolean loanBlocks(Player player) {
        if (settings.overdueStopsGambling() && overdue.test(player.getUniqueId())) {
            refuse(player, "economy.gamble.loan-overdue");
            return true;
        }
        if (supplied().debtStopsGambling() && de.raindancer.core.social.economy.Debts.inDebt(player.getUniqueId())) {
            refuse(player, "economy.gamble.in-debt", "amount",
                    settings.currency().render(de.raindancer.core.social.economy.Debts.owed(player.getUniqueId())));
            return true;
        }
        return false;
    }

    public boolean mayBet(Player player, Money stake, Game game) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!live.gameOn(game)) {
            refuse(player, "economy.gamble.off");
            return false;
        }
        if (!player.hasPermission(PermissionNodes.GAMBLE)) {
            refuse(player, "economy.gamble.not-allowed");
            return false;
        }
        if (loanBlocks(player)) {
            return false;
        }
        if (!stake.isPositive()) {
            refuse(player, "economy.not-an-amount");
            return false;
        }
        Optional<BetRefusal> refusal = rule.refusal(stake, live.minBet(game),
                lostToday(player.getUniqueId()), live.dailyLossLimitMoney());
        if (refusal.isPresent()) {
            switch (refusal.get()) {
                case BELOW_MINIMUM -> refuse(player, "economy.gamble.too-little", "amount", currency.render(live.minBet(game)));
                case LOSS_LIMIT -> refuse(player, "economy.gamble.loss-limit", "amount",
                        currency.render(live.dailyLossLimitMoney()));
            }
            return false;
        }
        if (!between.tryUse(player.getUniqueId())) {
            refuse(player, "economy.gamble.wait");
            return false;
        }
        economy.open(player.getUniqueId(), player.getName());
        return true;
    }

    public Money lostToday(UUID player) {
        DayLoss loss = losses.get(player);
        return loss == null || loss.day() != today() ? Money.ZERO : loss.lost();
    }

    public void count(UUID player, Money stake, Money payout) {
        long day = today();
        losses.compute(player, (id, before) -> {
            Money lost = before == null || before.day() != day ? Money.ZERO : before.lost();
            Money next = lost.plus(stake).minus(payout.min(lost.plus(stake)));
            return new DayLoss(day, next);
        });
    }

    private long today() {
        return LocalDate.now(clock).toEpochDay();
    }

    // ---------------------------------------------------------------------------- bet insurance

    /** Where the players who insure their bets are kept; read once, here. Off the server thread. */
    public void insurance(de.raindancer.modules.economy.store.SupplyBook store) {
        this.insuranceStore = store;
        insured.clear();
        insured.addAll(store.insuredGamblers());
    }

    /** Whether bet insurance is offered at all on this server. */
    public boolean insuranceOffered() {
        de.raindancer.modules.economy.SupplySettings live = supplied();
        return live.gambleInsurance() && live.gambleInsurancePremium() > 0;
    }

    public boolean insures(UUID player) {
        return insured.contains(player);
    }

    /** Switches a player's bet insurance; refused (false) when it is not offered. */
    public boolean toggleInsurance(Player player) {
        if (!insuranceOffered()) {
            refuse(player, "economy.gamble.insurance-off");
            return false;
        }
        UUID id = player.getUniqueId();
        boolean now = !insured.remove(id);
        if (now) {
            insured.add(id);
        }
        var store = insuranceStore;
        if (store != null) {
            de.raindancer.core.platform.util.Scheduling.async(plugin, () -> store.insureGambler(id, now));
        }
        de.raindancer.modules.economy.SupplySettings live = supplied();
        messages.send(player, now ? "economy.gamble.insured" : "economy.gamble.uninsured",
                "premium", String.valueOf(live.gambleInsurancePremium()),
                "payback", String.valueOf(live.gambleInsurancePayback()));
        return now;
    }

    /** What insuring this stake costs now, or zero when the player does not insure or it is not offered. */
    public Money premiumFor(UUID player, Money stake) {
        if (!insures(player) || !insuranceOffered()) {
            return Money.ZERO;
        }
        return de.raindancer.core.social.economy.EconomyLevers.sink(de.raindancer.modules.economy.model.Sources.BET_INSURANCE,
                insurance.premium(stake, supplied().gambleInsurancePremium()));
    }

    /** Takes the premium; false (and the player told) when it cannot be paid — the bet is then not made. */
    private boolean payPremium(Player player, Money premium) {
        if (!premium.isPositive()) {
            return true;
        }
        EconomyResult paid = economy.move(player.getUniqueId(), premium.negate(), TransactionKind.FEE, "Bet insurance",
                de.raindancer.modules.economy.model.Sources.BET_INSURANCE);
        if (!paid.succeeded()) {
            Outcomes.tell(messages, effects, player, paid, settings.currency(), "");
            return false;
        }
        return true;
    }

    /** Pays back the insured share of a lost stake — as far as a capped treasury can. */
    private void payBack(UUID player, Money stake, Money payout) {
        Money back = insurance.payback(stake, payout, supplied().gambleInsurancePayback());
        if (!back.isPositive()) {
            return;
        }
        EconomyResult paid = economy.moveUpTo(player, back, TransactionKind.GAMBLE, "Bet insurance paid back");
        Player online = server.getPlayer(player);
        if (online != null && paid.succeeded()) {
            messages.send(online, "economy.gamble.insurance-paid", "amount", settings.currency().render(paid.amount()));
        }
    }

    /** One game against the house, settled; empty when the ledger refused it (and the player was told). */
    public Optional<EconomyResult> settle(Player player, Money stake, Money payout, String game) {
        Money premium = premiumFor(player.getUniqueId(), stake);
        if (!payPremium(player, premium)) {
            return Optional.empty();
        }
        EconomyResult result = book.play(player.getUniqueId(), stake, payout, game, economy.most());
        if (!result.succeeded()) {
            if (premium.isPositive()) {
                economy.refund(player.getUniqueId(), premium, "Bet insurance: the bet was not made",
                        de.raindancer.modules.economy.model.Sources.BET_INSURANCE);
            }
            Outcomes.tell(messages, effects, player, result, settings.currency(), "");
            return Optional.empty();
        }
        if (premium.isPositive()) {
            payBack(player.getUniqueId(), stake, payout);
        }
        count(player.getUniqueId(), stake, payout);
        economy.tell(player.getUniqueId(), payout.minus(stake), result.balance(), TransactionKind.GAMBLE);
        return Optional.of(result);
    }

    // ---------------------------------------------------------------------------- coin flip

    /** Heads or tails against the house, settled; the caller reveals it when its coin stops. */
    public Optional<Flip> flip(Player player, Money stake, boolean heads) {
        if (!mayBet(player, stake, Game.COINFLIP)) {
            return Optional.empty();
        }
        boolean landedHeads = random.nextBoolean();
        Money payout = landedHeads == heads ? rule.payout(stake, 0.5, settings.edge(Game.COINFLIP)) : Money.ZERO;
        return settle(player, stake, payout, "Coin flip").map(result -> new Flip(landedHeads, heads, stake, payout));
    }

    public void revealFlip(Player player, Flip flip) {
        announce(player, flip.won(), flip.stake(), flip.payout(), "economy.gamble.flip",
                "side", flip.landedHeads() ? "heads" : "tails");
    }

    /** What a winning flip pays, for the menu to show before the bet. */
    public Money flipWouldPay(Money stake) {
        return rule.payout(stake, 0.5, settings.edge(Game.COINFLIP));
    }

    /** Challenges another player; nothing is staked until they accept. */
    public void challenge(Player from, Player to, Money stake) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (from.getUniqueId().equals(to.getUniqueId())) {
            refuse(from, "economy.gamble.duel-yourself");
            return;
        }
        if (!mayBet(from, stake, Game.COINFLIP)) {
            return;
        }
        if (!economy.has(from.getUniqueId(), stake)) {
            refuse(from, "economy.not-enough", "amount", currency.render(stake),
                    "balance", currency.render(economy.balance(from.getUniqueId())));
            return;
        }
        Duration life = Duration.ofSeconds(Math.max(10, live.duelSeconds()));
        long expires = clock.millis() + life.toMillis();
        Challenge challenge = new Challenge(from.getUniqueId(), from.getName(), to.getUniqueId(), stake, expires);
        challenges.put(to.getUniqueId(), challenge);
        messages.send(from, "economy.gamble.duel-sent", "player", to.getName(), "amount", currency.render(stake));
        messages.send(to, "economy.gamble.duel-received", "player", from.getName(), "amount", currency.render(stake),
                "seconds", String.valueOf(life.toSeconds()),
                "buttons", buttons.ask(to.getUniqueId(), life,
                        clicker -> Scheduling.entity(plugin, to, () -> accept(to, challenge)),
                        clicker -> decline(to, challenge)));
        effects.play(to.getUniqueId(), Cues.NOTIFY);
    }

    private void accept(Player to, Challenge challenge) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!challenges.remove(to.getUniqueId(), challenge) || clock.millis() > challenge.expiresAt()) {
            refuse(to, "economy.gamble.duel-gone");
            return;
        }
        if (!live.gameOn(Game.COINFLIP)) {
            refuse(to, "economy.gamble.off");
            return;
        }
        economy.open(to.getUniqueId(), to.getName());
        boolean challengerWins = random.nextBoolean();
        UUID winner = challengerWins ? challenge.from() : to.getUniqueId();
        UUID loser = challengerWins ? to.getUniqueId() : challenge.from();
        Money cut = challenge.stake().share(live.edge(Game.COINFLIP));
        EconomyResult result = book.duel(winner, loser, challenge.stake(), cut, "Coin flip duel", economy.most());
        Player from = server.getPlayer(challenge.from());
        if (!result.succeeded()) {
            refuse(to, "economy.gamble.duel-failed");
            if (from != null) {
                refuse(from, "economy.gamble.duel-failed");
            }
            return;
        }
        Money gain = challenge.stake().minus(cut);
        String winnerName = challengerWins ? challenge.fromName() : to.getName();
        economy.tell(winner, gain, economy.balance(winner), TransactionKind.GAMBLE);
        economy.tell(loser, challenge.stake().negate(), economy.balance(loser), TransactionKind.GAMBLE);
        count(loser, challenge.stake(), Money.ZERO);
        for (Player each : new Player[]{to, from}) {
            if (each == null) {
                continue;
            }
            boolean won = each.getUniqueId().equals(winner);
            messages.send(each, won ? "economy.gamble.duel-won" : "economy.gamble.duel-lost",
                    "player", winnerName, "amount", currency.render(won ? gain : challenge.stake()));
            sounds.play(each.getUniqueId(), won ? GameSounds.WIN : GameSounds.LOSE);
        }
    }

    private void decline(Player to, Challenge challenge) {
        challenges.remove(to.getUniqueId(), challenge);
        Player from = server.getPlayer(challenge.from());
        if (from != null) {
            messages.send(from, "economy.gamble.duel-declined", "player", to.getName());
        }
        messages.send(to, "economy.gamble.duel-you-declined", "player", challenge.fromName());
    }

    // ---------------------------------------------------------------------------- dice

    /** One roll of 1–100, settled; the caller reveals it when its dice stop. */
    public Optional<Roll> dice(Player player, Money stake, boolean over, int target) {
        if (!rule.diceValid(over, target)) {
            refuse(player, "economy.gamble.dice-bad-target");
            return Optional.empty();
        }
        if (!mayBet(player, stake, Game.DICE)) {
            return Optional.empty();
        }
        int roll = random.nextInt(100) + 1;
        boolean won = rule.diceWins(over, target, roll);
        Money payout = won ? rule.payout(stake, rule.diceChance(over, target), settings.edge(Game.DICE)) : Money.ZERO;
        return settle(player, stake, payout, "Dice").map(result -> new Roll(roll, over, target, stake, payout));
    }

    public void revealRoll(Player player, Roll roll) {
        announce(player, roll.won(), roll.stake(), roll.payout(), "economy.gamble.dice",
                "roll", String.valueOf(roll.roll()));
    }

    public boolean diceValid(boolean over, int target) {
        return rule.diceValid(over, target);
    }

    public double diceChance(boolean over, int target) {
        return rule.diceChance(over, target);
    }

    /** What a dice bet would pay if it won, for showing before it is placed. */
    public Money diceWouldPay(Money stake, boolean over, int target) {
        return rule.diceValid(over, target)
                ? rule.payout(stake, rule.diceChance(over, target), settings.edge(Game.DICE)) : Money.ZERO;
    }

    // ---------------------------------------------------------------------------- roulette

    /** One spin of the wheel, settled; the caller reveals it when its ball stops. */
    public Optional<Wheel> roulette(Player player, Money stake, RouletteBet bet) {
        if (!mayBet(player, stake, Game.ROULETTE)) {
            return Optional.empty();
        }
        int pocket = roulette.spin(random::nextInt);
        Money payout = roulette.wins(bet, pocket) ? roulette.payout(stake, bet, settings.edge(Game.ROULETTE)) : Money.ZERO;
        return settle(player, stake, payout, "Roulette: " + bet.label())
                .map(result -> new Wheel(pocket, bet, stake, payout));
    }

    public void revealWheel(Player player, Wheel wheel) {
        announce(player, wheel.won(), wheel.stake(), wheel.payout(), "economy.gamble.roulette",
                "pocket", wheel.pocket() + " " + colourName(wheel.pocket()), "bet", wheel.bet().label());
    }

    public RouletteRule.Colour colourOf(int pocket) {
        return roulette.colourOf(pocket);
    }

    private String colourName(int pocket) {
        return switch (roulette.colourOf(pocket)) {
            case RED -> "red";
            case BLACK -> "black";
            case GREEN -> "green";
        };
    }

    public Money rouletteWouldPay(Money stake, RouletteBet bet) {
        return roulette.payout(stake, bet, settings.edge(Game.ROULETTE));
    }

    public GameSounds sounds() {
        return sounds;
    }

    // ---------------------------------------------------------------------------- slots

    /** Spins and settles at once; the menu only shows what has already happened. */
    public Optional<Spin> spin(Player player, Money stake) {
        if (!mayBet(player, stake, Game.SLOTS)) {
            return Optional.empty();
        }
        List<SlotSymbol> reels = slots.spin(random::nextInt);
        Money payout = slots.payout(stake, reels, settings.edge(Game.SLOTS));
        return settle(player, stake, payout, "Slots").map(result -> new Spin(reels, stake, payout, result.balance()));
    }

    public double slotsMultiplier(List<SlotSymbol> reels) {
        return slots.multiplier(reels, settings.edge(Game.SLOTS));
    }

    /** Called by the slot machine once its reels have stopped. */
    public void revealSpin(Player player, Spin spin) {
        announce(player, spin.won(), spin.stake(), spin.payout(), "economy.gamble.slots", "reels",
                spin.reels().stream().map(symbol -> symbol.name().charAt(0) + symbol.name().substring(1).toLowerCase())
                        .reduce((a, b) -> a + " · " + b).orElse(""));
    }

    private void announce(Player player, boolean won, Money stake, Money payout, String key, Object... extra) {
        Currency currency = settings.currency();
        Object[] values = new Object[extra.length + 4];
        System.arraycopy(extra, 0, values, 0, extra.length);
        values[extra.length] = "amount";
        values[extra.length + 1] = currency.render(won ? payout : stake);
        values[extra.length + 2] = "balance";
        values[extra.length + 3] = currency.render(economy.balance(player.getUniqueId()));
        messages.send(player, key + (won ? "-won" : "-lost"), values);
        sounds.outcome(player.getUniqueId(), stake.minor(), won ? payout.minor() : 0);
    }

    /**
     * Takes a stake for a game that is decided over several steps — a blackjack hand, a mines field, a
     * crash round. What it pays comes later through {@link #payOut}; losses are counted when it ends.
     */
    public boolean takeStake(Player player, Money stake, String game) {
        Money premium = premiumFor(player.getUniqueId(), stake);
        if (!payPremium(player, premium)) {
            return false;
        }
        EconomyResult result = book.play(player.getUniqueId(), stake, Money.ZERO, game, economy.most());
        if (!result.succeeded()) {
            if (premium.isPositive()) {
                economy.refund(player.getUniqueId(), premium, "Bet insurance: the bet was not made",
                        de.raindancer.modules.economy.model.Sources.BET_INSURANCE);
            }
            Outcomes.tell(messages, effects, player, result, settings.currency(), "");
            return false;
        }
        if (premium.isPositive()) {
            insuredStakes.merge(insuredKey(player.getUniqueId(), game), stake, Money::plus);
        }
        economy.tell(player.getUniqueId(), stake.negate(), result.balance(), TransactionKind.GAMBLE);
        return true;
    }

    /** Pays what a multi-step game returned, stake included, and counts the day's loss. */
    public void payOut(UUID player, Money stake, Money payout, String game) {
        count(player, stake, payout);
        Money insuredStake = insuredStakes.remove(insuredKey(player, game));
        if (insuredStake != null) {
            // Only the insured part of what was lost is paid back against: never more than was insured.
            Money lost = stake.minus(payout.min(stake)).min(insuredStake);
            payBack(player, lost, Money.ZERO);
        }
        if (payout.isPositive()) {
            EconomyResult result = economy.move(player, payout, TransactionKind.GAMBLE, game + " — won");
            if (result.outcome() == EconomyResult.Outcome.TREASURY_EMPTY) {
                // A capped server's treasury ran low after the stake was taken: pay what it can, and say so.
                EconomyResult part = economy.moveUpTo(player, payout, TransactionKind.GAMBLE, game + " — won, in part");
                Player online = server.getPlayer(player);
                if (online != null) {
                    messages.send(online, "economy.gamble.treasury-short", "amount",
                            settings.currency().render(part.succeeded() ? part.amount() : Money.ZERO),
                            "won", settings.currency().render(payout));
                }
            } else if (!result.succeeded()) {
                // An account that cannot take its winnings (frozen, full) gets the stake back at least.
                economy.move(player, stake, TransactionKind.GAMBLE, game + " — returned");
            }
        }
    }

    /** Says how a multi-step game ended and plays its sound. */
    public void finish(Player player, boolean won, Money stake, Money payout, String key, Object... extra) {
        announce(player, won, stake, payout, key, extra);
    }

    public Currency currency() {
        return settings.currency();
    }

    /** A line of a table game that is neither a win nor a loss — a push, a returned stake. */
    public void tell(Player player, String key, Object... values) {
        messages.send(player, key, values);
    }

    public void forget(UUID player) {
        challenges.remove(player);
        challenges.values().removeIf(challenge -> challenge.from().equals(player));
    }

    private void refuse(Player player, String key, Object... values) {
        messages.send(player, key, values);
        effects.play(player.getUniqueId(), Cues.NO);
    }
}
