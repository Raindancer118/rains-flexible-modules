package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.platform.permission.Grants;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.StaffRank;
import de.raindancer.modules.moderation.store.StaffRoster;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Promoting, demoting and toggling a single permission.
 *
 * <h2>Why the commands and the screens both come through here</h2>
 * Because a promotion is never only the roster entry. It is the entry, <em>and</em> the granted nodes,
 * <em>and</em> making them take effect on somebody who is standing there, <em>and</em> the audit line,
 * <em>and</em> telling them, <em>and</em> telling the staff. Written twice — once for {@code /promote}
 * and once for the menu — those two lists drift, and the moderation plugin this replaces is the proof:
 * five commands, and only one of them wrote an audit entry.
 *
 * <p>So {@code /promote} and the rank screen are two ways of asking for the same method.
 */
public final class StaffService implements IModerationService {

    private static final de.raindancer.core.platform.log.LogChannel log =
            de.raindancer.core.platform.log.Log.of("moderation");

    private final Plugin plugin;
    private final Server server;
    private final StaffRoster roster;
    private final Grants grants;
    private final Audit audit;
    private final Messages messages;
    private final Chat chat;

    /**
     * Makes a grant take effect now rather than at the next login.
     *
     * <p>Core's, through {@code RainsCore.reapplyGrants}. Behind a consumer so this class stays
     * testable without a server, and because a promoted player may not be online at all.
     */
    private final Consumer<Player> reapply;

    /**
     * Who this module opped, so it only ever de-ops its own.
     *
     * <p>Not persisted on purpose. After a restart nobody is in it, so the module will not
     * de-op anybody at all — which is the safe direction: an op that outlives a demotion is
     * visible in {@code ops.json}, whereas an owner silently de-opped by a plugin they
     * installed for moderation is a support ticket nobody can diagnose.
     */
    private final java.util.Set<UUID> oppedByUs = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile ModerationSettings settings;

    public StaffService(Plugin plugin, Server server, StaffRoster roster, Grants grants, Audit audit,
                       Messages messages, Chat chat, Consumer<Player> reapply,
                       ModerationSettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.roster = roster;
        this.grants = grants;
        this.audit = audit;
        this.messages = messages;
        this.chat = chat;
        this.reapply = reapply == null ? player -> { } : reapply;
        settings(settings);
    }

    /**
     * Makes somebody staff at this rank, and tells everybody who should know.
     *
     * @return whether anything changed
     */
    public boolean promote(CommandSender by, UUID who, String name, StaffRank rank) {
        StaffRank before = roster.rankOf(who).orElse(null);
        if (!roster.promote(who, rank)) {
            return false;
        }
        applyNow(who);
        persist();

        boolean up = before == null || rank.isAtLeast(before);
        record(up ? "promote" : "demote", by, who, name, rank.title(),
                before == null ? "not staff" : before.title());

        messages().send(by, "moderation.rank.changed", "player", name, "rank", rank.title(),
                "colour", rank.colour());
        tellThem(who, "moderation.rank.you-are-now", "rank", rank.title(),
                "colour", rank.colour(), "what", rank.describe());
        tellTheStaff("<white><player></white> is now <" + rank.colour() + "><rank></"
                        + rank.colour() + "> <gray>(by <white><by></white>)",
                Chat.arg("player", name), Chat.arg("rank", rank.title()),
                Chat.arg("by", nameOf(by)));
        return true;
    }

    /**
     * Takes the rank and every node with it.
     *
     * @return whether they were staff at all
     */
    public boolean demote(CommandSender by, UUID who, String name) {
        StaffRank before = roster.rankOf(who).orElse(null);
        if (!roster.demote(who)) {
            return false;
        }
        applyNow(who);
        persist();

        record("demote", by, who, name, "not staff", before == null ? "not staff" : before.title());
        messages().send(by, "moderation.rank.removed", "player", name);
        tellThem(who, "moderation.rank.you-are-no-longer");
        tellTheStaff("<white><player></white> is no longer staff <gray>(by <white><by></white>)",
                Chat.arg("player", name), Chat.arg("by", nameOf(by)));
        return true;
    }

    /**
     * Turns one node on or off, leaving the rank alone.
     *
     * @return whether they now hold it
     */
    public boolean toggle(CommandSender by, UUID who, String name, String node) {
        boolean holdsItNow = roster.toggle(who, node);
        applyNow(who);
        persist();

        record(holdsItNow ? "grant" : "revoke", by, who, name, node,
                roster.rankOf(who).map(StaffRank::title).orElse("not staff"));
        return holdsItNow;
    }

