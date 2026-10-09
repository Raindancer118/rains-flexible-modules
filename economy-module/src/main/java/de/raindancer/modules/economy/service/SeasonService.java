package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.SupplySettings;
import de.raindancer.modules.economy.rules.BracketRule;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.store.SupplyBook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Seasons: staff end one, every balance becomes season points on a declining scale, and everybody starts the
 * next one at the starting balance plus the share kept. Nothing happens on its own. Talks to the database.
 */
public final class SeasonService implements IEconomyService {

    /** How a season ended: its number, how many accounts scored, and the points handed out. */
    public record Ended(int season, int accounts, long points) {
    }

    private final AccountBook book;
    private final SupplyBook store;
    private final SupplyService supply;
    private final BracketRule rule = new BracketRule();
    private volatile EconomySettings settings;

    public SeasonService(AccountBook book, SupplyBook store, SupplyService supply, EconomySettings settings) {
        this.book = book;
        this.store = store;
        this.supply = supply;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    public int current() {
        return store.season();
    }

    /**
     * Ends the season.
     *
     * @return empty when seasons are off, the points scale cannot be read, or a capped treasury cannot pay
     *         everybody's starting balance
     */
    public synchronized Optional<Ended> end() {
        SupplySettings live = SupplyService.settingsOf(supply);
        if (!live.seasons()) {
            return Optional.empty();
        }
        Currency currency = settings.currency();
        Optional<List<BracketRule.Rate>> scale = rule.parseRates(live.seasonBrackets(), currency);
        if (scale.isEmpty()) {
            return Optional.empty();
        }
        double kept = Math.clamp(live.seasonKeepPercent(), 0, 100) / 100.0;
        Money starting = settings.starting();
        int season = store.season();
        Map<UUID, Money> had = book.endSeason(balance -> starting.plus(balance.share(kept)), "Season " + season
                + " ended");
        if (had.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, Long> points = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<UUID, Money> each : had.entrySet()) {
            Money converted = each.getValue().minus(each.getValue().share(kept)).max(Money.ZERO);
            long scored = rule.points(converted, scale.get(), currency);
            if (scored > 0) {
                points.put(each.getKey(), scored);
                total += scored;
            }
        }
        store.endSeason(season, points);
        book.flush();
        return Optional.of(new Ended(season, points.size(), total));
    }

    public Map<Integer, Long> pointsOf(UUID player) {
        return store.pointsOf(player);
    }
}
