package de.raindancer.modules.names;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * Everything a server owner can decide about coloured names, as one record.
 *
 * <h2>Why a record</h2>
 * The record <em>is</em> the schema: {@code config.yml}, its comments, validation, tab completion and
 * the settings GUI are all derived from it, so there is nothing to keep in step and no second copy to
 * fail a test over. The defaults are real Java in {@link #DEFAULTS}, checked by the compiler rather than
 * being untyped literals in a list.
 *
 * <h2>What is deliberately not here</h2>
 * The palette — which item dyes, which toggles a decoration, and which shades. Three maps of unbounded
 * length are not four record components, and a server that adds an item should not have to edit Java. It
 * lives in the same {@code config.yml}, read by {@code store.Palette}; see {@code store.PaletteFile} for
 * why the two can share a file without either overwriting the other.
 *
 * <h2>Why the keys keep their old paths</h2>
 * Every {@link Key} below is the path the standalone plugin has always used, so a server upgrading from
 * {@code RainsColouredNames} keeps its ceiling, its cauldron and its mob names. A renamed key is not an
 * error anybody sees: the setting silently reverts to its default, and the line the owner wrote sits in
 * the file doing nothing.
 */
@Settings(id = "names", topics = {
        @Topic(path = "names", title = "Coloured names", icon = Material.NAME_TAG),
        @Topic(path = "names/crafting", title = "Crafting", icon = Material.CRAFTING_TABLE),
        @Topic(path = "names/world", title = "In the world", icon = Material.WATER_BUCKET),
})
public record NamesSettings(

        // ───────────────────────────────────────────────────────────── crafting

        @In("names/crafting") @Title("Most colours in one name") @Range(min = 1, max = 8)
        @Describe("A crafting table holds nine items and one of them has to be the thing being "
                + "painted, so eight is the ceiling. More tags than this is refused rather than "
                + "quietly cut short — somebody who lays out nine tags has said what they wanted.")
        @Key("max-gradient-stops")
        int maxStops,

        // ───────────────────────────────────────────────────────────── in the world

        @In("names/world") @Title("A water cauldron washes a tag clean")
        @Describe("The way undyeing leather armour and clearing a banner already work, so it is a "
                + "guess a player makes rather than a rule they have to be taught. Off leaves a "
                + "styled tag styled for ever, since nothing else takes a style off one.")
        @Key("wash-in-cauldron")
        boolean washInCauldron,

        @In("names/world") @Title("Naming a mob paints the mob's name too")
        @Describe("Off means a styled tag still names the mob, in plain white — which is what a name "
                + "tag has always done.")
        @Key("colour-mob-names")
        boolean colourMobNames) {

    /**
     * What a server gets before anybody changes anything.
     *
     * <p>Real Java rather than a list of untyped literals, so the compiler checks each one and a renamed
     * component is a build failure rather than a setting that silently reverts.
     */
    public static final NamesSettings DEFAULTS = new NamesSettings(8, true, true);

    /**
     * The ceiling, never below one.
     *
     * <p>Core clamps a number outside the declared range on the way in, so this should never have work
     * to do — but a hand-edited zero reaching {@code CraftRule} would mean "no gradients and no solid
     * colours either", which is the whole feature switched off by a number nobody meant as a switch.
     */
    public int stops() {
        return Math.max(1, Math.min(8, maxStops));
    }

    // ────────────────────────────────────────────────────────────────────────────────────────
    //  The values a caller varies on its own, written out one at a time.
    //
    //  A record has one positional constructor, and anything that spells all three components out
    //  is a mis-ordering waiting to happen — two swapped booleans compile perfectly.
    // ────────────────────────────────────────────────────────────────────────────────────────

    public NamesSettings withMaxStops(int stops) {
        return new NamesSettings(stops, washInCauldron, colourMobNames);
    }

    public NamesSettings withWashInCauldron(boolean wash) {
        return new NamesSettings(maxStops, wash, colourMobNames);
    }

    public NamesSettings withColourMobNames(boolean colour) {
        return new NamesSettings(maxStops, washInCauldron, colour);
    }
}