    /** Puts a drifted person back to exactly what their rank grants. */
    /**
     * Puts a rank's grants back on somebody as they join, silently.
     *
     * <h2>Why this has to happen at all</h2>
     * A rank hands out its nodes at the moment somebody is promoted. That is right — a grant is a
     * thing that happened, and re-deciding it on every join would undo the per-person permissions an
     * admin turned off by hand.
     *
     * <p>But it means a node <em>added to a preset afterwards</em> reaches nobody. Everybody promoted
     * before it existed keeps the set they were given, for ever, and the only cure is promoting each
     * of them again — which nobody thinks to do, because nothing is visibly wrong: the rank says Admin
     * and the command says no.
     *
     * <p>This is the half that heals: on join, whatever the preset says now is granted. Only granted —
     * nothing is taken away, so a permission somebody was given by hand survives, and one an admin
     * switched off stays off unless the preset itself changed.
     *
     * @return whether anything was actually new
     */
    public boolean topUpOnJoin(UUID who) {
        if (who == null || roster.rankOf(who).isEmpty()) {
            return false;
        }
        boolean changed = roster.topUpFromPreset(who);
        // Applied whether or not the roster changed: the roster is what they *should* hold, and the
        // permission plugin is what they *do*. Those come apart on their own — a LuckPerms edit, a
        // restore from backup — and this is the one moment both are in the same place.
        applyNow(who);
        if (changed) {
            persist();
        }
        return changed;
    }

    public boolean reapplyPreset(CommandSender by, UUID who, String name) {
        if (!roster.reapplyPreset(who)) {
            return false;
        }
        applyNow(who);
        persist();
        record("reapply-preset", by, who, name,
                roster.rankOf(who).map(StaffRank::title).orElse("not staff"), null);
        return true;
    }

    /**
     * Writes down that somebody switched a tool on or off.
     *
     * <p>Audited even when it is somebody using it on themselves, and that is the interesting case: an
     * invincible moderator standing in the middle of a fight is a fair question afterwards, and "was
     * anybody in god mode at the time" is exactly the sort of thing nobody can answer from memory.
     */
    public void recordSelfTool(CommandSender by, UUID who, String name,
                               de.raindancer.modules.moderation.command.SelfToolCommand.Tool tool,
                               boolean nowOn) {
        audit.record(AuditEntry.of("moderation", (nowOn ? "" : "un") + tool.word())
                .by(by instanceof Player player ? player.getUniqueId() : null, nameOf(by))
                .to(who, name)
                .saying(nowOn ? "on" : "off"));
    }

    /**
     * Writes down that somebody healed, fed, hurt or starved a player.
     *
     * <p>Audited for the same reason a tool is, and more so for the harmful two: half of somebody's
     * health disappearing mid-fight is the sort of thing that gets reported as a bug or as cheating,
     * and neither answer is available from memory afterwards.
     */
    public void recordVital(CommandSender by, UUID who, String name,
                            de.raindancer.modules.moderation.command.VitalsCommand.Vital vital) {
        audit.record(AuditEntry.of("moderation", vital.word())
                .by(by instanceof Player player ? player.getUniqueId() : null, nameOf(by))
                .to(who, name)
                .saying(vital.harmful() ? "harmful" : "restorative"));
    }

    /** The roster, for a screen that wants to draw it. */
    public StaffRoster roster() {
        return roster;
    }

    /** What somebody has been granted, for the per-person toggle screen. */
    public boolean has(UUID who, String node) {
        return grants.has(who, node);
    }

    // ---------------------------------------------------------------------------- the doing

    /**
     * Makes the change real for somebody who is standing there.
     *
     * <p>Otherwise a moderator is told they are a moderator and every command refuses them until they
     * relog — which they report as the promotion being broken, and which is the same bug in reverse for
     * a demotion: somebody who keeps a power they were just told they no longer have.
     */
    private void applyNow(UUID who) {
        Player here = server.getPlayer(who);
        if (here != null) {
            reapply.accept(here);
        }
        applyOpPolicy(who);
    }

