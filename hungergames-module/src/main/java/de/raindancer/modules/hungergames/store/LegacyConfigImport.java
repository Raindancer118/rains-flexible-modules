package de.raindancer.modules.hungergames.store;

import de.raindancer.core.data.settings.Setting;
import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reading a {@code config.yml} written by the old standalone plugin.
 *
 * <h2>Why this is needed at all, when the keys did not change</h2>
 * Most of them did not — {@code HungerGamesSettings} deliberately keeps the old paths, and
 * {@code HungerGamesSettingsMigrationTest} is what proves all 272 of them are accounted for. So a server
 * that drops its old file into the module's folder is <em>mostly</em> understood already.
 *
 * <p>Mostly is the problem. Around ninety keys survive at their old paths and the rest went somewhere else:
 * the announcement texts became {@code messages.yml} entries, the loot tables became their own file, the
 * protection interaction list became RainsCore's {@code LandFlags}. A server that copies its old file across
 * and restarts gets the ninety and silently loses its wording, its loot and its protections — with no error,
 * because from the settings store's point of view those keys are simply unrecognised and are left alone.
 *
 * <p>So the import exists to say what happened to every key rather than to move the ones that are easy. It
 * reads the old file, applies what belongs in the settings, and <b>reports the rest by name</b>.
 *
 * <h2>What it will not do</h2>
 * <ul>
 *   <li><b>It never writes over an existing file it did not read.</b> The settings go through
 *       {@link SettingsStore#set}, one key at a time, so anything the new config has that the old one did
 *       not is untouched — and a value the old file has that the new schema clamps is clamped rather than
 *       rejected.</li>
 *   <li><b>It never guesses.</b> A key it does not recognise is reported, not dropped and not applied
 *       somewhere plausible. The whole failure this class exists to prevent is a silent one.</li>
 *   <li><b>It does not touch the old file.</b> Somebody running this expects to be able to run it again,
 *       and an import that consumed its own input cannot be checked afterwards.</li>
 * </ul>
 */
public final class LegacyConfigImport {

    /**
     * What the import did, key by key.
     *
     * @param applied  keys read out of the old file and written into the settings
     * @param clamped  applied, but not at the value in the file — the schema pulled it into range
     * @param elsewhere keys whose home moved, with where it moved to; nothing was applied for these
     * @param unknown  keys in neither camp: from a fork, a hand-edit, or a version this does not know
     */
    public record Report(List<String> applied, Map<String, String> clamped, Map<String, String> elsewhere,
                         List<String> unknown, List<String> problems) {

        public Report {
            applied = List.copyOf(applied);
            clamped = Map.copyOf(clamped);
            elsewhere = Map.copyOf(elsewhere);
            unknown = List.copyOf(unknown);
            problems = List.copyOf(problems);
        }

        public boolean isEmpty() {
            return applied.isEmpty() && elsewhere.isEmpty() && unknown.isEmpty();
        }

        /** How many settings actually changed. */
        public int count() {
            return applied.size();
        }

        /**
         * The whole thing as lines somebody can read, worst last.
         *
         * <p>Deliberately ends with what did <em>not</em> come across. A report that closed with "47 settings
         * imported" is one people stop reading before the part that needs them to do something.
         */
        public List<String> lines() {
            List<String> said = new ArrayList<>();
            said.add(applied.size() + " setting(s) imported from the old config.yml.");
            clamped.forEach((key, what) -> said.add("  adjusted: " + key + " — " + what));

            if (!elsewhere.isEmpty()) {
                said.add(elsewhere.size() + " setting(s) are not settings any more and were NOT imported:");
                elsewhere.forEach((key, where) -> said.add("  " + key + " → " + where));
            }
            if (!unknown.isEmpty()) {
                said.add(unknown.size() + " key(s) were not recognised at all and were left alone:");
                unknown.forEach(key -> said.add("  " + key));
            }
            problems.forEach(problem -> said.add("  ! " + problem));
            return List.copyOf(said);
        }
    }

    /**
     * Where each key that is no longer a setting went.
     *
     * <p>Written out by hand rather than derived, and that is the point: being made to type where a key went
     * is what stops one being quietly dropped. It is the same list
     * {@code HungerGamesSettingsMigrationTest} holds — that test proves every one of the old plugin's 272
     * keys is either in the schema or in a map like this one, so a key missing from here fails the build
     * rather than being reported to a server owner as "not recognised".
     */
    private static final Map<String, String> MOVED = new LinkedHashMap<>();

    /** Prefixes whose every child moved to the same place. Kept apart so the map stays readable. */
    private static final Map<String, String> MOVED_PREFIXES = new LinkedHashMap<>();

    static {
        MOVED_PREFIXES.put("announcements.text.",
                "messages.yml — wording is not a setting any more; it wants placeholders, colours and "
                        + "translation. Copy your own lines into the module's messages.yml");
        // sounds.* and effects.* are not "moved" in the everyday sense of this map -- calling them that
        // would have been a lie about tuning somebody spent an evening on. But every one of them (bar the
        // three settings below) is a cue binding, and this method genuinely does not apply cue bindings --
        // that is importCues()'s job, on the same file, the whole reason "Same file, two destinations" is
        // in this class's javadoc. Leaving them off both this map and the schema would have `from()` report
        // a fifteen-sound cannon as "not recognised at all", which is exactly the silent loss this class
        // exists to prevent; naming the real destination here is what keeps that promise even for the keys
        // this particular method does not itself read.
        MOVED.put("sounds.enabled", "RainsCore's Effects — one switch for every plugin's sounds");
        MOVED.put("sounds.default-volume", "written into each cue when it is imported");
        MOVED.put("sounds.default-pitch", "written into each cue when it is imported");
        MOVED_PREFIXES.put("sounds.", "read by importCues(), not from() — a sound binding, not a setting");
        MOVED_PREFIXES.put("effects.", "read by importCues(), not from() — a particle binding, not a setting");
        MOVED_PREFIXES.put("loot.tables.",
                "loot-tables.yml, read by LootCatalogue — a loot table is content, not configuration");
        MOVED_PREFIXES.put("shop.items.",
                "sponsor-shop.yml, read by SponsorShopStore");
        MOVED_PREFIXES.put("border.phases",
                "border-phases.yml, read by BorderPhaseStore");
        // teams.* is deliberately NOT here. It was, and that was wrong: the seven keys are real settings
        // this module carries at their old paths, and a live server had teams.max-size: 10 that would have
        // silently become 2. What moved to Core is the team *model*, not the tournament's rules about them.

        MOVED.put("protection.allowed-interactions",
                "RainsCore's LandFlags — 'which blocks may be used despite protection' is the same question "
                        + "every protected area on the server asks");
        MOVED.put("gui.title", "RainsCore's Brand — every page on the server is titled the same way");
        MOVED.put("gui.rows", "Menu decides its own height from what it holds");
        MOVED.put("debug", "RainsCore's log levels, per channel");
        MOVED.put("language", "RainsCore's Messages, server-wide");
    }

    /**
     * What the old plugin's file has to be called for the module to notice it.
     *
     * <p>Its own name rather than {@code config.yml}, which is what the module's *current* settings are
     * called: a server that dropped its old file in under that name would have the module read it as its own
     * config, apply the ninety keys it recognises and never mention the rest — the exact silent loss this
     * class exists to prevent. Renaming it is the deliberate act that says "this is the old one".
     */
    public static final String FILE_NAME = "old-config.yml";

    private LegacyConfigImport() {
    }

    /**
     * Reads an old {@code config.yml} and applies what still belongs in the settings.
     *
     * <p>Nothing is written until every key has been read, so a file that turns out to be unreadable halfway
     * through leaves the settings exactly as they were.
     *
     * @param oldConfig the old plugin's {@code config.yml}
     * @param store     the module's settings, which is what receives the values
     */
    public static Report from(Path oldConfig, SettingsStore<HungerGamesSettings> store) {
        List<String> problems = new ArrayList<>();
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(oldConfig, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("could not read " + oldConfig, unreadable);
        } catch (org.bukkit.configuration.InvalidConfigurationException | RuntimeException notYaml) {
            return new Report(List.of(), Map.of(), Map.of(), List.of(),
                    List.of(oldConfig.getFileName() + " is not valid YAML (" + notYaml.getMessage()
                            + "). Nothing was imported and nothing was changed."));
        }

        SettingsSchema<HungerGamesSettings> schema = store.schema();
        Set<String> known = new LinkedHashSet<>(schema.keys());

        List<String> applied = new ArrayList<>();
        Map<String, String> clamped = new LinkedHashMap<>();
        Map<String, String> elsewhere = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();

        for (String key : leafKeys(yaml)) {
            if (known.contains(key)) {
                apply(store, yaml, key, applied, clamped, problems);
                continue;
            }
            String movedTo = whereDidItGo(key);
            if (movedTo != null) {
                elsewhere.put(key, movedTo);
                continue;
            }
            unknown.add(key);
        }

        if (!applied.isEmpty()) {
            store.save();
        }
        return new Report(applied, clamped, elsewhere, unknown, problems);
    }

    /**
     * Writes one value, and notices when the schema did not take it at face value.
     *
     * <p>The clamp is worth reporting rather than swallowing. A server whose old file said
     * {@code border.max-edge-speed: 0} gets 0.1 — correctly, since zero would break the border outright —
     * and an import that said "imported" without mentioning it would have quietly changed a number the owner
     * believes they set.
     */
    private static void apply(SettingsStore<HungerGamesSettings> store, ConfigurationSection yaml,
                              String key, List<String> applied, Map<String, String> clamped,
                              List<String> problems) {
        Object raw = yaml.get(key);
        String asText = raw instanceof List<?> list
                ? String.join(",", list.stream().map(String::valueOf).toList())
                : String.valueOf(raw);

        if (!store.set(key, asText)) {
            problems.add(key + ": '" + asText + "' is not a value this setting accepts — left as it was");
            return;
        }
        applied.add(key);

        String nowShowing = store.display(key);
        if (nowShowing != null && !nowShowing.equalsIgnoreCase(asText)) {
            clamped.put(key, "you had '" + asText + "', it is now '" + nowShowing + "'");
        }
    }

    /**
     * Reads the old file's {@code sounds.*} and {@code effects.*} into Core's cue registry.
     *
     * <p>Separate from {@link #from} because it writes somewhere else entirely: those keys are not settings,
     * they are bindings, and their home is the server-wide Core cue registry rather than this module's
     * {@code config.yml}. Same file, two destinations.
     *
     * <p><b>Why this is not "moved to Core, copy it by hand".</b> That is what the import used to say, and it
     * was the worst answer available: a live server's file carried a sixteen-sound cannon, a nine-sound
     * elimination and twenty-two particle layers, all written by hand over an evening. Telling somebody their
     * tuning now lives somewhere else and leaving them to retype it is how a migration loses the part nobody
     * can reconstruct.
     *
     * <p>The notation is the same on both sides — {@code SoundSequence} and {@code ParticleSequence} were
     * added to Core reading exactly what the old plugin wrote — so this is a rebinding rather than a
     * translation. A cue name the module does not know is reported rather than invented: it may well be an
     * item this port has not reached, and a rebinding into nowhere is a line nobody would ever see again.
     *
     * @param rebind how one cue is bound — {@code (cue, sounds, particles) -> boolean}, so this can be
     *               exercised without a Core registry
     * @return what happened, per cue, in the order the file listed them
     */
    public static List<String> importCues(Path oldConfig, java.util.function.Predicate<String> knownCue,
                                          CueRebinding rebind) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(oldConfig, StandardCharsets.UTF_8));
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException
                 | RuntimeException unreadable) {
            return List.of("the cues could not be read: " + unreadable.getMessage());
        }

        // Both halves of a cue at once, keyed by the name they share. sounds.item-medikit and
        // effects.item-medikit are one cue written on two lines, and binding them separately would have the
        // second overwrite the first.
        Map<String, String> sounds = new LinkedHashMap<>();
        Map<String, String> particles = new LinkedHashMap<>();
        for (String key : leafKeys(yaml)) {
            if (key.startsWith("sounds.") && !SOUND_SETTINGS.contains(key)) {
                sounds.put(key.substring("sounds.".length()), String.valueOf(yaml.get(key)));
            } else if (key.startsWith("effects.")) {
                particles.put(key.substring("effects.".length()), String.valueOf(yaml.get(key)));
            }
        }

        Set<String> everyCue = new LinkedHashSet<>(sounds.keySet());
        everyCue.addAll(particles.keySet());

        List<String> report = new ArrayList<>();
        int rebound = 0;
        List<String> unrecognised = new ArrayList<>();
        for (String shortName : everyCue) {
            String cue = CUE_PREFIX + shortName;
            if (!knownCue.test(cue)) {
                unrecognised.add(shortName);
                continue;
            }
            if (rebind.rebind(cue, sounds.get(shortName), particles.get(shortName))) {
                rebound++;
            }
        }
        if (rebound > 0) {
            report.add(rebound + " sound/particle cue(s) taken over from your old file.");
        }
        if (!unrecognised.isEmpty()) {
            report.add("These cues in your old file have no counterpart here and were left behind: "
                    + String.join(", ", unrecognised)
                    + ". They belong to features this build does not have yet.");
        }
        return List.copyOf(report);
    }

    /** Binding one cue from what was written. Returns whether it took. */
    @FunctionalInterface
    public interface CueRebinding {
        boolean rebind(String cue, String sounds, String particles);
    }

    /** The prefix every cue this module owns carries — see {@code HungerGamesCues}. */
    private static final String CUE_PREFIX = "hungergames:";

    /**
     * The three {@code sounds.*} keys that are settings rather than cue bindings.
     *
     * <p>Named rather than pattern-matched, because the difference is not visible in the key's shape:
     * {@code sounds.enabled} and {@code sounds.victory} look identical and one of them is a switch.
     */
    private static final Set<String> SOUND_SETTINGS = Set.of(
            "sounds.enabled", "sounds.default-volume", "sounds.default-pitch");

    /** Where a key went, or {@code null} if this does not know it. */
    static String whereDidItGo(String key) {
        String exact = MOVED.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> prefix : MOVED_PREFIXES.entrySet()) {
            if (key.startsWith(prefix.getKey())) {
                return prefix.getValue();
            }
        }
        return null;
    }

    /**
     * Every path in the file that holds a value rather than more paths.
     *
     * <p>{@code getKeys(true)} returns the branches as well as the leaves, and a branch applied as a setting
     * is a {@code MemorySection} written into a config as its {@code toString}.
     */
    static List<String> leafKeys(ConfigurationSection section) {
        List<String> leaves = new ArrayList<>();
        for (String key : section.getKeys(true)) {
            if (!section.isConfigurationSection(key)) {
                leaves.add(key);
            }
        }
        return List.copyOf(leaves);
    }

    /** Every setting the schema has that the old file did not mention — for the "what is new" half. */
    public static List<String> notInTheOldFile(Path oldConfig, SettingsSchema<HungerGamesSettings> schema) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(oldConfig, StandardCharsets.UTF_8));
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException
                | RuntimeException unreadable) {
            return List.of();
        }
        Set<String> had = new LinkedHashSet<>(leafKeys(yaml));
        return schema.settings().stream()
                .map(Setting::key)
                .filter(key -> !had.contains(key))
                .toList();
    }
}
