package de.raindancer.modules.names.store;

import de.raindancer.modules.names.model.Reagent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Which item does what to a name tag. <strong>The single source for that.</strong>
 *
 * <p>The crafting rule, the manual and the lore on a styled tag all read this one map, so an admin who
 * changes a line in {@code config.yml} changes every one of them and cannot end up with a help text
 * that describes a recipe the server no longer has.
 *
 * <h2>Why this is not part of {@code NamesSettings}</h2>
 * Because it is three maps of unknown length, and Core's settings are a record: every component is one
 * value with one key, one title and one line in a menu. A hundred-and-something dyes would be a hundred
 * settings, and adding an item would mean editing Java. So the three sections stay hand-read out of the
 * same file — which works because {@code SettingsStore.save} deliberately keeps the keys its schema does
 * not know, so writing a setting from {@code /settings} leaves the palette exactly as the owner wrote
 * it. {@link PaletteFile} is what puts the shipped tables there in the first place.
 *
 * <h2>A dye gives the colour it is</h2>
 * Every dye maps to <em>its own colour</em>, taken from {@link DyeColor} — the same values Minecraft
 * uses for leather armour, concrete and every other dyed thing. Pink dye makes pink.
 *
 * <p>That is not what the standalone plugin shipped with, and the first person to use it took pink dye
 * and got red. The old table mapped the dyes onto Minecraft's sixteen <em>chat</em> colours, so that all
 * sixteen could be reached, which meant pale dyes took bright colours and strong dyes took dark ones. It
 * was defensible on paper and wrong in the hand: nobody picks up pink dye wondering which chat colour it
 * has been assigned to. Reaching darker and lighter shades is what {@link Reagent.Shade} is for, and
 * that reaches far more than sixteen.
 */
public final class Palette {

    /** Shipped defaults. Kept in step with what {@link PaletteFile} writes, by {@code PaletteTest}. */
    private static final Map<Material, String> DEFAULT_COLOURS = defaultColours();

    private static final Map<Material, String> DEFAULT_DECORATIONS = defaultDecorations();

    private static final Map<Material, ShadeSpec> DEFAULT_SHADES = defaultShades();

    /** A configured shade: how far each craft moves, and towards what. */
    public record ShadeSpec(String label, String towards, double step) {
    }

    private final Map<Material, Reagent> reagents;

    private Palette(Map<Material, Reagent> reagents) {
        this.reagents = reagents;
    }

    /** What this server ships with, for a fresh config and for the tests. */
    public static Palette defaults() {
        Map<Material, Reagent> reagents = new EnumMap<>(Material.class);
        DEFAULT_COLOURS.forEach((material, colour) ->
                reagents.put(material, new Reagent.Colour(parseColour(colour), label(material))));
        DEFAULT_DECORATIONS.forEach((material, decoration) ->
                reagents.put(material, new Reagent.Decoration(parseDecoration(decoration))));
        DEFAULT_SHADES.forEach((material, shade) -> reagents.put(material,
                new Reagent.Shade(shade.label(), parseColour(shade.towards()), (float) shade.step())));
        return new Palette(reagents);
    }

