package de.raindancer.modules.roles.model;

import de.raindancer.core.social.economy.TradeSide;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.choose.Category;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One thing a role is good at: so many percent on one side of the shop, for some items.
 *
 * @param percent    negative is cheaper when buying, positive pays more when selling
 * @param categories the shop's drawers it covers, whole
 * @param items      items it covers by name; a {@code *} stands for anything ({@code *_LOG})
 * @param except     items it never covers, though a category or pattern would
 * @param text       what the role menu says about it, or empty to have it written from the rest
 */
public record Perk(TradeSide side, int percent, List<Category> categories, List<String> items, List<String> except,
                   String text) {

    private static final int NAMED = 4;

    public Perk {
        categories = List.copyOf(categories);
        items = items.stream().map(each -> each.toUpperCase(Locale.ROOT)).toList();
        except = except.stream().map(each -> each.toUpperCase(Locale.ROOT)).toList();
        text = text == null ? "" : text;
    }

    public boolean covers(String material) {
        if (material == null) {
            return false;
        }
        String name = material.toUpperCase(Locale.ROOT);
        if (except.stream().anyMatch(pattern -> matches(pattern, name))) {
            return false;
        }
        return items.stream().anyMatch(pattern -> matches(pattern, name))
                || categories.contains(Catalogue.categoryOf(name));
    }

    public static boolean matches(String pattern, String material) {
        String regex = java.util.Arrays.stream(pattern.toUpperCase(Locale.ROOT).split("\\*", -1))
                .map(java.util.regex.Pattern::quote).reduce((a, b) -> a + ".*" + b).orElse("");
        return material.toUpperCase(Locale.ROOT).matches(regex);
    }

    /** "25% off buying Food, Smoker" — for the role menu. */
    public String says() {
        if (!text.isBlank()) {
            return text;
        }
        List<String> names = new ArrayList<>();
        categories.forEach(category -> names.add(category.title()));
        items.forEach(item -> names.add(item.contains("*") ? describePattern(item) : Catalogue.readable(item)));
        String what = String.join(", ", names.subList(0, Math.min(NAMED, names.size())))
                + (names.size() > NAMED ? " and " + (names.size() - NAMED) + " more" : "");
        return Math.abs(percent) + "% " + (side == TradeSide.BUY ? (percent < 0 ? "off" : "more") + " buying "
                : (percent > 0 ? "more" : "less") + " selling ") + what;
    }

    private static String describePattern(String pattern) {
        String words = Catalogue.readable(pattern.replace("*", "").replaceAll("^_|_$", ""));
        return "any " + words.toLowerCase(Locale.ROOT);
    }
}
