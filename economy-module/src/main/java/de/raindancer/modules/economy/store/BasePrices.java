package de.raindancer.modules.economy.store;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The shipped list of what raw materials are worth — {@code base-prices.yml}, {@code name: value} a line.
 * Read by hand because it is flat: a YAML library would only add the ways {@code no: 1} becomes a boolean.
 */
public final class BasePrices {

    public static final String RESOURCE = "base-prices.yml";

    private BasePrices() {
    }

    public static Map<String, Money> parse(InputStream in, Currency currency) {
        Map<String, Money> read = new LinkedHashMap<>();
        if (in == null) {
            return read;
        }
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                String text = line.strip();
                int colon = text.indexOf(':');
                if (text.isEmpty() || text.startsWith("#") || colon <= 0) {
                    continue;
                }
                String name = text.substring(0, colon).strip().toUpperCase(Locale.ROOT);
                String value = text.substring(colon + 1).strip();
                int comment = value.indexOf('#');
                if (comment >= 0) {
                    value = value.substring(0, comment).strip();
                }
                currency.parse(value).filter(Money::isPositive).ifPresent(money -> read.put(name, money));
            }
        } catch (IOException unreadable) {
            return read;
        }
        return read;
    }
}
