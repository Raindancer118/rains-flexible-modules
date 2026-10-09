package de.raindancer.modules.essentials.rules;

import de.raindancer.core.platform.rule.AbstractRule;
import de.raindancer.core.platform.rule.Verdict;

import java.util.Locale;
import java.util.regex.Pattern;

/** Whether a rule's title or text, or a preset's name, is something that can be shown and found again. */
public final class HouseRuleTextRule extends AbstractRule<HouseRuleTextRule.Ask> {

    public static final int TITLE_LIMIT = 40;
    /** Two to three lines of chat — a rule longer than that is two rules. */
    public static final int TEXT_LIMIT = 256;
    public static final int PRESET_LIMIT = 32;

    private static final Pattern PRESET = Pattern.compile("[a-z0-9][a-z0-9-]*");

    public enum Part { TITLE, TEXT, PRESET }

    public record Ask(Part part, String value) {
    }

    public HouseRuleTextRule() {
        super("a rule needs a title and a text of readable length; a preset a plain name");
    }

    /** What somebody typing "Friendly SMP" means: {@code friendly-smp}. */
    public static String presetName(String typed) {
        return typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    @Override
    public Verdict judge(Ask ask) {
        String value = ask.value() == null ? "" : ask.value().strip();
        if (value.isEmpty()) {
            return Verdict.refused("essentials.rules.blank");
        }
        int limit = switch (ask.part()) {
            case TITLE -> TITLE_LIMIT;
            case TEXT -> TEXT_LIMIT;
            case PRESET -> PRESET_LIMIT;
        };
        if (value.length() > limit) {
            return Verdict.refused("essentials.rules.too-long", String.valueOf(limit));
        }
        if (ask.part() == Part.PRESET && !PRESET.matcher(value).matches()) {
            return Verdict.refused("essentials.rules.preset.bad-name", value);
        }
        return Verdict.allowed();
    }
}
