package de.raindancer.modules.cosmetics.model;

import de.raindancer.core.ui.choose.Swatch;
import de.raindancer.core.ui.text.NameStyle;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Everything this server offers: the palette and the presets, in the order the config lists them.
 * Replaced whole on reload, never edited.
 */
public record Catalogue(List<PaletteColour> palette, List<Preset> presets) {

    public static final Catalogue EMPTY = new Catalogue(List.of(), List.of());

    public Catalogue {
        palette = List.copyOf(palette);
        presets = List.copyOf(presets);
    }

    public Optional<Preset> preset(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String wanted = id.toLowerCase(Locale.ROOT);
        return presets.stream().filter(preset -> preset.id().equals(wanted)).findFirst();
    }

    /** The preset this exact style came from, so a preset can be judged as one rather than by its parts. */
    public Optional<Preset> presetMatching(NameStyle style) {
        return presets.stream().filter(preset -> preset.style().equals(style)).findFirst();
    }

    /** A swatch by its label; {@code light_blue} and {@code Light Blue} both find "light blue". */
    public Optional<PaletteColour> colourNamed(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String wanted = typed.trim().replace('_', ' ').toLowerCase(Locale.ROOT);
        return palette.stream().filter(swatch -> swatch.label().equals(wanted)).findFirst();
    }

    /** The palette as Core's style editor wants it. */
    public List<Swatch> swatches() {
        return palette.stream().map(swatch -> new Swatch(swatch.label(), swatch.colour(), swatch.icon())).toList();
    }

    public boolean inPalette(TextColor colour) {
        return palette.stream().anyMatch(swatch -> swatch.colour().value() == colour.value());
    }

    public Optional<PaletteColour> swatchOf(TextColor colour) {
        return palette.stream().filter(swatch -> swatch.colour().value() == colour.value()).findFirst();
    }

    /** "pink" for a palette colour, "gold" for a chat colour, the hex code for anything else. */
    public String nameOf(TextColor colour) {
        Optional<PaletteColour> swatch = swatchOf(colour);
        if (swatch.isPresent()) {
            return swatch.get().label();
        }
        NamedTextColor named = NamedTextColor.namedColor(colour.value());
        return named != null ? NamedTextColor.NAMES.key(named).replace('_', ' ') : colour.asHexString();
    }
}
