package de.raindancer.modules.hungergames.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.model.BorderPhaseConfig.Mode;
import de.raindancer.modules.hungergames.model.BorderTrigger;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persists the border's phase list, {@code border-phases.yml}, as the compact line syntax an owner types
 * rather than as a nested structure they would have to click through five fields to change.
 *
 * <h2>Why this moved out of the settings record</h2>
 * {@code HungerGamesSettings} is one schema bound to one file by {@code ModuleContext.settings}, and every
 * component of it is a single value an owner tunes — a number, a toggle, a duration. A phase list is not
 * that: it is a sequence of records, each with its own trigger and its own shrink mode, and a settings
 * schema has no component shape for "a list of these". Treating it as one anyway — a {@code List<String>}
 * setting holding lines this class alone understands — would have made the settings screen either useless
 * for it (there is no widget for "one border phase") or a second, worse copy of the parser below.
 *
 * <h2>Why a syntax error refuses the whole file rather than skipping the bad line</h2>
 * A border with three phases and a typo in the fourth is not a border with three phases: the phases run in
 * order, each one picking up where the last left off, and a plugin that silently dropped the broken one
 * would shrink to whatever the third phase's target was and then stop, with nobody told the border was
 * meant to keep going. So a line that will not parse fails the whole load, the old file (if any) is left
 * untouched on disk, and the caller is handed why — see {@link #load()}.
 */
public final class BorderPhaseStore {

    private static final LogChannel log = Log.of("hungergames");

    private static final Pattern PHASE = Pattern.compile(
            "^(?<trigger>[^-]+?)\\s*->\\s*(?<target>\\d+(?:\\.\\d+)?)\\s*@\\s*(?<mode>duration|speed|max)\\s*:\\s*(?<value>[\\w.]+)"
                    + "(?:\\s*,\\s*prefer\\s*:\\s*(?<prefer>[\\w.]+))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALIVE = Pattern.compile("^alive\\s*<\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT = Pattern.compile("^(\\d+(?:\\.\\d+)?)%$");
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([hms])");

    private final YamlStore store;
    private final Duration gameDuration;
    private final List<String> problems = new ArrayList<>();

    /**
     * @param gameDuration the round length, needed to resolve a percentage trigger (e.g. {@code 50%})
     *                     into an absolute time
     */
    public BorderPhaseStore(Path file, Duration gameDuration) {
        this.store = new YamlStore(file);
        this.gameDuration = gameDuration;
    }

    /** What was wrong with the file the last time {@link #load()} refused it. Empty when it loaded cleanly. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /**
     * The configured phases, in order. An absent file is zero phases, not an error — a server with no
     * border shrink configured yet is a normal starting state. A file that exists but will not parse — a
     * syntax error in one line, or invalid YAML — is reported through {@link #problems()} and treated as
     * zero phases too, but the file itself is left exactly where it was; nothing here ever writes over one
     * it could not read.
     */
    public List<BorderPhaseConfig> load() {
        synchronized (problems) {
            problems.clear();
        }
        if (!store.exists()) {
            return List.of();
        }
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            carry();
            store.quarantine();
            return List.of();
        }
        List<String> lines = yaml.getStringList("phases");
        List<BorderPhaseConfig> phases = new ArrayList<>();
        for (String line : lines) {
            try {
                phases.add(parse(line, gameDuration));
            } catch (IllegalArgumentException broken) {
                note("'" + line + "' could not be parsed (" + broken.getMessage() + ") — the whole phase "
                        + "list was rejected rather than shrinking to an order nobody configured");
                return List.of();
            }
        }
        return phases;
    }

    /**
     * Writes the phase list, each phase serialised back into the compact syntax {@link #load()} reads.
     * Refuses to write a list containing a phase this store cannot itself parse back — see the class note
     * on why a partial write is worse than none.
     */
    public boolean save(List<BorderPhaseConfig> phases) {
        List<String> lines = serialize(phases);
        // Round-tripped before it is trusted: a phase that does not survive its own serialisation would
        // otherwise be written as a line nobody — including this class on the very next load — can read
        // back, which is a border that silently stops shrinking after a restart.
        try {
            for (String line : lines) {
                parse(line, gameDuration);
            }
        } catch (IllegalArgumentException broken) {
            log.warn("border-phases.yml: refused to save a phase list that would not read back ({})",
                    broken.getMessage());
            return false;
        }
        return store.write(yaml -> yaml.set("phases", lines));
    }

    // ---------------------------------------------------------------------------- syntax

    /**
     * Parses a phase line: {@code <trigger> -> <target size> @ <mode>:<value>}.
     *
     * @throws IllegalArgumentException on any syntax error
     */
    public static BorderPhaseConfig parse(String line, Duration gameDuration) {
        Matcher matcher = PHASE.matcher(line.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid phase syntax: \"" + line
                    + "\" (expected '<trigger> -> <size> @ <mode>:<value>')");
        }

        BorderTrigger trigger = parseTrigger(matcher.group("trigger").trim(), gameDuration);
        double target = Double.parseDouble(matcher.group("target"));
        String mode = matcher.group("mode").toLowerCase(Locale.ROOT);
        String value = matcher.group("value");
        String prefer = matcher.group("prefer");

        if (prefer != null && !mode.equals("max")) {
            throw new IllegalArgumentException("'prefer:' is only allowed in max mode (line: \"" + line + "\")");
        }

        return switch (mode) {
            case "duration" -> BorderPhaseConfig.ofDuration(trigger, target, parseDuration(value));
            case "speed" -> BorderPhaseConfig.ofFixedSpeed(trigger, target, Double.parseDouble(value));
            case "max" -> prefer != null
                    ? BorderPhaseConfig.ofMaxSpeed(trigger, target, Double.parseDouble(value), parseDuration(prefer))
                    : BorderPhaseConfig.ofMaxSpeed(trigger, target, Double.parseDouble(value));
            default -> throw new IllegalArgumentException("unknown mode: " + mode);
        };
    }

    /** Serialises the phases back into the syntax {@link #parse} reads. */
    public static List<String> serialize(List<BorderPhaseConfig> phases) {
        List<String> lines = new ArrayList<>();
        for (BorderPhaseConfig phase : phases) {
            lines.add(serializePhase(phase));
        }
        return lines;
    }

    /** Pure syntax check, without committing to a game duration — what the settings screen validates against. */
    public static Optional<String> validateList(List<String> lines) {
        for (String line : lines) {
            try {
                parse(line, Duration.ofMinutes(180));
            } catch (IllegalArgumentException broken) {
                return Optional.of(broken.getMessage());
            }
        }
        return Optional.empty();
    }

    private static BorderTrigger parseTrigger(String input, Duration gameDuration) {
        Optional<Duration> time = Optional.empty();
        Optional<Integer> aliveBelow = Optional.empty();

        for (String part : input.split("\\|")) {
            String trimmed = part.trim();
            Matcher alive = ALIVE.matcher(trimmed);
            Matcher percent = PERCENT.matcher(trimmed);
            if (alive.matches()) {
                aliveBelow = Optional.of(Integer.parseInt(alive.group(1)));
            } else if (percent.matches()) {
                double fraction = Double.parseDouble(percent.group(1)) / 100.0;
                time = Optional.of(Duration.ofMillis((long) (gameDuration.toMillis() * fraction)));
            } else {
                time = Optional.of(parseDuration(trimmed));
            }
        }
        return new BorderTrigger(time, aliveBelow);
    }

    private static Duration parseDuration(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT);
        Matcher m = DURATION_PART.matcher(s);
        Duration result = Duration.ZERO;
        int matched = 0;
        while (m.find()) {
            long amount = Long.parseLong(m.group(1));
            result = switch (m.group(2)) {
                case "h" -> result.plusHours(amount);
                case "m" -> result.plusMinutes(amount);
                default -> result.plusSeconds(amount);
            };
            matched += m.group().length();
        }
        if (matched != s.length() || matched == 0) {
            throw new IllegalArgumentException("invalid duration: \"" + input + "\" (e.g. 90s, 15m, 1h30m)");
        }
        return result;
    }

    private static String serializePhase(BorderPhaseConfig phase) {
        StringBuilder trigger = new StringBuilder();
        phase.trigger().time().ifPresent(time -> trigger.append(formatDuration(time)));
        phase.trigger().aliveBelow().ifPresent(alive -> {
            if (!trigger.isEmpty()) {
                trigger.append("|");
            }
            trigger.append("alive<").append(alive);
        });

        String mode = switch (phase.mode()) {
            case Mode.DURATION -> "duration:" + formatDuration(phase.duration().orElseThrow());
            case Mode.FIXED_SPEED -> "speed:" + formatDouble(phase.edgeSpeed().orElseThrow());
            case Mode.MAX_SPEED -> "max:" + formatDouble(phase.edgeSpeed().orElseThrow())
                    + phase.duration().map(prefer -> ",prefer:" + formatDuration(prefer)).orElse("");
        };

        return trigger + " -> " + formatDouble(phase.targetSize()) + " @ " + mode;
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds % 3600 == 0 && seconds > 0) {
            return (seconds / 3600) + "h";
        }
        if (seconds % 60 == 0 && seconds > 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    private static String formatDouble(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void carry() {
        List<String> fromFile = store.problems();
        synchronized (problems) {
            problems.addAll(fromFile);
        }
    }

    private void note(String problem) {
        synchronized (problems) {
            problems.add(problem);
        }
        log.warn("border-phases.yml: {}", problem);
    }
}
