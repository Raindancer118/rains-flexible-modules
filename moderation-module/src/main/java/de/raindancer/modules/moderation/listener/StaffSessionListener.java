package de.raindancer.modules.moderation.listener;

import de.raindancer.core.ui.chat.Chat;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.store.ImmuneStaff;
import de.raindancer.modules.moderation.store.PendingNotices;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Coming on shift, and going off it.
 *
 * <h2>What somebody joining needs to know</h2>
 * How many reports are waiting, before anything else. A queue that has to be opened to be noticed is a
 * queue that is noticed the following evening.
 *
 * <h2>And what the staff need to know about somebody joining</h2>
 * That there are notes on them. Quietly, to the staff only — the player never learns a note exists,
 * which is the whole reason a note can be honest.
 *
 * <h2>The leaving half</h2>
 * Every module listener is told, and the staff chat toggle is cleared. That second one is not tidiness:
 * somebody who logs back in still toggled on says to two people what they think they are saying to the
 * server, and a mistake that quiet is one nobody reports as a bug.
 */
public final class StaffSessionListener implements IModerationListener {

    private final ModerationServices services;
    private final ImmuneStaff immune;
    private final PendingNotices pending;
    private final List<IModerationListener> everybodyElse = new ArrayList<>();

    public StaffSessionListener(ModerationServices services, ImmuneStaff immune,
                                PendingNotices pending) {
        this.services = services;
        this.immune = immune;
        this.pending = pending;
    }

    /** The other listeners, so leaving tells all of them. Called by the module as it registers them. */
    public StaffSessionListener alsoTelling(IModerationListener... listeners) {
        if (listeners != null) {
            for (IModerationListener listener : listeners) {
                if (listener != null && listener != this) {
                    everybodyElse.add(listener);
                }
            }
        }
        return this;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();

        // The one moment a permission plugin can be asked about this account at all. What is learnt
        // here is what protects them for the whole time they are offline — see ImmuneStaff.
        if (immune.remember(joining.getUniqueId(),
                joining.hasPermission(de.raindancer.modules.moderation.rules.StaffRule.IMMUNE))) {
            de.raindancer.core.platform.util.Scheduling.async(services.plugin(), immune::flush);
        }

        if (services.config().vanishOnJoinForStaff()
                && joining.hasPermission(ModerationPermission.VANISH.node())) {
            services.vanish().vanish(joining.getUniqueId(), joining.getAllowFlight());
        }
        if (services.config().openReportsOnJoin()
                && joining.hasPermission(ModerationPermission.REPORTS.node())) {
            int waiting = services.reports().waitingCount();
            if (waiting > 0) {
                services.messages().send(joining, "moderation.report.waiting", "count", waiting);
            }
        }
        if (services.config().notesShownOnJoin()) {
            tellTheStaffAbout(joining);
        }
        deliverWhatWasWaiting(joining);
    }

    /**
     * Says what could not be said while they were away.
     *
     * <p>A mute handed out to somebody who had already logged off, or a report closed an hour after it
     * was filed. The version that dropped those lines is the version where a player comes back, cannot
     * talk, and concludes the server is broken — or files no more reports, because nobody ever came
     * back to them about the last one.
     *
     * <p>A tick later, deliberately: a message sent inside the join handler lands before the client has
     * finished drawing its chat, so it is the one line nobody ever sees.
     */
    private void deliverWhatWasWaiting(Player joining) {
        List<PendingNotices.Notice> theirs = pending.forgetAndTake(joining.getUniqueId());
        if (theirs.isEmpty()) {
            return;
        }
        de.raindancer.core.platform.util.Scheduling.entityLater(services.plugin(), joining, 40L, () -> {
            if (!joining.isOnline()) {
                return;
            }
            services.messages().send(joining, "moderation.while-you-were-away");
            for (PendingNotices.Notice notice : theirs) {
                services.messages().send(joining, notice.key(), notice.asArguments());
            }
        });
        de.raindancer.core.platform.util.Scheduling.async(services.plugin(), pending::flush);
    }

    /** A quiet word to whoever may read notes, when somebody with a record comes on. */
    private void tellTheStaffAbout(Player joining) {
        int notes = services.noteService().countAbout(joining.getUniqueId());
        if (notes <= 0) {
            return;
        }
        List<Player> staff = new ArrayList<>();
        for (Player who : services.server().getOnlinePlayers()) {
            // Not the person who just joined, even if they are staff themselves. A moderator does not
            // need telling that there are notes about them, and being told is how they find out.
            if (!who.equals(joining) && who.hasPermission(ModerationPermission.NOTES.node())) {
                staff.add(who);
            }
        }
        if (!staff.isEmpty()) {
            services.chat().broadcast(staff,
                    "<gray><white><player></white> has <white><count></white> staff note(s).",
                    Chat.arg("player", joining.getName()), Chat.arg("count", notes));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID leaving = event.getPlayer().getUniqueId();
        forget(leaving);
        for (IModerationListener listener : everybodyElse) {
            listener.forget(leaving);
        }
    }

    @Override
    public void forget(UUID player) {
        // The toggle, which is the one thing in this module that outlives a session wrongly.
        services.staffChat().forget(player);
    }

    @Override
    public String describe() {
        return "coming on shift and going off it: waiting reports, notes, and what to forget";
    }
}
