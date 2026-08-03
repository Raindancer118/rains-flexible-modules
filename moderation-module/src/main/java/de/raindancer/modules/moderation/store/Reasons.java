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

    /**
     * What the module ships with.
     *
     * <h2>How the ladders were chosen</h2>
     * Short first rungs. The purpose of a first punishment is to make somebody stop, and almost everybody
     * does; half an hour achieves that and keeps a player who was having a bad evening. The ladder then
     * climbs steeply, because the second and third are the ones that are no longer a misunderstanding.
     * Anything {@link Severity#SEVERE} ends permanently, or the ladder never actually bites.
     *
     * <h2>Why a kick has no ladder</h2>
     * It is over the moment it lands, so a length written into the record would mean nothing. A kick is
     * for "stop and come back when you have" — <em>not</em> for a player who is idle, which is why there
     * is no away-from-keyboard reason here: kicking somebody for being idle is a server setting, not a
     * moderator's decision, and putting it on somebody's record makes idling look like an offence.
     */
    public static Reasons builtIn() {
        List<Reason> reasons = new ArrayList<>();

        // ── chat: the mutes ────────────────────────────────────────────────────────────────────
        reasons.add(mute("spam", "Spam", Severity.MINOR, 30, 6 * 60, 3 * 24 * 60));
        reasons.add(mute("caps", "Shouting in capitals", Severity.MINOR, 15, 60, 12 * 60));
        reasons.add(mute("swearing", "Swearing", Severity.MINOR, 30, 12 * 60, 7 * 24 * 60));
        reasons.add(mute("begging", "Begging", Severity.MINOR, 30, 4 * 60, 24 * 60));
        reasons.add(mute("arguing", "Arguing with staff in public", Severity.MINOR,
                60, 12 * 60, 3 * 24 * 60));
        reasons.add(mute("off-topic", "Wrong channel", Severity.MINOR, 15, 60, 6 * 60));
        reasons.add(mute("toxicity", "Being unpleasant to people", Severity.SERIOUS,
                2 * 60, 24 * 60, 7 * 24 * 60));
        reasons.add(mute("advertising", "Advertising another server", Severity.SERIOUS,
                12 * 60, 7 * 24 * 60, -1));
        reasons.add(mute("spoilers", "Spoiling somebody's build or surprise", Severity.SERIOUS,
                60, 12 * 60, 3 * 24 * 60));
        reasons.add(mute("harassment", "Harassment", Severity.SEVERE,
                24 * 60, 14 * 24 * 60, -1));
        reasons.add(mute("hate-speech", "Hate speech", Severity.SEVERE, 7 * 24 * 60, -1));
        reasons.add(mute("sexual-content", "Sexual content in chat", Severity.SEVERE,
                24 * 60, 14 * 24 * 60, -1));

        // ── the world: the bans ────────────────────────────────────────────────────────────────
        reasons.add(ban("griefing", "Griefing", Severity.SERIOUS,
                3 * 24 * 60, 30 * 24 * 60, -1));
        reasons.add(ban("stealing", "Stealing", Severity.SERIOUS,
                3 * 24 * 60, 30 * 24 * 60, -1));
        reasons.add(ban("scamming", "Scamming another player", Severity.SERIOUS,
                7 * 24 * 60, 30 * 24 * 60, -1));
        reasons.add(ban("inappropriate-build", "Inappropriate build", Severity.SERIOUS,
                24 * 60, 7 * 24 * 60, 30 * 24 * 60));
        reasons.add(ban("kill-trapping", "Trapping or killing players unfairly", Severity.SERIOUS,
                24 * 60, 7 * 24 * 60, 30 * 24 * 60));
        reasons.add(ban("lag-machine", "Building something that lags the server", Severity.SERIOUS,
                24 * 60, 7 * 24 * 60, 30 * 24 * 60));

        // ── the account: the ones that end permanently ─────────────────────────────────────────
        reasons.add(ban("cheating", "Cheating", Severity.SEVERE, 30 * 24 * 60, -1));
        reasons.add(ban("x-ray", "X-ray", Severity.SEVERE, 14 * 24 * 60, 90 * 24 * 60, -1));
        reasons.add(ban("exploiting", "Exploiting a bug", Severity.SEVERE,
                7 * 24 * 60, 90 * 24 * 60, -1));
        reasons.add(ban("duping", "Duplicating items", Severity.SEVERE, 30 * 24 * 60, -1));
        reasons.add(ban("ban-evasion", "Evading a ban", Severity.SEVERE, -1));
        reasons.add(ban("alt-abuse", "Using a second account to get around a punishment",
                Severity.SEVERE, -1));
        reasons.add(ban("threats", "Threatening somebody", Severity.SEVERE, 14 * 24 * 60, -1));
        reasons.add(ban("doxxing", "Sharing somebody's private information", Severity.SEVERE, -1));
        reasons.add(ban("impersonation", "Pretending to be staff", Severity.SEVERE,
                7 * 24 * 60, 30 * 24 * 60, -1));
        reasons.add(ban("account-sharing", "Letting somebody else use the account",
                Severity.SERIOUS, 24 * 60, 7 * 24 * 60, 30 * 24 * 60));

        // ── over the moment it lands: the kicks ────────────────────────────────────────────────
        // One rung each, always: a kick is not a state somebody is in, so a length on it would be a
        // number in the record that means nothing.
        reasons.add(once("asked-to-stop", "Asked to stop and did not", PunishmentKind.KICK,
                Severity.MINOR));
        reasons.add(once("cool-off", "Take a moment", PunishmentKind.KICK, Severity.MINOR));
        reasons.add(once("ignoring-staff", "Ignoring staff", PunishmentKind.KICK, Severity.MINOR));
        reasons.add(once("inappropriate-name", "Inappropriate name or skin", PunishmentKind.KICK,
                Severity.SERIOUS));
        reasons.add(once("client-problem", "Something is wrong with their client", PunishmentKind.KICK,
                Severity.MINOR));

        // ── on the record and nothing else: the warnings ───────────────────────────────────────
        reasons.add(once("rules-reminder", "A word about the rules", PunishmentKind.WARNING,
                Severity.MINOR));
        reasons.add(once("chat-warning", "A word about their chat", PunishmentKind.WARNING,
                Severity.MINOR));
        reasons.add(once("build-warning", "A word about what they built", PunishmentKind.WARNING,
                Severity.MINOR));
        reasons.add(once("pvp-warning", "A word about how they fight", PunishmentKind.WARNING,
                Severity.MINOR));
        reasons.add(once("final-warning", "Final warning", PunishmentKind.WARNING,
                Severity.SERIOUS));

        // ── while somebody is being talked to: the freezes ─────────────────────────────────────
        reasons.add(freeze("under-investigation", "Being looked into", Severity.SERIOUS, 15, 60));
        reasons.add(freeze("wait-for-staff", "Asked to wait for staff", Severity.MINOR, 5, 15));
        reasons.add(freeze("suspected-cheating", "Suspected of cheating", Severity.SERIOUS, 30, 2 * 60));

        return new Reasons(reasons);
    }

    // ────────────────────────────────────────────────────────────────────────────────────────
    //  Builders, so a catalogue this size reads as a table rather than as forty constructor
    //  calls. Minutes throughout, and -1 for a rung that never ends.
    // ────────────────────────────────────────────────────────────────────────────────────────

    private static Reason mute(String id, String label, Severity severity, int... rungsInMinutes) {
        return new Reason(id, label, PunishmentKind.MUTE, severity, ladder(rungsInMinutes));
    }

    private static Reason ban(String id, String label, Severity severity, int... rungsInMinutes) {
        return new Reason(id, label, PunishmentKind.BAN, severity, ladder(rungsInMinutes));
    }

    private static Reason freeze(String id, String label, Severity severity, int... rungsInMinutes) {
        return new Reason(id, label, PunishmentKind.FREEZE, severity, ladder(rungsInMinutes));
    }

    /** A kick or a warning: over as soon as it happens, so exactly one rung and no length. */
    private static Reason once(String id, String label, PunishmentKind kind, Severity severity) {
        return new Reason(id, label, kind, severity, List.of(Sentence.forEver()));
    }

    private static List<Sentence> ladder(int... rungsInMinutes) {
        List<Sentence> rungs = new ArrayList<>();
        for (int minutes : rungsInMinutes) {
            rungs.add(minutes < 0 ? Sentence.forEver() : Sentence.of(Duration.ofMinutes(minutes)));
        }
        return rungs;
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
