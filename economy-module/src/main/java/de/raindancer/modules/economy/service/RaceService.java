package de.raindancer.modules.economy.service;

import de.raindancer.modules.economy.model.Game;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.rules.HorseRaceRule;
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
 * One horse race for the whole server, over and over: bets are open for a while, the race runs, the winner
 * is paid. Who wins is drawn when the race starts; the race is generated to match.
 */
public final class RaceService implements IEconomyService {

    public enum Phase { BETTING, RUNNING, FINISHED }

    public record Bet(int horse, Money stake) {
    }

    private static final long FRAME_MILLIS = 400;
    private static final long FINISHED_MILLIS = 8_000;

    private final Server server;
    private final GamblingService gambling;
    private final LongSupplier clock;
    private final HorseRaceRule rule = new HorseRaceRule();
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, List<Bet>> bets = new LinkedHashMap<>();
    private volatile EconomySettings settings;
    private Phase phase = Phase.BETTING;
    private long phaseStarted;
    private int winner = -1;
    private List<List<Integer>> race = List.of();
    private int frame;

    public RaceService(Server server, GamblingService gambling, LongSupplier clock, EconomySettings settings) {
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

    public HorseRaceRule rule() {
        return rule;
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized int winner() {
        return phase == Phase.FINISHED ? winner : -1;
    }

    /** Where every horse is right now, out of {@link HorseRaceRule#TRACK}. */
    public synchronized int[] positions() {
        int[] at = new int[rule.horses()];
        if (phase == Phase.BETTING || race.isEmpty()) {
            return at;
        }
        for (int horse = 0; horse < at.length; horse++) {
            List<Integer> path = race.get(horse);
            at[horse] = path.get(Math.min(frame, path.size() - 1));
        }
        return at;
    }

    public synchronized long secondsToStart() {
        return Math.max(0, (phaseStarted + settings.raceBettingSeconds() * 1000L - clock.getAsLong() + 999) / 1000);
    }

    public synchronized List<Bet> betsOf(UUID player) {
        return List.copyOf(bets.getOrDefault(player, List.of()));
    }

    public double pays(int horse) {
        return rule.pays(horse, settings.edge(Game.RACE));
    }

    public synchronized void tick() {
        long now = clock.getAsLong();
        switch (phase) {
            case BETTING -> {
                if (now - phaseStarted >= settings.raceBettingSeconds() * 1000L) {
                    winner = rule.winner(random.nextDouble());
                    race = rule.race(winner, random);
                    frame = 0;
                    phase = Phase.RUNNING;
                    phaseStarted = now;
                    bettors().forEach(player -> gambling.sounds().play(player.getUniqueId(), GameSounds.BELL));
                }
            }
            case RUNNING -> {
                int due = (int) ((now - phaseStarted) / FRAME_MILLIS);
                if (due != frame) {
                    frame = due;
                    bettors().forEach(player -> gambling.sounds().play(player.getUniqueId(), GameSounds.GALLOP));
                }
                if (frame >= race.getFirst().size() - 1) {
                    finish();
                }
            }
            case FINISHED -> {
                if (now - phaseStarted >= FINISHED_MILLIS) {
                    phase = Phase.BETTING;
                    phaseStarted = now;
                    bets.clear();
                    race = List.of();
                }
            }
        }
    }

    private List<Player> bettors() {
        List<Player> online = new ArrayList<>();
        bets.keySet().forEach(id -> {
            Player player = server.getPlayer(id);
            if (player != null) {
                online.add(player);
            }
        });
        return online;
    }

    private void finish() {
        phase = Phase.FINISHED;
        phaseStarted = clock.getAsLong();
        String name = HorseRaceRule.NAMES.get(winner);
        for (Map.Entry<UUID, List<Bet>> each : bets.entrySet()) {
            Money staked = Money.ZERO;
            Money payout = Money.ZERO;
            for (Bet bet : each.getValue()) {
                staked = staked.plus(bet.stake());
                if (bet.horse() == winner) {
                    payout = payout.plus(bet.stake().share(pays(winner)));
                }
            }
            gambling.payOut(each.getKey(), staked, payout, "Horse race");
            Player player = server.getPlayer(each.getKey());
            if (player != null) {
                gambling.finish(player, payout.isPositive(), staked, payout, "economy.gamble.race", "horse", name);
            }
        }
    }

    public synchronized boolean bet(Player player, int horse, Money stake) {
        if (phase != Phase.BETTING) {
            gambling.tell(player, "economy.gamble.race-closed");
            return false;
        }
        if (horse < 0 || horse >= rule.horses()) {
            return false;
        }
        if (!gambling.mayBet(player, stake, Game.RACE) || !gambling.takeStake(player, stake,
                "Horse race: " + HorseRaceRule.NAMES.get(horse))) {
            return false;
        }
        bets.computeIfAbsent(player.getUniqueId(), id -> new ArrayList<>()).add(new Bet(horse, stake));
        gambling.sounds().play(player.getUniqueId(), GameSounds.CHIPS);
        return true;
    }

    /** The module stopping: stakes on a race that will never finish go back. */
    public synchronized void refundAll() {
        if (phase == Phase.FINISHED) {
            return;
        }
        bets.forEach((id, list) -> list.forEach(bet -> gambling.payOut(id, bet.stake(), bet.stake(), "Horse race")));
        bets.clear();
    }
}