    /**
     * Reads {@code colours:}, {@code decorations:} and {@code shades:} out of the config.
     *
     * <p>A line that names an item, colour or decoration that does not exist is reported through
     * {@code warn} and skipped. The rest of the palette still loads: one typo in a sixteen-line colour
     * table should cost the server that one dye, not the whole feature.
     *
     * <p>A file with none of the three sections gives an empty palette rather than the defaults, and
     * deliberately: an owner who deleted every dye meant it. {@link PaletteFile} is what makes sure a
     * <em>fresh</em> file has them, so the two cases are told apart before this is ever reached.
     */
    public static Palette from(ConfigurationSection section, Consumer<String> warn) {
        Map<Material, Reagent> reagents = new EnumMap<>(Material.class);

        ConfigurationSection colours = section.getConfigurationSection("colours");
        if (colours != null) {
            for (String key : colours.getKeys(false)) {
                Material material = material(key);
                if (material == null) {
                    warn.accept("'" + key + "' is not an item, so nothing can be crafted with it. Skipped.");
                    continue;
                }
                TextColor colour = parseColourOrNull(colours.getString(key, ""));
                if (colour == null) {
                    warn.accept("'" + colours.getString(key, "") + "' is not a colour (" + key + "). Skipped.");
                    continue;
                }
                reagents.put(material, new Reagent.Colour(colour, label(material)));
            }
        }

        ConfigurationSection decorations = section.getConfigurationSection("decorations");
        if (decorations != null) {
            for (String key : decorations.getKeys(false)) {
                Material material = material(key);
                if (material == null) {
                    warn.accept("'" + key + "' is not an item, so nothing can be crafted with it. Skipped.");
                    continue;
                }
                String value = decorations.getString(key, "");
                TextDecoration decoration = TextDecoration.NAMES.value(value.toLowerCase(Locale.ROOT));
                if (decoration == null) {
                    warn.accept("'" + value + "' is not a decoration (" + key + "). Skipped.");
                    continue;
                }
                reagents.put(material, new Reagent.Decoration(decoration));
            }
        }

        ConfigurationSection shades = section.getConfigurationSection("shades");
        if (shades != null) {
            for (String key : shades.getKeys(false)) {
                Material material = material(key);
                if (material == null) {
                    warn.accept("'" + key + "' is not an item, so nothing can be crafted with it. Skipped.");
                    continue;
                }
                ConfigurationSection entry = shades.getConfigurationSection(key);
                TextColor towards = entry == null ? null : parseColourOrNull(entry.getString("towards", ""));
                if (towards == null) {
                    warn.accept("Shade '" + key + "' has no readable 'towards' colour. Skipped.");
                    continue;
                }
                double step = entry.getDouble("step", 0.2);
                if (step <= 0 || step >= 1) {
                    warn.accept("Shade '" + key + "' has step " + step
                            + ", which must be between 0 and 1. Using 0.2.");
                    step = 0.2;
                }
                reagents.put(material, new Reagent.Shade(
                        entry.getString("label", label(material)), towards, (float) step));
            }
        }

        return new Palette(reagents);
    }

    /** What {@code material} does to a name tag, or {@code null} if it does nothing. */
    public Reagent reagentFor(Material material) {
        return reagents.get(material);
    }

    /** Every reagent, for the manual and the screen. */
    public Map<Material, Reagent> reagents() {
        return Map.copyOf(reagents);
    }

    /** Whether anything at all can be crafted. An empty palette is a switched-off feature. */
    public boolean isEmpty() {
        return reagents.isEmpty();
    }

    /**
     * Every reagent, colours first, then decorations, then shades.
     *
     * <p>One ordering, used by the screen and by the chat manual alike. Two orderings would be two
     * answers to "what is the third one along", which is exactly how somebody ends up being told to
     * click the wrong button.
     */
    public List<Map.Entry<Material, Reagent>> ordered() {
        List<Map.Entry<Material, Reagent>> entries = new ArrayList<>(reagents.entrySet());
        entries.sort(Comparator
                .comparingInt((Map.Entry<Material, Reagent> entry) -> switch (entry.getValue()) {
                    case Reagent.Colour ignored -> 0;
                    case Reagent.Decoration ignored -> 1;
                    case Reagent.Shade ignored -> 2;
                })
                .thenComparing(entry -> entry.getKey().ordinal()));
        return entries;
    }

    /**
     * What to call a colour that is already on an item.
     *
     * <p>A style read back off a name tag is a bare hex value with no idea which dye made it, so the
     * lore would say {@code #f38baa} unless the palette is asked. An exact match answers "pink"; a
     * colour that has been shaded, or that no dye on this server produces, keeps its hex — which is
     * honest, because there is no dye the player could name it after.
     */
    public String nameOf(TextColor colour) {
        for (Reagent reagent : reagents.values()) {
            if (reagent instanceof Reagent.Colour dyed && dyed.colour().value() == colour.value()) {
                return dyed.label();
            }
        }
        NamedTextColor named = NamedTextColor.namedColor(colour.value());
        return named != null ? NamedTextColor.NAMES.key(named).replace('_', ' ') : colour.asHexString();
    }

    // ------------------------------------------------------------------ parsing

    /**
     * {@code RED_DYE}, {@code red_dye} or {@code minecraft:red_dye}, all the same item.
     *
     * <p>Deliberately {@code Material#getMaterial} and not {@code Material#matchMaterial}, and the air
     * check is against the three constants rather than {@code Material#isAir()}: both of the ones not
     * used here go through the item registry, which does not exist until a server is running, and this
     * class is one the tests have to be able to build without one.
     */
    public static Material material(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String name = key.trim().toUpperCase(Locale.ROOT);
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        Material material = Material.getMaterial(name);
        if (material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
            return null;
        }
        return material;
    }

