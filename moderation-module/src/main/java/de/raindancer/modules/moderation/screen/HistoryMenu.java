package de.raindancer.modules.moderation.screen;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Reason;
import de.raindancer.modules.moderation.model.RecordEntry;
import de.raindancer.modules.moderation.model.Report;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What has happened to somebody.
 *
 * <h2>Why lifted punishments are still here</h2>
 * Because Core never deletes one: lifting a ban adds the lifting to it and leaves the ban. That is the
 * difference between a record and a set of flags — a ban lifted on appeal in March is still the reason
 * the one in June is a second offence, and a history that hid it would make every ladder start again at
 * the first rung after every successful appeal.
 *
 * <p>So they are shown, and marked as lifted, with who lifted them and why.
 */
public final class HistoryMenu extends ModerationList<RecordEntry> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final UUID subject;
    private final String subjectName;

    public HistoryMenu(ModerationServices services, Player viewer, Menu parent, UUID subject,
                       String subjectName) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Record — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return "Record";
    }

    @Override
    protected List<RecordEntry> entries() {
        // Punishments *and* reports. A page called "Record" that listed only punishments read as
        // complete while leaving half of itself out — somebody reported four times and never punished
        // looked spotless to a moderator who had opened this precisely to avoid missing something.
        return RecordEntry.merge(services().punishmentService().history(subject),
                services().reports().about(subject));
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing on record",
                "<gray>" + subjectName + " has never been punished or reported here.");
    }

    @Override
    protected ItemStack icon(RecordEntry entry) {
        return switch (entry) {
            case RecordEntry.Punished punished -> punishmentIcon(punished.punishment());
            case RecordEntry.Reported reported -> reportIcon(reported.report());
        };
    }

    /**
     * A report on the record.
     *
     * <p>Deliberately unlike a punishment: paper, not a barrier, and the word "alleged" in front of the
     * text. A report is an accusation, and four of them from one angry player must not read like four
     * findings of guilt just because they share a page with things that are.
     */
    private ItemStack reportIcon(Report report) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + report.text());
        lore.add("<dark_gray>" + Times.describe(Duration.between(report.at(), Instant.now()))
                + " ago");
        lore.add("");
        lore.add("<dark_gray>Reported by <gray>" + nameOf(report.reporter(), report.reporterName()));
        lore.add("<dark_gray>" + report.state().describe());
        if (report.handler() != null) {
            lore.add("<dark_gray>Handled by <gray>"
                    + nameOf(report.handler(), report.handlerName()));
        }
        lore.add("");
        lore.add("<dark_gray>An accusation, not a finding.");

        return Icons.of(report.state().icon(), "<aqua>Reported", lore);
    }

    private ItemStack punishmentIcon(Punishment past) {
        Instant now = Instant.now();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + past.reason());
        lore.add("<dark_gray>" + Times.describe(Duration.between(past.givenAt(), now)) + " ago");
        lore.add("<dark_gray>for " + past.length());
        // Who did it. The record showed what happened and never by whom, which is the first thing
        // anybody asks when they disagree with an entry — and the answer was already stored.
        lore.add("<dark_gray>by <gray>" + nameOf(past.moderator(), null));

        if (past.liftedAt() != null) {
            lore.add("");
            lore.add("<green>Lifted by " + nameOf(past.lifter(), null) + " — "
                    + past.liftReason().orElse("no reason given"));
        } else if (past.isActiveAt(now)) {
            lore.add("");
            lore.add("<red>Still in force" + past.remainingAt(now)
                    .map(left -> " — " + Times.brief(left) + " left").orElse(""));
        }
        // Which preset it was, when it was one. A record full of free text is one nothing can count,
        // and saying so on the entry is how that becomes visible rather than merely true.
        Optional<Reason> preset = services().reasons().matching(past.reason());
        lore.add("");
        lore.add(preset.map(reason -> "<dark_gray>" + reason.severity().describe() + " — counts "
                        + "towards the ladder")
                .orElse("<dark_gray>Typed by hand — counts towards nothing"));

        return Icons.of(iconFor(past), "<yellow>" + past.kind().past(), lore);
    }

    private static Material iconFor(Punishment past) {
        return switch (past.kind()) {
            case BAN -> Material.BARRIER;
            case MUTE -> Material.PAPER;
            case KICK -> Material.LEATHER_BOOTS;
            case WARNING -> Material.YELLOW_BANNER;
            case FREEZE -> Material.PACKED_ICE;
        };
    }

    /**
     * A name for somebody who may be offline, renamed, or gone.
     *
     * <p>Falls back to what was stored at the time and then to "the console", which is what a null
     * actor means everywhere else here. Never a raw UUID: a record nobody can read is a record nobody
     * checks.
     */
    private String nameOf(UUID who, String storedName) {
        if (who == null) {
            return "the console";
        }
        if (storedName != null && !storedName.isBlank()) {
            return storedName;
        }
        String known = services().server().getOfflinePlayer(who).getName();
        return known != null ? known : "somebody who has left";
    }

    @Override
    protected void onClick(RecordEntry entry, InventoryClickEvent event) {
        // A record is read, not edited. Lifting happens on the player's own page, where the button
        // says what it does and asks first — an entry that lifted itself on a click would be the one
        // irreversible thing on a page with no confirmation.
        if (!may(ModerationPermission.HISTORY)) {
            tell("moderation.no-permission");
            return;
        }
        switch (entry) {
            case RecordEntry.Punished punished -> tell("moderation.record-entry",
                    "what", punished.punishment().kind().past(),
                    "reason", punished.punishment().reason(),
                    "length", punished.punishment().length(),
                    "by", nameOf(punished.punishment().moderator(), null));
            case RecordEntry.Reported reported -> tell("moderation.record-report",
                    "text", reported.report().text(),
                    "by", nameOf(reported.report().reporter(), reported.report().reporterName()),
                    "state", reported.report().state().describe());
        }
    }

    @Override
    public String describe() {
        return "what has happened to somebody: punishments and reports, lifted ones included";
    }
}
