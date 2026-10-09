package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Amounts in slices, each slice at its own rate — a progressive payment or wealth tax, and the declining
 * scale a season's points are counted on. Written {@code '<from> <rate>'}.
 */
public final class BracketRule implements IEconomyRule {

    /** From this amount up to the next slice, {@code percent} is due. */
    public record Bracket(Money from, double percent) {
    }

    /** From this amount up to the next slice, each whole coin is worth {@code perCoin} points. */
    public record Rate(Money from, double perCoin) {
    }

    /** Empty when a line cannot be read or a rate is outside {@code 0..100}: a typo must not tax everything away. */
    public Optional<List<Bracket>> parse(List<String> lines, Currency currency) {
        List<Bracket> read = new ArrayList<>();
        for (String[] pair : pairs(lines)) {
            Optional<Money> from = currency.parse(pair[0]);
            Double rate = number(pair[1]);
            if (from.isEmpty() || rate == null || rate < 0 || rate > 100) {
                return Optional.empty();
            }
            read.add(new Bracket(from.get(), rate));
        }
        if (read.size() != (lines == null ? 0 : lines.stream().filter(line -> !line.isBlank()).count())) {
            return Optional.empty();
        }
        read.sort(Comparator.comparing(Bracket::from));
        return Optional.of(List.copyOf(read));
    }

    public Optional<List<Rate>> parseRates(List<String> lines, Currency currency) {
        List<Rate> read = new ArrayList<>();
        for (String[] pair : pairs(lines)) {
            Optional<Money> from = currency.parse(pair[0]);
            Double rate = number(pair[1]);
            if (from.isEmpty() || rate == null || rate < 0) {
                return Optional.empty();
            }
            read.add(new Rate(from.get(), rate));
        }
        if (read.size() != (lines == null ? 0 : lines.stream().filter(line -> !line.isBlank()).count())) {
            return Optional.empty();
        }
        read.sort(Comparator.comparing(Rate::from));
        return Optional.of(List.copyOf(read));
    }

    /** What is due on {@code amount}, slice by slice. Rounded down. */
    public Money tax(Money amount, List<Bracket> brackets) {
        if (amount == null || !amount.isPositive() || brackets.isEmpty()) {
            return Money.ZERO;
        }
        BigDecimal due = BigDecimal.ZERO;
        for (int index = 0; index < brackets.size(); index++) {
            long from = brackets.get(index).from().minor();
            long to = index + 1 < brackets.size() ? brackets.get(index + 1).from().minor() : Long.MAX_VALUE;
            long slice = Math.min(amount.minor(), to) - from;
            if (slice <= 0) {
                continue;
            }
            due = due.add(BigDecimal.valueOf(slice).multiply(BigDecimal.valueOf(brackets.get(index).percent())));
        }
        long minor = due.divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR).longValue();
        return Money.of(Math.min(minor, amount.minor()));
    }

    /** Points for {@code amount}, slice by slice, counted in whole coins. Rounded down. */
    public long points(Money amount, List<Rate> rates, Currency currency) {
        if (amount == null || !amount.isPositive() || rates.isEmpty()) {
            return 0;
        }
        BigDecimal points = BigDecimal.ZERO;
        BigDecimal unit = BigDecimal.valueOf(currency.unit());
        for (int index = 0; index < rates.size(); index++) {
            long from = rates.get(index).from().minor();
            long to = index + 1 < rates.size() ? rates.get(index + 1).from().minor() : Long.MAX_VALUE;
            long slice = Math.min(amount.minor(), to) - from;
            if (slice <= 0) {
                continue;
            }
            points = points.add(BigDecimal.valueOf(slice).multiply(BigDecimal.valueOf(rates.get(index).perCoin()))
                    .divide(unit, 6, RoundingMode.FLOOR));
        }
        return points.setScale(0, RoundingMode.FLOOR).longValue();
    }

    private static List<String[]> pairs(List<String> lines) {
        List<String[]> pairs = new ArrayList<>();
        if (lines == null) {
            return pairs;
        }
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] words = line.strip().split("\\s+");
            if (words.length == 2) {
                pairs.add(words);
            }
        }
        return pairs;
    }

    private static Double number(String text) {
        try {
            double value = Double.parseDouble(text.replace(',', '.').replace("%", ""));
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    @Override
    public String describe() {
        return "what is due on an amount taxed in slices, and a season's points";
    }
}
