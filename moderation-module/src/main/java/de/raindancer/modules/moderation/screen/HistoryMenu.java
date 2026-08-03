package de.raindancer.modules.moderation.screen;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Reason;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
public final class HistoryMenu extends ModerationList<Punishment> {

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
    protected List<Punishment> entries() {
        List<Punishment> everything = new ArrayList<>(
                services().punishmentService().history(subject));
        // Newest first: the useful end of a long record is the recent end, and a moderator opening
        // this is nearly always asking "what happened lately".
        everything.sort(Comparator.comparing(Punishment::givenAt).reversed());
        return everything;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing on record",
                "<gray>" + subjectName + " has never been punished here.");
    }

    @Override
    protected ItemStack icon(Punishment past) {
        Instant now = Instant.now();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + past.reason());
        lore.add("<dark_gray>" + Times.describe(Duration.between(past.givenAt(), now)) + " ago");
        lore.add("<dark_gray>for " + past.length());

        if (past.liftedAt() != null) {
            lore.add("");
            lore.add("<green>Lifted — " + past.liftReason().orElse("no reason given"));
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

    @Override
    protected void onClick(Punishment past, InventoryClickEvent event) {
        // A record is read, not edited. Lifting happens on the player's own page, where the button
        // says what it does and asks first — an entry that lifted itself on a click would be the one
        // irreversible thing on a page with no confirmation.
        if (!may(ModerationPermission.HISTORY)) {
            tell("moderation.no-permission");
            return;
        }
        tell("moderation.record-entry", "what", past.kind().past(), "reason", past.reason(),
                "length", past.length());
    }

    @Override
    public String describe() {
        return "what has happened to somebody, lifted punishments included";
    }
}
