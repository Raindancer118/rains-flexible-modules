package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentGuard;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.moderation.punishment.Punishments;
import de.raindancer.core.moderation.punishment.Durations;
import de.raindancer.core.moderation.punishment.VanillaBanBridge;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.Audience;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Reason;
import de.raindancer.modules.moderation.model.Sentence;
import de.raindancer.modules.moderation.rules.AnnouncementRule;
import de.raindancer.modules.moderation.rules.EscalationRule;
import de.raindancer.modules.moderation.rules.StandingRule;
import de.raindancer.modules.moderation.store.PendingNotices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/**
 * Handing out a punishment, and everything that has to happen with it.
 *
 * <h2>Why this is one method rather than each command doing its own</h2>
 * Because a punishment is never only the record. It is the record, <em>and</em> the server's own ban
 * list, <em>and</em> kicking somebody who is standing there, <em>and</em> the audit line, <em>and</em>
 * telling the right people. The plugin this replaces had that sequence written out in five commands, and
 * they had drifted: one of them did not mirror to the vanilla list, another did not kick, and the
 * warning command wrote no audit entry at all. Written once, every path gets all of it.
 *
 * <h2>What it does not decide</h2>
 * Whether the moderator may (that is {@code StaffRule}, asked by the command or the screen before it
 * gets here), how long (that is the reason's ladder, through {@link EscalationRule}), and who hears
 * (that is {@link AnnouncementRule}). This service is the doing.
 */
