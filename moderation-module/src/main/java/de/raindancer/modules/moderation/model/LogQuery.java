package de.raindancer.modules.moderation.model;

import de.raindancer.core.world.time.Moments;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A search through the chat log or the blocked attempts, as staff type it:
 * {@code player:Bo from:2026-10-10 17:30 to:18:00 day:yesterday last:2h type:join page:2 rules "near spawn"}.
 *
 * <p>Plain: read against a zone and a clock handed in, so it is tested without a server. Written back with
 * {@link #arguments} it reads as the same search, which is what the clickable pages and names run.
 *
 * @param players  names or ids as typed, any one of them
 * @param from     inclusive, null for no limit
 * @param to       exclusive, null for no limit
 * @param words    each must appear, any case
 * @param phrases  each must appear as it stands, any case
 * @param types    kinds of attempt, as typed
 * @param problems the parts that could not be read, as typed
 */
public record LogQuery(List<String> players, Instant from, Instant to, List<String> words, List<String> phrases,
                       List<String> types, int page, List<String> problems) {

    private static final Pattern TIME = Pattern.compile("\\d{1,2}:\\d{2}(:\\d{2})?");

    public LogQuery {
        players = List.copyOf(players);
        words = List.copyOf(words);
        phrases = List.copyOf(phrases);
        types = List.copyOf(types);
        problems = List.copyOf(problems);
        page = Math.max(1, page);
    }

    private record Token(String text, boolean quoted) {
    }

    public static LogQuery parse(String[] args, ZoneId zone, Instant clock) {
        Instant now = Instant.ofEpochSecond(clock.getEpochSecond());
        List<Token> tokens = tokens(args == null ? "" : String.join(" ", args));
        List<String> players = new ArrayList<>();
        List<String> words = new ArrayList<>();
        List<String> phrases = new ArrayList<>();
        List<String> types = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Instant from = null;
        Instant to = null;
        int page = 1;
        String toTyped = null;
        for (int at = 0; at < tokens.size(); at++) {
            Token token = tokens.get(at);
            if (token.quoted()) {
                phrases.add(token.text());
                continue;
            }
            int colon = token.text().indexOf(':');
            String key = colon < 0 ? "" : token.text().substring(0, colon).toLowerCase(Locale.ROOT);
            String value = colon < 0 ? "" : token.text().substring(colon + 1);
            boolean dateOnly = value.matches("\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}\\.\\d{1,2}\\.(\\d{4})?");
            if (dateOnly && (isFrom(key) || isTo(key)) && at + 1 < tokens.size() && !tokens.get(at + 1).quoted()
                    && TIME.matcher(tokens.get(at + 1).text()).matches()) {
                value = value + " " + tokens.get(++at).text();
            }
            String typed = key + ":" + value;
            if (isPlayer(key)) {
                if (value.isBlank()) {
                    problems.add(typed);
                } else {
                    players.add(value);
                }
            } else if (isFrom(key)) {
                Optional<Instant> start = Moments.start(value, zone, now);
                start.ifPresentOrElse(moment -> { }, () -> problems.add(typed));
                from = start.orElse(from);
            } else if (isTo(key)) {
                Optional<Instant> end = Moments.end(value, zone, now);
                end.ifPresentOrElse(moment -> { }, () -> problems.add(typed));
                to = end.orElse(to);
                toTyped = typed;
            } else if (key.equals("day") || key.equals("date") || key.equals("on")) {
                Optional<Moments.Span> day = Moments.day(value, zone, now);
                if (day.isPresent()) {
                    from = day.get().from();
                    to = day.get().to();
                } else {
                    problems.add(typed);
                }
            } else if (key.equals("last")) {
                Optional<Moments.Span> last = Moments.last(value, now);
                if (last.isPresent()) {
                    from = last.get().from();
                    to = null;
                } else {
                    problems.add(typed);
                }
            } else if (key.equals("page")) {
                try {
                    page = Integer.parseInt(value);
                    if (page < 1) {
                        problems.add(typed);
                    }
                } catch (NumberFormatException notANumber) {
                    problems.add(typed);
                }
            } else if (key.equals("type") || key.equals("kind")) {
                if (value.isBlank()) {
                    problems.add(typed);
                } else {
                    types.add(value.toLowerCase(Locale.ROOT));
                }
            } else if (!token.text().isBlank()) {
                words.add(token.text());
            }
        }
        if (from != null && to != null && !to.isAfter(from)) {
            problems.add(toTyped == null ? "to" : toTyped);
        }
        return new LogQuery(players, from, to, words, phrases, types, page, problems);
    }

    private static boolean isPlayer(String key) {
        return key.equals("player") || key.equals("p") || key.equals("name");
    }

    private static boolean isFrom(String key) {
        return key.equals("from") || key.equals("since") || key.equals("after");
    }

    private static boolean isTo(String key) {
        return key.equals("to") || key.equals("until") || key.equals("before");
    }

    /** Splits on spaces, keeping what is in double quotes together: {@code "a b"} and {@code from:"a b"}. */
    private static List<Token> tokens(String typed) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean wholeQuoted = false;
        for (int i = 0; i < typed.length(); i++) {
            char c = typed.charAt(i);
            if (c == '"') {
                if (!inQuotes && current.isEmpty()) {
                    wholeQuoted = true;
                }
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                flush(tokens, current, wholeQuoted);
                wholeQuoted = false;
            } else {
                current.append(c);
            }
        }
        flush(tokens, current, wholeQuoted);
        return tokens;
    }

    private static void flush(List<Token> tokens, StringBuilder current, boolean quoted) {
        String text = quoted ? current.toString().strip().replaceAll("\\s+", " ") : current.toString();
        if (!text.isEmpty()) {
            tokens.add(new Token(text, quoted));
        }
        current.setLength(0);
    }

    /** Words, then phrases: everything that must appear in a line. */
    public List<String> terms() {
        List<String> all = new ArrayList<>(words);
        all.addAll(phrases);
        return all;
    }

    /** Whether nothing narrows it — the newest of everything. */
    public boolean isEverything() {
        return players.isEmpty() && from == null && to == null && words.isEmpty() && phrases.isEmpty()
                && types.isEmpty();
    }

    public LogQuery atPage(int number) {
        return new LogQuery(players, from, to, words, phrases, types, number, problems);
    }

    public LogQuery onlyPlayer(String player) {
        return new LogQuery(List.of(player), from, to, words, phrases, types, page, problems);
    }

    /** The same search as command arguments, times pinned down with their offset so a later click reads them alike. */
    public String arguments(ZoneId zone) {
        List<String> parts = new ArrayList<>();
        players.forEach(player -> parts.add("player:" + player));
        if (from != null) {
            parts.add("from:" + Moments.write(from, zone));
        }
        if (to != null) {
            // An exact moment as an end takes in its own second, so the second before is written.
            parts.add("to:" + Moments.write(to.minusSeconds(1), zone));
        }
        types.forEach(type -> parts.add("type:" + type));
        parts.addAll(words);
        phrases.forEach(phrase -> parts.add("\"" + phrase.replace("\"", "") + "\""));
        if (page > 1) {
            parts.add("page:" + page);
        }
        return String.join(" ", parts);
    }

    /** What it looks for, in a line for a header. */
    public String describe(ZoneId zone) {
        return describe(zone, java.util.function.UnaryOperator.identity());
    }

    /** The same, with each player as {@code shown} names them — an id typed or clicked reads as a name. */
    public String describe(ZoneId zone, java.util.function.UnaryOperator<String> shown) {
        List<String> parts = new ArrayList<>();
        if (!players.isEmpty()) {
            parts.add((players.size() == 1 ? "player " : "players ")
                    + String.join(", ", players.stream().map(shown).toList()));
        }
        if (from != null || to != null) {
            parts.add((from == null ? "…" : Moments.show(from, zone)) + " – "
                    + (to == null ? "now" : Moments.show(to.minusSeconds(1), zone)));
        }
        if (!types.isEmpty()) {
            parts.add(String.join(", ", types));
        }
        if (!words.isEmpty() || !phrases.isEmpty()) {
            List<String> quoted = new ArrayList<>();
            terms().forEach(term -> quoted.add("\"" + term + "\""));
            parts.add(String.join(" ", quoted));
        }
        return parts.isEmpty() ? "everything" : String.join(" · ", parts);
    }
}
