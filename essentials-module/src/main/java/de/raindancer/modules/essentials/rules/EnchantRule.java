package de.raindancer.modules.essentials.rules;

import de.raindancer.core.platform.rule.AbstractRule;
import de.raindancer.core.platform.rule.Verdict;

import java.util.Optional;
import java.util.Set;

/**
 * Whether an enchantment may be put on, or taken off, what somebody is holding.
 *
 * <p>Takes no server: whether it fits and what the vanilla maximum is are worked out by the service
 * and handed over as facts, so every combination of permissions is a one-line test.
 *
 * <h2>Why 255</h2>
 * An enchantment level is a signed byte-sized number in practice — past 255 the game stores something
 * that no longer round-trips, so the ceiling is enforced here whatever a player is permitted.
 */
public final class EnchantRule extends AbstractRule<EnchantRule.Request> {

    public static final int MAX_LEVEL = 255;

    private static final Set<String> REMOVAL_WORDS = Set.of("remove", "off", "none", "clear", "strip");

    public EnchantRule() {
        super("an enchantment is put on within 1 to 255, past the vanilla maximum and onto items it does "
                + "not fit only with the matching permissions, and only taken off if it is there");
    }

    /**
     * @param holding    whether the target holds anything at all
     * @param self       whether the one enchanting is the one holding it
     * @param mayOthers  holds the node for enchanting somebody else's item
     * @param level      what was asked for; 0 means take it off
     * @param vanillaMax the highest level the game itself gives this enchantment
     * @param fitsItem   whether the game would normally allow it on this item
     * @param present    whether the item already has this enchantment
     */
    public record Request(boolean holding, boolean self, boolean mayOthers, int level, int vanillaMax,
                          boolean fitsItem, boolean present, boolean mayBeyondMax, boolean mayAnyItem) {

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private boolean holding;
            private boolean self;
            private boolean mayOthers;
            private int level;
            private int vanillaMax = 1;
            private boolean fitsItem;
            private boolean present;
            private boolean mayBeyondMax;
            private boolean mayAnyItem;

            public Builder holding(boolean value) {
                holding = value;
                return this;
            }

            public Builder self(boolean value) {
                self = value;
                return this;
            }

            public Builder mayOthers(boolean value) {
                mayOthers = value;
                return this;
            }

            public Builder level(int value) {
                level = value;
                return this;
            }

            public Builder vanillaMax(int value) {
                vanillaMax = value;
                return this;
            }

            public Builder fitsItem(boolean value) {
                fitsItem = value;
                return this;
            }

            public Builder present(boolean value) {
                present = value;
                return this;
            }

            public Builder mayBeyondMax(boolean value) {
                mayBeyondMax = value;
                return this;
            }

            public Builder mayAnyItem(boolean value) {
                mayAnyItem = value;
                return this;
            }

            public Request build() {
                return new Request(holding, self, mayOthers, level, vanillaMax, fitsItem, present,
                        mayBeyondMax, mayAnyItem);
            }
        }
    }

    @Override
    public Verdict judge(Request request) {
        if (!request.self() && !request.mayOthers()) {
            return Verdict.refused("essentials.enchant.not-others");
        }
        if (!request.holding()) {
            return Verdict.refused("essentials.enchant.nothing-held");
        }
        if (request.level() < 0 || request.level() > MAX_LEVEL) {
            return Verdict.refused("essentials.enchant.level-range");
        }
        if (request.level() == 0) {
            return request.present() ? Verdict.allowed() : Verdict.refused("essentials.enchant.not-on-item");
        }
        if (!request.fitsItem() && !request.mayAnyItem()) {
            return Verdict.refused("essentials.enchant.wrong-item");
        }
        if (request.level() > request.vanillaMax() && !request.mayBeyondMax()) {
            return Verdict.refused("essentials.enchant.too-high", request.vanillaMax());
        }
        return Verdict.allowed();
    }

    /** The highest level a chooser should offer somebody. */
    public static int ceiling(int vanillaMax, boolean mayBeyondMax) {
        return mayBeyondMax ? MAX_LEVEL : Math.max(1, vanillaMax);
    }

    /**
     * What somebody typed as a level: a number (any size, so the range refusal can name it), or one of
     * the words for taking it off. Empty for anything else, which lets a player name follow.
     */
    public static Optional<Integer> parseLevel(String typed) {
        if (typed == null || typed.isBlank()) {
            return Optional.empty();
        }
        String word = typed.strip().toLowerCase(java.util.Locale.ROOT);
        if (REMOVAL_WORDS.contains(word)) {
            return Optional.of(0);
        }
        if (!word.matches("-?\\d+")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(word));
        } catch (NumberFormatException tooBig) {
            return Optional.of(word.startsWith("-") ? Integer.MIN_VALUE : Integer.MAX_VALUE);
        }
    }
}