    /** {@code PINK_DYE} reads as "pink", {@code LAPIS_LAZULI} as "lapis lazuli". */
    public static String label(Material material) {
        String name = material.name().toLowerCase(Locale.ROOT);
        if (name.endsWith("_dye")) {
            name = name.substring(0, name.length() - "_dye".length());
        }
        return name.replace('_', ' ');
    }

    /**
     * The item itself, as somebody would write it: {@code LIGHT_BLUE_DYE} as "Light blue dye".
     *
     * <p>Beside {@link #label} rather than in whatever screen needed it first, because the manual, the
     * chat listing and any lore line that names an item must all read the same — an item called two
     * things is an item nobody can be told to go and get.
     */
    public static String pretty(Material material) {
        String words = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** A named chat colour ({@code dark_red}) or a hex code ({@code #f38baa}). */
    public static TextColor parseColourOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("#")) {
            return TextColor.fromHexString(trimmed);
        }
        return NamedTextColor.NAMES.value(trimmed.toLowerCase(Locale.ROOT));
    }

    private static TextColor parseColour(String value) {
        TextColor colour = parseColourOrNull(value);
        if (colour == null) {
            throw new IllegalStateException("Built-in default colour '" + value + "' is not a colour.");
        }
        return colour;
    }

    private static TextDecoration parseDecoration(String value) {
        TextDecoration decoration = TextDecoration.NAMES.value(value.toLowerCase(Locale.ROOT));
        if (decoration == null) {
            throw new IllegalStateException("Built-in default '" + value + "' is not a decoration.");
        }
        return decoration;
    }

    // ------------------------------------------------------------------ the shipped tables

    /**
     * Every dye, mapped to the colour Minecraft itself gives it.
     *
     * <p>Read out of {@link DyeColor} rather than typed in, so the table cannot drift from the game and
     * a new dye would arrive with the right colour already. The dye's item is its {@code DyeColor} name
     * plus {@code _DYE}, which is true for all sixteen.
     */
    private static Map<Material, String> defaultColours() {
        Map<Material, String> colours = new LinkedHashMap<>();
        for (DyeColor dye : DyeColor.values()) {
            Material item = Material.getMaterial(dye.name() + "_DYE");
            if (item != null) {
                colours.put(item, hex(dye));
            }
        }
        return colours;
    }

    private static String hex(DyeColor dye) {
        return String.format("#%02x%02x%02x",
                dye.getColor().getRed(), dye.getColor().getGreen(), dye.getColor().getBlue());
    }

    /**
     * The five decorations, each on the cheapest item that says what it does.
     *
     * <p>An iron ingot for <b>bold</b> because it is the heavy one, a stick for <i>italic</i> because it
     * leans, string for <u>underline</u> because it is a line, shears for <s>strikethrough</s> because
     * they cut, and an ender pearl for the obfuscated scramble. Deleting a line here removes that recipe
     * entirely — which is how a server turns off obfuscated names, since a name nobody can read is a
     * real thing to want to forbid.
     */
    private static Map<Material, String> defaultDecorations() {
        Map<Material, String> decorations = new LinkedHashMap<>();
        decorations.put(Material.IRON_INGOT, "bold");
        decorations.put(Material.STICK, "italic");
        decorations.put(Material.STRING, "underlined");
        decorations.put(Material.SHEARS, "strikethrough");
        decorations.put(Material.ENDER_PEARL, "obfuscated");
        return decorations;
    }

    /** Coal darkens, glowstone dust lightens. A fifth of the way per craft, so it is a dial. */
    private static Map<Material, ShadeSpec> defaultShades() {
        Map<Material, ShadeSpec> shades = new LinkedHashMap<>();
        shades.put(Material.COAL, new ShadeSpec("darker", "#000000", 0.2));
        shades.put(Material.GLOWSTONE_DUST, new ShadeSpec("lighter", "#ffffff", 0.2));
        return shades;
    }

    /** The shipped tables, for {@link PaletteFile} and for the test that keeps the two honest. */
    public static Map<Material, String> shippedColours() {
        return new LinkedHashMap<>(DEFAULT_COLOURS);
    }

    public static Map<Material, String> shippedDecorations() {
        return new LinkedHashMap<>(DEFAULT_DECORATIONS);
    }

    public static Map<Material, ShadeSpec> shippedShades() {
        return new LinkedHashMap<>(DEFAULT_SHADES);
    }
}
