package de.raindancer.modules.moderation.store;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.model.Reason;
import de.raindancer.modules.moderation.model.Sentence;
import de.raindancer.modules.moderation.model.Severity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The reasons this server hands punishments out for, and what each one costs.
 *
 * <h2>Why the catalogue is code rather than a config file, for now</h2>
 * Because the ladders are the interesting half and a mis-typed ladder is a permanent ban. Written here
 * they are checked by the compiler and by {@code ReasonsTest} — which asserts, among other things, that
 * every rung is longer than the one below it and that nothing sits above a permanent one. A server that
 * wants its own set gets it by handing a different {@code Reasons} to the module, which is one
 * constructor argument rather than a parser nobody would test.
 *
 * <h2>How the ladders were chosen</h2>
 * Short first rungs. The purpose of a first punishment is to make somebody stop, and almost everybody
 * does; a thirty-minute mute achieves that and keeps a player who was having a bad evening. The ladder
 * then climbs steeply, because the second and third are the ones that are no longer a misunderstanding.
 * Anything {@link Severity#SEVERE} ends permanently, or the ladder never actually bites.
 */
public final class Reasons {

    private final Map<String, Reason> byId;

    private Reasons(List<Reason> reasons) {
        Map<String, Reason> index = new LinkedHashMap<>();
        for (Reason reason : reasons) {
            if (index.putIfAbsent(reason.id(), reason) != null) {
                // A duplicate id would make the escalation ladder count the wrong offences, silently.
                throw new IllegalArgumentException("two reasons share the id '" + reason.id() + "'");
            }
        }
        this.byId = Map.copyOf(index);
    }

    /** A catalogue of somebody else's reasons — for a server that wants its own set. */
    public static Reasons of(List<Reason> reasons) {
        return new Reasons(reasons == null ? List.of() : reasons);
    }

    /** What the module ships with. */
    public static Reasons builtIn() {
        List<Reason> reasons = new ArrayList<>();

        // ── chat ───────────────────────────────────────────────────────────────────────────────
        reasons.add(new Reason("spam", "Spam", PunishmentKind.MUTE, Severity.MINOR,
                List.of(Sentence.of(Duration.ofMinutes(30)),
                        Sentence.of(Duration.ofHours(6)),
                        Sentence.of(Duration.ofDays(3)))));
        reasons.add(new Reason("swearing", "Swearing", PunishmentKind.MUTE, Severity.MINOR,
                List.of(Sentence.of(Duration.ofMinutes(30)),
                        Sentence.of(Duration.ofHours(12)),
                        Sentence.of(Duration.ofDays(7)))));
        reasons.add(new Reason("advertising", "Advertising", PunishmentKind.MUTE, Severity.SERIOUS,
                List.of(Sentence.of(Duration.ofHours(12)),
                        Sentence.of(Duration.ofDays(7)),
                        Sentence.forEver())));
        reasons.add(new Reason("harassment", "Harassment", PunishmentKind.MUTE, Severity.SEVERE,
                List.of(Sentence.of(Duration.ofDays(1)),
                        Sentence.of(Duration.ofDays(14)),
                        Sentence.forEver())));

        // ── the world ──────────────────────────────────────────────────────────────────────────
        reasons.add(new Reason("griefing", "Griefing", PunishmentKind.BAN, Severity.SERIOUS,
                List.of(Sentence.of(Duration.ofDays(3)),
                        Sentence.of(Duration.ofDays(30)),
                        Sentence.forEver())));
        reasons.add(new Reason("stealing", "Stealing", PunishmentKind.BAN, Severity.SERIOUS,
                List.of(Sentence.of(Duration.ofDays(3)),
                        Sentence.of(Duration.ofDays(30)),
                        Sentence.forEver())));

        // ── the account ────────────────────────────────────────────────────────────────────────
        reasons.add(new Reason("cheating", "Cheating", PunishmentKind.BAN, Severity.SEVERE,
                List.of(Sentence.of(Duration.ofDays(30)), Sentence.forEver())));
        reasons.add(new Reason("exploiting", "Exploiting a bug", PunishmentKind.BAN, Severity.SEVERE,
                List.of(Sentence.of(Duration.ofDays(7)),
                        Sentence.of(Duration.ofDays(90)),
                        Sentence.forEver())));
        reasons.add(new Reason("ban-evasion", "Evading a ban", PunishmentKind.BAN, Severity.SEVERE,
                List.of(Sentence.forEver())));

        // ── the ones that are over the moment they land ────────────────────────────────────────
        // A kick and a warning are not states somebody is in, so a ladder of lengths on either would
        // be a length written into the record that means nothing. One rung each, always.
        reasons.add(new Reason("afk", "Away from keyboard", PunishmentKind.KICK, Severity.MINOR,
                List.of(Sentence.forEver())));
        reasons.add(new Reason("asked-to-stop", "Asked to stop", PunishmentKind.KICK, Severity.MINOR,
                List.of(Sentence.forEver())));
        reasons.add(new Reason("first-warning", "A word about the rules", PunishmentKind.WARNING,
                Severity.MINOR, List.of(Sentence.forEver())));
        reasons.add(new Reason("last-warning", "Final warning", PunishmentKind.WARNING,
                Severity.SERIOUS, List.of(Sentence.forEver())));

        // ── while somebody is being talked to ──────────────────────────────────────────────────
        reasons.add(new Reason("under-investigation", "Being looked into", PunishmentKind.FREEZE,
                Severity.SERIOUS,
                List.of(Sentence.of(Duration.ofMinutes(15)), Sentence.of(Duration.ofHours(1)))));

        return new Reasons(reasons);
    }

    /**
     * What a player can pick from when reporting somebody.
     *
     * <h2>Why a report needs presets too</h2>
     * Because the one-word report is the commonest useless one — "griefing", and nothing else — and a
     * list turns that into a category plus whatever they add. It also makes the queue sortable by what
     * was reported, which free text never is.
     *
     * <p>They are <b>categories, not verdicts</b>: a player saying "this looks like cheating" is not the
     * server deciding that it was, so these deliberately do not map to a punishment. What a moderator
     * hands out afterwards is their own decision, from the punishment reasons.
     *
     * <p>Custom text still works — {@code /report <player> <anything>} — and always will. A player
     * describing something the list does not cover is the case a report system exists for.
     */
    public static List<String> reportCategories() {
        return List.of("Griefing", "Stealing", "Cheating", "Harassment", "Spam", "Advertising",
                "Inappropriate build", "Inappropriate name or skin", "Something else");
    }

    /** Everything, worst last — so the reason a misclick lands on is the cheapest one. */
    public List<Reason> all() {
        List<Reason> everything = new ArrayList<>(byId.values());
        everything.sort(Comparator.comparingInt((Reason reason) -> reason.severity().weight())
                .thenComparing(Reason::label, String.CASE_INSENSITIVE_ORDER));
        return everything;
    }

    /** The reasons that hand out this kind of punishment, in the same order. */
    public List<Reason> forKind(PunishmentKind kind) {
        List<Reason> matching = new ArrayList<>();
        for (Reason reason : all()) {
            if (reason.kind() == kind) {
                matching.add(reason);
            }
        }
        return matching;
    }

    /** One reason, however its id was typed. */
    public Optional<Reason> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * The reason a punishment's recorded text names, if any.
     *
     * <p>The other direction from {@code EscalationRule}: given what is written on an old punishment,
     * which preset was it. Used by the history screen to draw the right icon, and by anything that
     * wants to say which rung somebody is on.
     */
    public Optional<Reason> matching(String recordedReason) {
        if (recordedReason == null || recordedReason.isBlank()) {
            return Optional.empty();
        }
        String written = recordedReason.trim().toLowerCase(Locale.ROOT);
        Reason best = null;
        for (Reason reason : byId.values()) {
            String label = reason.label().toLowerCase(Locale.ROOT);
            if (!written.startsWith(label)) {
                continue;
            }
            if (written.length() > label.length()
                    && Character.isLetterOrDigit(written.charAt(label.length()))) {
                continue;
            }
            // The longest label that fits, so "Griefing spawn" is not read as "Grief".
            if (best == null || label.length() > best.label().length()) {
                best = reason;
            }
        }
        return Optional.ofNullable(best);
    }

    public int size() {
        return byId.size();
    }
}