public final class PunishmentService implements IModerationService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final Server server;
    private final Punishments punishments;
    private final PunishmentGuard guard;
    private final VanillaBanBridge bridge;
    private final Audit audit;
    private final Messages messages;
    private final Chat chat;
    private final PendingNotices pending;
    private final AnnouncementRule announcements;
    private final EscalationRule escalation;

    private volatile ModerationSettings settings;

    public PunishmentService(Plugin plugin, Server server, Punishments punishments,
                             PunishmentGuard guard, VanillaBanBridge bridge, Audit audit,
                             Messages messages, Chat chat, PendingNotices pending,
                             AnnouncementRule announcements,
                             EscalationRule escalation, ModerationSettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.punishments = punishments;
        this.guard = guard;
        this.bridge = bridge;
        this.audit = audit;
        this.messages = messages;
        this.chat = chat;
        this.pending = pending;
        this.announcements = announcements;
        this.escalation = escalation;
        settings(settings);
    }

    /**
     * How long this reason should cost this player, given what they have already done.
     *
     * <p>With escalation switched off it is always the first rung, which makes the presets names
     * rather than a policy — a deliberate choice a server can make.
     */
    public Sentence suggest(Reason reason, UUID subject) {
        if (reason == null) {
            return Sentence.forEver();
        }
        if (!settings.useEscalation()) {
            return reason.first();
        }
        return escalation.suggest(reason, punishments.history(subject));
    }

    /** How many times they have done this before, for the lore line that says which rung this is. */
    public int priorOffences(Reason reason, UUID subject) {
        return escalation.priorOffences(reason, punishments.history(subject));
    }

    /**
     * Hands one out.
     *
     * @param actor    null for the console
     * @param sentence ignored for a kick or a warning, neither of which is a state somebody is in
     * @return what was recorded, so the caller can tell the moderator what they just did
     */
    public Punishment punish(UUID actor, String actorName, UUID subject, String subjectName,
                             PunishmentKind kind, Sentence sentence, String reason) {
        Sentence howLong = sentence == null ? Sentence.forEver() : sentence;
        Punishment given = punishments.punish(subject, kind, actor, reason,
                kind.isLasting() ? howLong.orNull() : null);

        if (kind == PunishmentKind.BAN && settings.mirrorToVanillaBanList()) {
            // So vanilla /banlist still agrees, and so the ban survives this plugin being removed.
            bridge.mirrorBan(subject, given.reason(), given.endsAt());
        }
        removeThemIfNeeded(kind, subject, given);
        tellThem(kind, subject, given);

        record(kind.name().toLowerCase(Locale.ROOT), actor, actorName, subject, subjectName,
                given.reason(), howLong.describe());
        announce(announcements.forPunishment(kind, settings), kind, actorName, subjectName,
                given.reason(), kind.isLasting() ? howLong.describe() : null, false);

        if (kind == PunishmentKind.WARNING) {
            banIfTheyHaveCollectedEnough(subject, subjectName);
        }
        return given;
    }

    /**
     * The line the server drew in advance.
     *
     * <p>A warning stops nothing by design, which is what makes it usable — and also means somebody
     * collecting them faces nothing at all until a moderator notices the pattern. The threshold is the
     * server saying once, ahead of time, where the line is; after that it applies itself, and applies
     * the same way to everybody, which is the half a tired moderator at midnight cannot promise.
     *
     * <p>Handed out as the console rather than as the moderator who gave the last warning: it is the
     * <em>rule</em> banning them, not a person, and attributing it to whoever happened to be on shift
     * makes them the one who gets argued with.
     */
    private void banIfTheyHaveCollectedEnough(UUID subject, String subjectName) {
        ModerationSettings now = settings;
        if (!now.warningsEscalateToABan() || punishments.isActive(subject, PunishmentKind.BAN)) {
            return;
        }
        int collected = new StandingRule(now.warnWindow())
                .recentWarnings(punishments.history(subject), Instant.now());
        if (collected < now.warnsBeforeBan()) {
            return;
        }
        Sentence howLong = Sentence.parse(now.warnBanLength()).orElseGet(Sentence::forEver);
        String why = collected + " warnings within " + Durations.describe(now.warnWindow());

        // Straight to punish() rather than repeating the work: the ban then mirrors to the vanilla
        // list, kicks them, writes its own audit line and announces itself like any other.
        punish(null, "the warning threshold", subject, subjectName, PunishmentKind.BAN, howLong, why);
    }

    /**
     * Ends one early.
     *
     * @return whether there was one to end
     */
    public boolean lift(UUID actor, String actorName, UUID subject, String subjectName,
                        PunishmentKind kind, String why) {
        if (!punishments.lift(subject, kind, actor, why)) {
            return false;
        }
        if (kind == PunishmentKind.BAN && settings.mirrorToVanillaBanList()) {
            bridge.mirrorPardon(subject);
        }
        record("lift-" + kind.name().toLowerCase(Locale.ROOT), actor, actorName, subject, subjectName,
                why, null);
        announce(announcements.forLift(kind, settings), kind, actorName, subjectName, why, null, true);
        return true;
    }

    /** Whether one of these is in force right now. */
    public boolean isActive(UUID subject, PunishmentKind kind) {
        return punishments.isActive(subject, kind);
    }

    /** Everything on somebody's record, newest first. */
    public List<Punishment> history(UUID subject) {
        return punishments.history(subject);
    }

    // ------------------------------------------------------------------------------ the doing

    /**
     * Throws somebody off, when the punishment means they should not be here.
     *
     * <p>Scheduled onto their own thread: on Folia every player has one, and kicking somebody from the
     * wrong region thread is undefined behaviour rather than an exception.
     */
    private void removeThemIfNeeded(PunishmentKind kind, UUID subject, Punishment given) {
        boolean shouldGo = kind == PunishmentKind.KICK
                || (kind == PunishmentKind.BAN && settings.kickOnBan());
        if (!shouldGo) {
            return;
        }
        Player here = server.getPlayer(subject);
        if (here == null) {
            return;
        }
        Component why = kind == PunishmentKind.BAN
                ? guard.joinRefusal(subject).orElseGet(() -> plain(given.reason()))
                : plain(given.reason());
        Scheduling.entity(plugin, here, () -> here.kick(why));
    }

    /**
     * Tells somebody what has just happened to them — now if they are here, on their return if not.
     *
     * <p>A mute that says nothing is a player typing into a void and concluding the server is broken,
     * which is most of what a support channel gets asked about. Being offline is not a reason to say
     * nothing; it is a reason to say it later. See {@link PendingNotices}.
     */
    private void tellThem(PunishmentKind kind, UUID subject, Punishment given) {
        if (kind == PunishmentKind.BAN || kind == PunishmentKind.KICK) {
            return;     // they are on their way out; the kick screen carries the reason
        }
        String key = "moderation.you-were-" + kind.name().toLowerCase(Locale.ROOT);
        Player here = server.getPlayer(subject);
        if (here != null) {
            messages.send(here, key, "reason", given.reason(), "length", given.length());
            return;
        }
        // Not dropped. Somebody muted for spam very often logs off in a huff, and the version that
        // gave up here is the version where they come back, cannot talk, and conclude the server is
        // broken — which is what a support channel spends its evenings on.
        pending.keep(subject, key, Map.of("reason", given.reason(), "length", given.length()));
    }

    /** Writes the line an appeal is answered from. */
    private void record(String action, UUID actor, String actorName, UUID subject, String subjectName,
                        String reason, String length) {
        if (!settings.auditEverything()) {
            return;
        }
        AuditEntry.Builder entry = AuditEntry.of("moderation", action)
                .by(actor, actorName)
                .to(subject, subjectName)
                .saying(reason);
        if (length != null) {
            entry = entry.with("length", length);
        }
        audit.record(entry);
    }

    private void announce(Audience audience, PunishmentKind kind, String actorName, String subjectName,
                          String reason, String length, boolean lifted) {
        if (audience == Audience.NOBODY) {
            return;
        }
        boolean named = announcements.namesTheModerator(audience, settings);
        String what = lifted ? "no longer " + kind.past() : kind.past();
        String line = "<white><subject></white> is <yellow>" + what + "</yellow>"
                + (length == null ? "" : " <gray>(<length>)</gray>")
                + (named ? " <gray>— by <white><moderator></white></gray>" : "")
                + "<gray>: <reason>";

        chat.broadcast(recipientsFor(audience), line,
                Chat.arg("subject", subjectName),
                Chat.arg("moderator", actorName == null ? "the console" : actorName),
                Chat.arg("length", length == null ? "" : length),
                Chat.arg("reason", reason == null ? "no reason given" : reason));
    }

    /**
     * Who actually receives the line.
     *
     * <p>"The staff" is whoever may read a player's history — the same permission the history command
     * asks for, rather than a second idea of who counts as staff.
     */
    private List<Player> recipientsFor(Audience audience) {
        List<Player> recipients = new ArrayList<>();
        for (Player who : server.getOnlinePlayers()) {
            if (audience == Audience.EVERYBODY
                    || who.hasPermission(ModerationPermission.HISTORY.node())) {
                recipients.add(who);
            }
        }
        return recipients;
    }

    private static Component plain(String text) {
        return MINI.deserialize("<red>" + (text == null ? "" : text));
    }

    @Override
    public void settings(ModerationSettings settings) {
        this.settings = settings == null ? ModerationSettings.DEFAULTS : settings;
    }

    @Override
    public String describe() {
        return "handing out a punishment: the record, the ban list, the kick, the audit line, the word";
    }
}
