package de.raindancer.modules.hungergames.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * {@code tributes.yml} — the sign-up sheet, as a file a list can be pasted into.
 *
 * <h2>Why this exists</h2>
 * A tournament's tributes are decided before the evening, from a Discord thread or a sheet of paper, and most
 * of them have never been on the server. Two ways of registering them existed and neither covered that:
 *
 * <ul>
 *   <li>{@code /allow <name>} takes an offline name and always did — but it is one command per person, forty
 *       times, typed.</li>
 *   <li>The tribute screen's picker offers <em>everybody the server has ever seen</em>, which is exactly the
 *       wrong set: somebody who has never joined cannot be chosen at all, and that is most of the list.</li>
 * </ul>
 *
 * <p>So: a file. Forty names pasted in one go, and {@link #load()} can be called again while the server is
 * running — which is what makes it useful rather than a thing that only works at boot. The screen has a button
 * that does exactly that, so nobody has to know the file exists to benefit from it.
 *
 * <h2>Why a name is enough, and what that costs</h2>
 * A UUID is the identity and a name is a display cache — that rule holds everywhere else in this module. Here
 * it cannot: the whole point is people the server has never met, and asking Mojang for forty UUIDs is forty
 * blocking HTTP calls on the main thread at the moment somebody wants a list read.
 *
 * <p>So a name gets a <em>derived</em> UUID, stable for that name, exactly as {@code /allow} does — the same
 * function, so the two agree about who somebody is. It is replaced by the real one the moment that person
 * actually joins, because {@code ConnectionListener} refreshes on every join and the register keys on whoever
 * connects. The cost is that a tribute who changes their name between the sheet being written and the evening
 * starting arrives as a stranger; the alternative was a list that could not be written at all.
 */
public final class TributeRoster {

    private static final LogChannel log = Log.of("hungergames");

    /** What the file is called. Beside the module's own data, not at the server root. */
    public static final String FILE_NAME = "tributes.yml";

    /**
     * The shipped file, which is a comment and nothing else.
     *
     * <p>An empty list rather than an example name: an example is a tribute somebody forgets to delete, and
     * "Steve" turning up on the whitelist of a real tournament is a confusing five minutes.
     */
    private static final String SHIPPED = """
            # The tributes for this tournament, one name per line under "tributes".
            #
            # Names, not UUIDs, on purpose: this list is written before the evening from a sign-up sheet, and
            # most of the people on it have never been on this server. A name here becomes a real tribute the
            # moment they join.
            #
            # Reload it without restarting from the tribute screen — /hg admin, then Tributes, then
            # "Read tributes.yml". Names already registered are left alone, so reading it twice is harmless.
            #
            # tributes:
            #   - Katniss
            #   - Peeta
            #   - Rue

            tributes: []
            """;

    /** One name, and what it will be keyed as until that person first joins. */
    public record Entry(String name, UUID derivedId) {
    }

    /** What happened when the file was read. */
    public record Report(List<Entry> found, List<String> problems) {

        public Report {
            found = List.copyOf(found);
            problems = List.copyOf(problems);
        }

        public boolean isEmpty() {
            return found.isEmpty();
        }

        /** One line per thing worth saying, for a log or a screen. Empty when there is nothing to say. */
        public List<String> lines() {
            List<String> lines = new ArrayList<>();
            if (!found.isEmpty()) {
                lines.add(found.size() + " name(s) read from " + FILE_NAME + ".");
            }
            lines.addAll(problems);
            return List.copyOf(lines);
        }
    }

    private final YamlStore store;

    public TributeRoster(Path file) {
        this.store = new YamlStore(file);
    }

    /** Where the file is, so a screen can name it. */
    public Path file() {
        return store.file();
    }

    /**
     * Reads it. Never throws.
     *
     * <p>An unreadable file is reported and treated as empty rather than allowed to stop whatever asked —
     * this is called from a button during a tournament, and a page that will not open is a worse answer than
     * a page that says the file has a typo on line nine.
     */
    public Report load() {
        if (!store.exists()) {
            return new Report(List.of(), List.of());
        }
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            return new Report(List.of(), List.copyOf(store.problems()));
        }

        List<Entry> found = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        // Case-insensitively, because a sheet written by three people has Katniss, katniss and KATNISS on it
        // and they are one person.
        Set<String> seen = new LinkedHashSet<>();

        for (String raw : yaml.getStringList("tributes")) {
            String name = raw == null ? "" : raw.strip();
            if (name.isEmpty()) {
                continue;   // a blank line in a pasted list is not worth reporting
            }
            if (!isPlausibleName(name)) {
                problems.add("'" + name + "' is not a Minecraft name, so it was skipped.");
                continue;
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                problems.add("'" + name + "' is on the list more than once; the extra was ignored.");
                continue;
            }
            found.add(new Entry(name, derivedIdFor(name)));
        }
        return new Report(found, problems);
    }

    /** Writes the shipped file if there is not one, so somebody looking for it finds it. */
    public boolean createIfMissing() {
        if (store.exists()) {
            return false;
        }
        try {
            java.nio.file.Files.createDirectories(store.file().getParent());
            java.nio.file.Files.writeString(store.file(), SHIPPED, StandardCharsets.UTF_8);
            log.info("Wrote an empty {} — paste your sign-up sheet into it.", FILE_NAME);
            return true;
        } catch (java.io.IOException couldNotWrite) {
            log.warn("Could not write {}: {}", FILE_NAME, couldNotWrite.getMessage());
            return false;
        }
    }

    /**
     * Adds a name to the file, so what was typed into the screen is also on the sheet.
     *
     * <p>Otherwise the file and the register disagree the first time somebody uses both, and reading the file
     * afterwards looks as though it has forgotten people.
     */
    public boolean remember(String name) {
        if (!isPlausibleName(name)) {
            return false;
        }
        return store.update(yaml -> {
            List<String> names = new ArrayList<>(yaml.getStringList("tributes"));
            boolean alreadyThere = names.stream()
                    .anyMatch(known -> known != null && known.strip().equalsIgnoreCase(name.strip()));
            if (!alreadyThere) {
                names.add(name.strip());
                yaml.set("tributes", names);
            }
        });
    }

    /**
     * Whether that could be somebody's Minecraft name.
     *
     * <p>Three to sixteen characters of letters, digits and underscore. Checked because the alternative is a
     * pasted Discord line — "Katniss (she/her)" — becoming a tribute nobody can ever match to a real player,
     * sitting in the register looking like a person who has not turned up yet.
     */
    public static boolean isPlausibleName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.strip();
        return trimmed.length() >= 3 && trimmed.length() <= 16
                && trimmed.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
    }

    /**
     * The UUID a name is keyed as until that person first joins.
     *
     * <p>The same derivation {@code /allow} uses, deliberately — the same name has to mean the same tribute
     * whichever route registered them, or a name added by the file and then allowed by command is two people.
     */
    public static UUID derivedIdFor(String name) {
        return UUID.nameUUIDFromBytes(("hungergames:" + name.strip().toLowerCase(Locale.ROOT))
                .getBytes(StandardCharsets.UTF_8));
    }
}
