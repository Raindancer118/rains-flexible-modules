package de.raindancer.modules.cosmetics.model;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

/** One swatch in the colour picker: what it is called, what it is, and what it is drawn as. */
public record PaletteColour(String label, TextColor colour, Material icon) {
}