    /**
     * The one place in this module that ops anybody, and the only one that may be.
     *
     * <h2>Why staff are not op</h2>
     * Op is not a permission — it is <em>every</em> permission, of every plugin on the server, plus the
     * vanilla commands: {@code /stop}, {@code /gamerule}, and {@code /op} itself. A moderator who is op
     * can promote themselves to admin, and an admin who is op can switch off the setting that limits
     * them. So the tiers grant nodes, and nothing below {@link StaffRank#ADMIN} is ever opped at all.
     *
     * <p>{@link ModerationSettings#adminsAreOp()} exists because some owners genuinely run their admins
     * as co-owners, and telling them to edit {@code ops.json} by hand would be pretending this module
     * does not know about the question. It is <b>off</b>, so the decision is made deliberately rather
     * than inherited.
     *
     * <h2>Why it takes op away as well as gives it</h2>
     * An admin demoted to moderator who stays op is somebody who kept every power the demotion was
     * about — and the demotion looked like it worked. The one op this module did not grant, it also
     * never removes: see below.
     */
    private void applyOpPolicy(UUID who) {
        if (!settings.adminsAreOp()) {
            // With the setting off, this module has no opinion about op at all — it grants nodes and
            // nothing else. Returning here rather than falling through to the check below is not a
            // shortcut: the first live run warned about the owner still being op on *every* rank
            // change, including promotions, which is a warning about something nobody asked for.
            return;
        }
        boolean shouldBeOp = settings.adminsAreOp()
                && roster.rankOf(who).filter(rank -> rank == StaffRank.ADMIN).isPresent();

        org.bukkit.OfflinePlayer them = server.getOfflinePlayer(who);
        if (them.isOp() == shouldBeOp) {
            return;
        }
        if (!shouldBeOp && !oppedByUs.contains(who)) {
            // Somebody the server owner opped by hand, or in ops.json. Not ours to take away: an admin
            // demotion silently de-opping the owner's co-owner is a worse surprise than an op that
            // outlives a rank. Said in the console, because the alternative is a demotion that looks
            // complete and is not.
            log.warn("{} is still a server operator, which this module did not grant and will not "
                    + "take away. Their rank no longer implies it — remove it with /deop if that was "
                    + "the intention.", them.getName() == null ? who.toString() : them.getName());
            return;
        }
        them.setOp(shouldBeOp);
        if (shouldBeOp) {
            oppedByUs.add(who);
        } else {
            oppedByUs.remove(who);
        }
    }



    /** Both files, off the server thread. Neither is large and both matter after a crash. */
    private void persist() {
        Scheduling.async(plugin, () -> {
            roster.flush();
            grants.flush();
        });
    }

    private void tellThem(UUID who, String key, Object... values) {
        Player here = server.getPlayer(who);
        if (here != null) {
            messages.send(here, key, values);
        }
        // Not queued as a pending notice: a rank they cannot use yet is not news they need, and the
        // moment they log in the permissions are simply there.
    }

    private void tellTheStaff(String line,
                              net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... arguments) {
        // Scheduled onto the global region: reading the online player list and asking each one for a
        // permission is main-thread work, and this can be reached from a command on any thread.
        Scheduling.global(plugin, () -> {
            List<Player> staff = new ArrayList<>();
            for (Player listening : server.getOnlinePlayers()) {
                if (listening.hasPermission(ModerationPermission.HISTORY.node())) {
                    staff.add(listening);
                }
            }
            if (!staff.isEmpty()) {
                chat.broadcast(staff, line, arguments);
            }
        });
    }

    /**
     * Writes the line that answers "who made them an admin?".
     *
     * <p>Always, and regardless of the audit setting. Every other audit line in this module can be
     * switched off because it records something a moderator did to a player; these record somebody
     * handing out the power to do it, which is the one thing a server owner must be able to look up.
     */
    private void record(String action, CommandSender by, UUID who, String name, String to,
                        String from) {
        AuditEntry.Builder entry = AuditEntry.of("moderation", action)
                .by(by instanceof Player player ? player.getUniqueId() : null, nameOf(by))
                .to(who, name)
                .saying(to);
        if (from != null) {
            entry = entry.with("from", from);
        }
        audit.record(entry);
    }

    private Messages messages() {
        return messages;
    }

    private static String nameOf(CommandSender sender) {
        return sender == null ? "the console" : sender.getName();
    }

    @Override
    public void settings(ModerationSettings settings) {
        this.settings = settings == null ? ModerationSettings.DEFAULTS : settings;
    }

    @Override
    public String describe() {
        return "promoting, demoting and toggling a single permission — the roster, the grants and the "
                + "record of who did it";
    }
}
