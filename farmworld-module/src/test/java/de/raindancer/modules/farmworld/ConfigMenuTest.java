package de.raindancer.modules.farmworld;

import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.SettingsSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That every button on the admin config page writes a setting that exists.
 *
 * <h2>Why this is worth a test of its own</h2>
 * Because the page addresses the settings by their key as a string — {@code "bring-radius"} — and a string that
 * does not match a key is refused by the store. The refusal is even said out loud, which makes it worse rather
 * than better: the button looks like it works, tells the admin the setting "would not take that value", and the
 * only wrong thing is the spelling on this side.
 *
 * <p>It is also exactly what a rename breaks. Changing a {@code @Key} in {@link FarmWorldSettings} is a one-word
 * edit that leaves the record, the file, the {@code /settings} tree and the migration all correct, and quietly
 * disconnects one button on one page.
 */
class ConfigMenuTest {

    private static final Path CONFIG_MENU = Path.of(
            "src/main/java/de/raindancer/modules/farmworld/screen/FarmWorldConfigMenu.java");

    /** A key handed to the store directly: the first argument of a cycle, a step or a write. */
    private static final Pattern ADDRESSED_DIRECTLY =
            Pattern.compile("(?:cycle|step|write)\\(\"([a-z0-9-]+)\"");

    /**
     * A key handed to the {@code toggle} helper, which takes it after the value it is showing.
     *
     * <p>A second pattern rather than a cleverer single one. A scan that missed these would report four settings
     * as unreachable when every one of them has a button — the switches simply address the store one call
     * deeper — and a scan that is wrong in that direction is the worse kind: it sends somebody looking for a bug
     * in working code.
     */
    private static final Pattern ADDRESSED_BY_A_SWITCH =
            Pattern.compile("now\\.\\w+\\(\\),\\s*\"([a-z0-9-]+)\"");

    /**
     * A key handed to the {@code amount} helper, which opens Core's chooser.
     *
     * <p>The third route, for the four settings whose range is too wide to nudge. It takes the key third, after
     * the band and the column.
     */
    private static final Pattern ADDRESSED_BY_A_CHOOSER =
            Pattern.compile("amount\\(MenuLayout\\.\\w+, \\d+, \"([a-z0-9-]+)\"");

    private static String source() {
        try {
            return Files.readString(CONFIG_MENU);
        } catch (IOException unreadable) {
            throw new AssertionError("the config page is gone", unreadable);
        }
    }

    /** Every setting key the page tries to write, by any of the three routes. */
    private static Set<String> addressed() {
        Set<String> keys = new LinkedHashSet<>();
        String body = source();
        for (Pattern pattern : List.of(ADDRESSED_DIRECTLY, ADDRESSED_BY_A_SWITCH,
                ADDRESSED_BY_A_CHOOSER)) {
            Matcher matcher = pattern.matcher(body);
            while (matcher.find()) {
                keys.add(matcher.group(1));
            }
        }
        return keys;
    }

    /** Every key the settings actually have, as the store knows them. */
    private static Set<String> real() {
        return new LinkedHashSet<>(
                SettingsSchema.of(FarmWorldSettings.class, FarmWorldSettings.DEFAULTS).keys());
    }

    @Test
    @DisplayName("the scan found buttons, so a refactor cannot quietly empty this")
    void theScanIsNotVacuous() {
        assertThat(addressed())
                .as("no setting keys were found on the page at all")
                .hasSizeGreaterThan(5);
        assertThat(real()).hasSize(FarmWorldSettings.class.getRecordComponents().length);
    }

    @Test
    @DisplayName("every key the page writes is a key the settings have")
    void everyButtonAddressesSomethingReal() {
        List<String> unknown = new ArrayList<>();
        Set<String> real = real();
        for (String key : addressed()) {
            if (!real.contains(key)) {
                unknown.add(key);
            }
        }
        assertThat(unknown)
                .as("the store refuses a key it does not know, so each of these is a button that tells the "
                        + "admin their value was rejected when the only wrong thing is the spelling on the page")
                .isEmpty();
    }

    @Test
    @DisplayName("every setting has a button, so nothing is only reachable by editing the file")
    void everySettingIsOnThePage() {
        // The page is the reason it exists: an admin who has to open config.yml to change one of twelve settings
        // has a page that is nearly useful, which is the sort of thing nobody notices is missing until they need
        // it.
        List<String> unreachable = new ArrayList<>();
        Set<String> addressed = addressed();
        for (String key : real()) {
            if (!addressed.contains(key)) {
                unreachable.add(key);
            }
        }
        assertThat(unreachable)
                .as("these can only be changed by editing config.yml or through the whole server's /settings "
                        + "tree")
                .isEmpty();
    }

    @Test
    @DisplayName("the keys on the page are the ones in the record, not the component names")
    void theKeysAreTheConfigPaths() {
        // The trap this closes. The record component is bringRadius and the key is bring-radius, and both look
        // right at a glance — but only one of them is what the store answers to.
        List<String> componentNames = new ArrayList<>();
        for (RecordComponent component : FarmWorldSettings.class.getRecordComponents()) {
            componentNames.add(component.getName());
        }

        assertThat(addressed())
                .as("a component name is not a settings key")
                .doesNotContainAnyElementsOf(componentNames);
    }

    @Test
    @DisplayName("no two buttons are asked for in the same place")
    void nothingLandsOnTopOfAnythingElse() {
        // MenuLayout clamps a column to seven, silently. A page that asked for an eighth button would draw it on
        // top of the seventh: one setting simply unreachable, with nothing on screen or in the log to say which.
        Pattern placed = Pattern.compile("MenuLayout\\.(WHO|RULES|LAND), (\\d+)");
        List<String> places = new ArrayList<>();
        Matcher matcher = placed.matcher(source());
        while (matcher.find()) {
            places.add(matcher.group(1) + " " + matcher.group(2));
        }

        assertThat(places).as("no buttons were found at all").hasSizeGreaterThan(8);
        assertThat(places).doesNotHaveDuplicates();
        for (String place : places) {
            int column = Integer.parseInt(place.split(" ")[1]);
            assertThat(column)
                    .as("%s is outside the seven columns a band has, so it would be clamped on top of another "
                            + "button", place)
                    .isBetween(1, 7);
        }
    }

    @Test
    @DisplayName("each key is spelled the same on the page as in the record")
    void thePageAndTheRecordAgree() {
        for (RecordComponent component : FarmWorldSettings.class.getRecordComponents()) {
            String key = component.getAnnotation(Key.class).value();
            assertThat(addressed())
                    .as("%s is configured at %s, and the page does not write that key",
                            component.getName(), key)
                    .contains(key);
        }
    }

    @Test
    @DisplayName("the chooser is given the range the setting actually allows")
    void theChooserCannotOfferARefusedValue() {
        // A chooser whose maximum is above the schema's would let an admin accept a number the store then
        // refuses — the one shape of button that looks like it worked and did not. Checked against the two that
        // are easiest to get wrong, because their limits are written as constants elsewhere.
        String body = source();

        assertThat(body)
                .as("the scatter radii are bounded by Scatter's own limits, not by numbers typed here twice")
                .contains("Scatter.FURTHEST_ALLOWED")
                .contains("Scatter.NEAREST_ALLOWED");
    }
}
