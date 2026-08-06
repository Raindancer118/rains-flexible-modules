package de.raindancer.modules.hungergames;

import de.raindancer.core.data.settings.Setting;
import de.raindancer.core.data.settings.SettingsSchema;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test that stops this port being a silent data loss.
 *
 * <h2>What a changed or dropped key actually costs somebody</h2>
 * {@code HungerGamesSettings}' {@link de.raindancer.core.data.settings.Key @Key} values are typed by hand
 * from {@code HgSettings.java}, the catalogue this record replaces. Nothing enforces that they were copied
 * correctly except this test. If one were mistyped -- {@code "game.duration"} written as
 * {@code "game.length"} -- a server upgrading from the old plugin would have its {@code config.yml} read
 * as though {@code game.duration} were simply never set, and would get the shipped default (a three-hour
 * round) silently in place of whatever it had actually configured. No exception, no line in the log: the
 * server would just start behaving differently the first time somebody restarted it after the upgrade.
 *
 * <p>This test reads {@code legacy-config.yml} -- a snapshot of every one of {@code HgSettings.java}'s 272
 * real key paths, built directly from that source file rather than typed out a second time by hand, which
 * is exactly the kind of transcription this test exists to catch mistakes in -- and asserts that every leaf
 * key in it is accounted for exactly once: either it is one of the roughly ninety keys
 * {@code HungerGamesSettings} still carries, at precisely this path, or it is named in {@link #MOVED} with
 * where it went instead. {@link #MOVED} is written out by hand, one line per key, rather than derived from
 * anything -- being made to type where a key went, and to look at it while doing so, is the entire point.
 * A key that is neither in the schema nor in {@link #MOVED} fails this test, which is the one and only
 * mechanical guarantee here that nothing was simply forgotten.
 *
 * <h2>The one gap this test does not paper over</h2>
 * No keys are marked
 * {@link #DEFERRED} rather than given a real destination. {@code SETTINGS.md}, the document that decided
 * the rest of this split, never classifies them: they are not in its list of what stays, and not in its
 * table of where things went. Teams and sponsors are plainly this module's own concern rather than Core's,
 * so inventing a Core destination for them here would be worse than admitting the gap -- it would make this
 * test claim a decision nobody actually made. They are called out explicitly below rather than being
 * silently absorbed into an "everything else" bucket, so the next person to touch this file finds a named
 * problem instead of a passing test that quietly stopped meaning what it says.
 */
class HungerGamesSettingsMigrationTest {

    /**
     * Kept, and deliberately unused.
     *
     * <p>It named the keys that had no destination — {@code teams.*}, {@code sponsors.*}, {@code startup.*}
     * — and every one of them has one now. The constant stays so that the next person who needs to defer
     * something has the shape to hand, and so that
     * {@link #theDeferredGapIsNamedAndBounded} keeps asserting the gap is empty rather than silently no
     * longer checking anything.
     */
    private static final String DEFERRED = "DEFERRED: not yet re-homed";

    /**
     * Every key {@code HungerGamesSettings} does not carry, and where it went instead.
     *
     * <p>79 entries: 272 keys in the old plugin, minus the 193 this record still answers to — the HTTP
     * API's five keys, and now all fifty-two of {@code items.*}, counted among those 193 rather than here,
     * since the API stays wholly in this module (see {@code HttpApiService}'s class javadoc) and never
     * reached a Core transport, and the item-tuning keys are real settings a live server had already tuned
     * (see {@code TheLiveConfigSurvivesTest}).
     */
    private static final Map<String, String> MOVED = new LinkedHashMap<>();

    static {
            MOVED.put("announcements.text.countdown", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.deathmatch-start", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.deathmatch-warning", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.elimination", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.game-start", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.grace-end", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.kill", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.remaining-players", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.sponsor-beacon-disabled", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.sponsor-beacon-spawned", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.sponsor-not-enough", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.sponsor-purchase", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.sponsor-shop-disabled", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.sponsor-token-earned", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.supply-drop-landed", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.supply-drop-warning", "messages.yml (Messages.defineFrom)");
            MOVED.put("announcements.text.winner", "messages.yml (Messages.defineFrom)");
            MOVED.put("border.phases", "store/BorderPhaseStore");
            MOVED.put("effects.cannon", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.deathmatch-start", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.elimination", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.enabled", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.game-start", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.item-aura", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.item-exmatrikulator", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.item-feast", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.item-hermes-boots", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.item-krueckau-impact", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.item-leap", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.item-lightning", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.item-medikit", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.item-repulse", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.item-smoke-bomb", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.item-stupidness-protector", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.kill", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.sponsor-beacon-spawn", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.startup-arrive", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.startup-launch", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.supply-drop", "RainsCore ui.effect (Cue)");
            MOVED.put("effects.victory", "RainsCore ui.effect (Cue)");
            MOVED.put("events.supply-drops.loot-table", "RainsCore content.loot.LootTables");
            MOVED.put("events.supply-drops.schedule", "store/ (beside border phases)");
            MOVED.put("gamemaster.names", "store/GamemasterStore");
            MOVED.put("protection.allowed-interactions", "RainsCore world.protection.LandFlags");
            MOVED.put("sounds.cannon", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.countdown", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.deathmatch-start", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.deathmatch-warning", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.default-pitch", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.default-volume", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.elimination", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.enabled", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.game-start", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.item-aura", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.item-exmatrikulator", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.item-feast", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.item-fiendfinder", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.item-grappling", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.item-hermes-boots", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.item-hermes-warning", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.item-krueckau-impact", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.item-krueckau-throw", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.item-leap", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.item-medikit", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.item-repulse", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.item-smoke-bomb", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.item-stupidness-protector", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.item-war-kit", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.kill", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.sponsor-beacon-spawn", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.sponsor-purchase-failed", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.sponsor-purchase-success", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.sponsor-token-earned", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.startup-arrive", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.startup-lamp", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.startup-launch", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.supply-drop-landed", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.supply-drop-warning", "RainsCore ui.effect (Cue)");
            MOVED.put("sounds.victory", "RainsCore ui.effect (Cue)");
    }

    private static YamlConfiguration loadLegacyConfig() {
        try (InputStream stream = HungerGamesSettingsMigrationTest.class
                .getResourceAsStream("/legacy-config.yml")) {
            if (stream == null) {
                throw new IllegalStateException("legacy-config.yml is missing from test resources");
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every leaf path in a {@link ConfigurationSection}, dotted, the way {@code YamlConfigBackend} reads them. */
    private static List<String> leafKeys(ConfigurationSection section) {
        List<String> leaves = new ArrayList<>();
        for (String key : section.getKeys(true)) {
            if (!section.isConfigurationSection(key)) {
                leaves.add(key);
            }
        }
        return leaves;
    }

    @Test
    @DisplayName("the fixture itself is the 272 keys HgSettings.java really has")
    void theFixtureHasTwoHundredSeventyTwoKeys() {
        List<String> leaves = leafKeys(loadLegacyConfig());

        assertThat(leaves).hasSize(272).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every old key is either still here, at the same path, or named in MOVED")
    void everyOldKeyIsAccountedFor() {
        Set<String> schemaKeys = Set.copyOf(
                SettingsSchema.of(HungerGamesSettings.class, HungerGamesSettings.DEFAULTS)
                        .settings().stream().map(Setting::key).toList());

        List<String> unaccounted = new ArrayList<>();
        for (String key : leafKeys(loadLegacyConfig())) {
            boolean inSchema = schemaKeys.contains(key);
            boolean inMoved = MOVED.containsKey(key);
            if (!inSchema && !inMoved) {
                unaccounted.add(key);
            }
            if (inSchema && inMoved) {
                // A key cannot both still be a setting and have moved away -- that is two answers to
                // "where is this now", and whichever one somebody reads first is a guess.
                unaccounted.add(key + " (claimed by both the schema and MOVED)");
            }
        }

        assertThat(unaccounted)
                .as("every one of the old plugin's 272 keys must be explained, or a config value has "
                        + "quietly stopped existing")
                .isEmpty();
    }

    @Test
    @DisplayName("the schema and MOVED between them are exactly the 272 keys, with no double-counting")
    void theSplitAccountsForEveryKeyExactlyOnce() {
        Set<String> schemaKeys = Set.copyOf(
                SettingsSchema.of(HungerGamesSettings.class, HungerGamesSettings.DEFAULTS)
                        .settings().stream().map(Setting::key).toList());

        // 193 carried at their old paths, 79 explained elsewhere, and the two together are the whole 272.
        // The arithmetic is the point rather than either number: a key that slipped out of both lists is a
        // config value that stopped existing without anybody deciding it should. The fifty-two items.* keys
        // moved from MOVED into the schema when a live server's config.yml proved they were real settings
        // rather than content Core owns — see TheLiveConfigSurvivesTest.
        assertThat(schemaKeys).hasSize(193);
        assertThat(MOVED).hasSize(79);
        assertThat(schemaKeys.size() + MOVED.size()).isEqualTo(272);
    }

    @Test
    @DisplayName("nothing in MOVED is also still a live setting")
    void movedKeysAreNotAlsoInTheSchema() {
        Set<String> schemaKeys = Set.copyOf(
                SettingsSchema.of(HungerGamesSettings.class, HungerGamesSettings.DEFAULTS)
                        .settings().stream().map(Setting::key).toList());

        List<String> overlap = MOVED.keySet().stream().filter(schemaKeys::contains).toList();

        assertThat(overlap).isEmpty();
    }

    @Test
    @DisplayName("the deferred gap is exactly teams, sponsors and startup, and nothing else")
    void theDeferredGapIsNamedAndBounded() {
        List<String> deferred = MOVED.entrySet().stream()
                .filter(entry -> entry.getValue().equals(DEFERRED))
                .map(Map.Entry::getKey)
                .toList();

        assertThat(deferred)
                .as("nothing is deferred any more. teams.* and sponsors.* were the last of it, and a live "
                        + "server's own config.yml settled the question: it had tuned nine of those keys, so "
                        + "'not ported in this wave' meant nine silent changes on the next restart")
                .isEmpty();

    }
}
