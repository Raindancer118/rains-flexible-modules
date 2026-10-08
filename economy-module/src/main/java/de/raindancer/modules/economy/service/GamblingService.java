package de.raindancer.modules.economy.service;

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
import de.raindancer.modules.economy.rules.SlotsRule;
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
 * The games of chance: coin flips against the house or another player, dice, and the slot machine.
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
    private final SecureRandom random = new SecureRandom();
    private final Cooldowns<UUID> between = new Cooldowns<>();
    private final Map<UUID, Challenge> challenges = new ConcurrentHashMap<>();
    private final Map<UUID, DayLoss> losses = new ConcurrentHashMap<>();
    private volatile EconomySettings settings;

    public GamblingService(Plugin plugin, Server server, RainEconomy economy, Messages messages, Effects effects,
                           ChatButtons buttons, Clock clock, EconomySettings settings) {
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

    // ---------------------------------------------------------------------------- the checks every game shares

    private boolean mayBet(Player player, Money stake, boolean gameOn) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!live.gameOpen(gameOn)) {
            refuse(player, "economy.gamble.off");
            return false;
        }
        if (!player.hasPermission(PermissionNodes.GAMBLE)) {
            refuse(player, "economy.gamble.not-allowed");
            return false;
        }
        if (!stake.isPositive()) {
            refuse(player, "economy.not-an-amount");
            return false;
        }
        Optional<BetRefusal> refusal = rule.refusal(stake, live.minBetMoney(), live.maxBetMoney(),
                lostToday(player.getUniqueId()), live.dailyLossLimitMoney());
        if (refusal.isPresent()) {
            switch (refusal.get()) {
                case BELOW_MINIMUM -> refuse(player, "economy.gamble.too-little", "amount", currency.render(live.minBetMoney()));
                case ABOVE_MAXIMUM -> refuse(player, "economy.gamble.too-much", "amount", currency.render(live.maxBetMoney()));
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

    private void count(UUID player, Money stake, Money payout) {
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

    /** One game against the house, settled; empty when the ledger refused it (and the player was told). */
    private Optional<EconomyResult> settle(Player player, Money stake, Money payout, String game) {
        EconomyResult result = book.play(player.getUniqueId(), stake, payout, game, economy.most());
        if (!result.succeeded()) {
            Outcomes.tell(messages, effects, player, result, settings.currency(), "");
            return Optional.empty();
        }
        count(player.getUniqueId(), stake, payout);
        economy.tell(player.getUniqueId(), payout.minus(stake), result.balance(), TransactionKind.GAMBLE);
        return Optional.of(result);
    }

    // ---------------------------------------------------------------------------- coin flip

    /** Heads or tails against the house, settled; the caller reveals it when its coin stops. */
    public Optional<Flip> flip(Player player, Money stake, boolean heads) {
        if (!mayBet(player, stake, settings.coinflipEnabled())) {
            return Optional.empty();
        }
        boolean landedHeads = random.nextBoolean();
        Money payout = landedHeads == heads ? rule.payout(stake, 0.5, settings.houseEdge()) : Money.ZERO;
        return settle(player, stake, payout, "Coin flip").map(result -> new Flip(landedHeads, heads, stake, payout));
    }

    public void revealFlip(Player player, Flip flip) {
        announce(player, flip.won(), flip.stake(), flip.payout(), "economy.gamble.flip",
                "side", flip.landedHeads() ? "heads" : "tails");
    }

    /** What a winning flip pays, for the menu to show before the bet. */
    public Money flipWouldPay(Money stake) {
        return rule.payout(stake, 0.5, settings.houseEdge());
    }

    /** Challenges another player; nothing is staked until they accept. */
    public void challenge(Player from, Player to, Money stake) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (from.getUniqueId().equals(to.getUniqueId())) {
            refuse(from, "economy.gamble.duel-yourself");
            return;
        }
        if (!mayBet(from, stake, live.coinflipEnabled())) {
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
        if (!live.gameOpen(live.coinflipEnabled())) {
            refuse(to, "economy.gamble.off");
            return;
        }
        economy.open(to.getUniqueId(), to.getName());
        boolean challengerWins = random.nextBoolean();
        UUID winner = challengerWins ? challenge.from() : to.getUniqueId();
        UUID loser = challengerWins ? to.getUniqueId() : challenge.from();
        Money cut = challenge.stake().share(live.houseEdge());
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
            effects.play(each.getUniqueId(), won ? Cues.REWARD : Cues.NO);
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
        if (!mayBet(player, stake, settings.diceEnabled())) {
            return Optional.empty();
        }
        int roll = random.nextInt(100) + 1;
        boolean won = rule.diceWins(over, target, roll);
        Money payout = won ? rule.payout(stake, rule.diceChance(over, target), settings.houseEdge()) : Money.ZERO;
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
                ? rule.payout(stake, rule.diceChance(over, target), settings.houseEdge()) : Money.ZERO;
    }

    // ---------------------------------------------------------------------------- slots

    /** Spins and settles at once; the menu only shows what has already happened. */
    public Optional<Spin> spin(Player player, Money stake) {
        if (!mayBet(player, stake, settings.slotsEnabled())) {
            return Optional.empty();
        }
        List<SlotSymbol> reels = slots.spin(random::nextInt);
        Money payout = slots.payout(stake, reels, settings.houseEdge());
        return settle(player, stake, payout, "Slots").map(result -> new Spin(reels, stake, payout, result.balance()));
    }

    public double slotsMultiplier(List<SlotSymbol> reels) {
        return slots.multiplier(reels, settings.houseEdge());
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
        effects.play(player.getUniqueId(), won ? Cues.REWARD : Cues.NO);
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
