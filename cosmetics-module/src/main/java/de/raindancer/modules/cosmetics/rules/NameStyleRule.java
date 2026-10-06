package de.raindancer.modules.cosmetics.rules;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.model.Grants;
import de.raindancer.modules.cosmetics.model.Preset;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;
import java.util.Optional;

/**
 * Whether somebody may wear a name style. The only place that answers it: the screens grey buttons with
 * it, the command refuses with it, and the join check takes a lost perk away with it.
 *
 * <p>Refusals are message keys, the detail is what to put in {@code <detail>}.
 */
public final class NameStyleRule implements ICosmeticsRule {

    public Verdict judge(NameStyle style, Grants grants, Catalogue catalogue, int maxStops) {
        if (style.isEmpty()) {
            // Taking a style off is never refused, or somebody who lost a rank could not undo it.
            return Verdict.allowed();
        }
        Optional<Preset> preset = catalogue.presetMatching(style);
        if (preset.isPresent() && mayUse(preset.get(), grants)) {
            return Verdict.allowed();
        }
        int ceiling = Math.max(1, Math.min(NameStyle.MAX_STOPS, maxStops));
        if (style.colours().size() > ceiling) {
            return Verdict.refused("cosmetics.refused.too-many-stops", ceiling);
        }
        if (style.colours().size() == 1 && !grants.colour()) {
            return Verdict.refused("cosmetics.refused.colour");
        }
        if (style.isGradient() && !grants.gradient()) {
            return Verdict.refused("cosmetics.refused.gradient");
        }
        if (!grants.anyColour()) {
            for (TextColor colour : style.colours()) {
                if (!catalogue.inPalette(colour)) {
                    return Verdict.refused("cosmetics.refused.any-colour", catalogue.nameOf(colour));
                }
            }
        }
        for (TextDecoration decoration : style.decorations()) {
            if (!grants.decorations().contains(decoration)) {
                return Verdict.refused("cosmetics.refused.decoration",
                        decoration.name().toLowerCase(Locale.ROOT));
            }
        }
        // A restricted preset worn without its node lands here too, and passes if every part of it
        // would be allowed on its own — refusing it would only make somebody rebuild it by hand.
        return Verdict.allowed();
    }

    /** A public preset is for everybody; a restricted one for whoever holds its node. */
    public boolean mayUse(Preset preset, Grants grants) {
        return !preset.restricted() || grants.presets().contains(preset.id());
    }

    @Override
    public String describe() {
        return "who may wear which name style";
    }
}
