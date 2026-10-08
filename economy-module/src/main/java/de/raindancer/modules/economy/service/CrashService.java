package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.rules.CrashRule;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * One crash round for the whole server: bets are open for a few seconds, then the multiplier climbs until
 * the crash point drawn at the start — known to the server, to nobody else. Whoever cashes out before it
 * is paid their stake times the multiplier; whoever does not, loses it.
 */
public final class CrashService implements IEconomyService {

    public enum Phase { BETTING, RUNNING, CRASHED }

    /** One player's bet in the current round. */
    public static final class Bet {
        public final Money stake;
        public final double autoCashOut;
        public double cashedAt;

        Bet(Money stake, double autoCashOut) {
            this.stake = stake;
            this.autoCashOut = autoCashOut;
        }
    }

    private static final long CRASHED_MILLIS = 4_000;

    private final Server server;
    private final GamblingService gambling;
    private final LongSupplier clock;
    private final CrashRule rule = new CrashRule();
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Bet> bets = new LinkedHashMap<>();
    private final List<Double> history = new ArrayList<>();
    private volatile EconomySettings settings;
    private Phase phase = Phase.BETTING;
    private long phaseStarted;
    private double crashPoint;
    private double shown = 1.0;

    public CrashService(Server server, GamblingService gambling, LongSupplier clock, EconomySettings settings) {
        this.server = server;
        this.gambling = gambling;
        this.clock = clock;
        this.phaseStarted = clock.getAsLong();
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized double multiplier() {
        return shown;
    }

    public synchronized long secondsToStart() {
        return Math.max(0, (phaseStarted + settings.crashBettingSeconds() * 1000L - clock.getAsLong() + 999) / 1000);
    }

    public synchronized Map<UUID, Bet> bets() {
        return Map.copyOf(bets);
    }

    public synchronized List<Double> history() {
        return List.copyOf(history);
    }

    /** Every couple of ticks, on the global thread. */
    public synchronized void tick() {
        long now = clock.getAsLong();
        switch (phase) {
            case BETTING -> {
                if (now - phaseStarted >= settings.crashBettingSeconds() * 1000L) {
                    phase = Phase.RUNNING;
                    phaseStarted = now;
                    crashPoint = rule.crashPoint(1 - random.nextDouble(), settings.houseEdge());
                    shown = 1.0;
                }
            }
            case RUNNING -> {
                double at = rule.multiplierAt(now - phaseStarted);
                // Before the crash: one step can pass a target and the crash point both.
                for (Map.Entry<UUID, Bet> each : bets.entrySet()) {
                    Bet bet = each.getValue();
                    if (bet.cashedAt == 0 && rule.autoCashesOut(bet.autoCashOut, at, crashPoint)) {
                        cashOut(each.getKey(), bet, bet.autoCashOut);
                    }
                }
                if (at >= crashPoint) {
                    shown = crashPoint;
                    crash();
                    return;
                }
                shown = at;
            }
            case CRASHED -> {
                if (now - phaseStarted >= CRASHED_MILLIS) {
                    phase = Phase.BETTING;
                    phaseStarted = now;
                    bets.clear();
                    shown = 1.0;
                }
            }
        }
    }

    private void crash() {
        phase = Phase.CRASHED;
        phaseStarted = clock.getAsLong();
        history.addFirst(crashPoint);
        while (history.size() > 8) {
            history.removeLast();
        }
        for (Map.Entry<UUID, Bet> each : bets.entrySet()) {
            Bet bet = each.getValue();
            Player player = server.getPlayer(each.getKey());
            if (bet.cashedAt == 0) {
                gambling.payOut(each.getKey(), bet.stake, Money.ZERO, "Crash");
                if (player != null) {
                    gambling.finish(player, false, bet.stake, Money.ZERO, "economy.gamble.crash",
                            "at", String.format("%.2f", crashPoint));
                }
            }
            if (player != null) {
                gambling.sounds().play(player.getUniqueId(), GameSounds.CRASH);
            }
        }
    }

    /** Joins the coming round. */
    public synchronized boolean bet(Player player, Money stake, double autoCashOut) {
        if (phase != Phase.BETTING) {
            gambling.tell(player, "economy.gamble.crash-closed");
            return false;
        }
        if (bets.containsKey(player.getUniqueId())) {
            gambling.tell(player, "economy.gamble.crash-already");
            return false;
        }
        if (!gambling.mayBet(player, stake, settings.crashEnabled()) || !gambling.takeStake(player, stake, "Crash")) {
            return false;
        }
        bets.put(player.getUniqueId(), new Bet(stake, autoCashOut));
        gambling.sounds().play(player.getUniqueId(), GameSounds.CHIPS);
        return true;
    }

    /** Cashes out now, at the multiplier showing. */
    public synchronized void cashOut(Player player) {
        Bet bet = bets.get(player.getUniqueId());
        if (phase == Phase.RUNNING && bet != null && bet.cashedAt == 0) {
            cashOut(player.getUniqueId(), bet, shown);
        }
    }

    private void cashOut(UUID id, Bet bet, double at) {
        bet.cashedAt = at;
        Money payout = bet.stake.share(at);
        gambling.payOut(id, bet.stake, payout, "Crash");
        Player player = server.getPlayer(id);
        if (player != null) {
            gambling.finish(player, true, bet.stake, payout, "economy.gamble.crash", "at", String.format("%.2f", at));
        }
    }

    /** The module stopping: every open stake goes back, nothing is lost to a restart. */
    public synchronized void refundAll() {
        for (Map.Entry<UUID, Bet> each : bets.entrySet()) {
            if (each.getValue().cashedAt == 0 && phase != Phase.CRASHED) {
                gambling.payOut(each.getKey(), each.getValue().stake, each.getValue().stake, "Crash");
            }
        }
        bets.clear();
    }
}
