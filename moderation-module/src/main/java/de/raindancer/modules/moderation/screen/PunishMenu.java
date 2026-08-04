package de.raindancer.modules.moderation.screen;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Reason;
import org.bukkit.Material;

import java.time.Duration;
import de.raindancer.modules.moderation.model.Sentence;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Choosing why.
 *
 * <h2>What the list is actually for</h2>
 * Not saving typing — making the record countable. The same offence written eleven ways cannot be
 * counted, so nothing can tell a first offence from a fifth, and every length is somebody's guess on the
 * day. Picking from a list is what lets {@code EscalationRule} say which rung this person is on, and the
 * lore says so before the click: each reason shows what it would cost <em>this</em> player, not what it
 * costs in general.
 *
 * <h2>Cheapest first</h2>
 * The list is ordered by severity with the worst last, so a misclick lands on the reason that costs the
 * least. That is the same reason the danger slot is flanked by navigation.
 */
public final class PunishMenu extends ModerationList<Reason> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final UUID subject;
    private final String subjectName;
    private final PunishmentKind kind;

    public PunishMenu(ModerationServices services, Player viewer, Menu parent, UUID subject,
                      String subjectName, PunishmentKind kind) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName;
        this.kind = kind;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Why — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return "Reasons";
    }

    @Override
    protected List<Reason> entries() {
        return services().reasons().forKind(kind);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(org.bukkit.Material.COBWEB, "<gray>No reasons for this",
                "<gray>Nothing in the catalogue hands out a " + kind.name().toLowerCase(Locale.ROOT)
                        + ".",
                "<dark_gray>Use the command with a length instead.");
    }

    @Override
    protected ItemStack icon(Reason reason) {
        Sentence suggested = services().punishmentService().suggest(reason, subject);
        // Brought within what *this* viewer may hand out. A mod picking Griefing, whose ladder starts
        // at three days, is shown the day they can actually give rather than a number that will be
        // refused after two more clicks.
        if (reason.kind() == PunishmentKind.BAN) {
            suggested = services().banLimitRule().clamp(viewer.getUniqueId(), suggested);
        }
        int priors = services().punishmentService().priorOffences(reason, subject);

        List<String> lore = new ArrayList<>();
        lore.add("<" + reason.severity().colour() + ">" + reason.severity().describe());
        lore.add("");
        if (kind.isLasting()) {
            lore.add("<gray>Would be <white>" + suggested.describe() + "</white>.");
        }
        if (reason.escalates()) {
            lore.add(priors == 0
                    ? "<dark_gray>Their first."
                    : "<dark_gray>Their " + ordinal(priors + 1) + " — the ladder has been climbed.");
        }
        lore.add("");
        lore.add(kind.isLasting()
                ? "<dark_gray>Click to choose how long."
                : "<dark_gray>Click to do it. Asks first.");

        return Icons.of(reason.severity().icon(), "<yellow>" + reason.label(), lore);
    }

    @Override
    protected void onClick(Reason reason, InventoryClickEvent event) {
        var verdict = services().staffRule().canAct(viewer.getUniqueId(), subject, permissionFor());
        if (verdict.isRefused()) {
            verdict.refusal().ifPresent(this::tell);
            return;
        }
        if (kind.isLasting()) {
            new DurationMenu(services(), viewer, this, subject, subjectName, reason).open();
            return;
        }
        // A kick and a warning have no length to choose, so the confirmation is the whole second step.
        new ConfirmScreen(services(), viewer, this,
                "<yellow>" + reason.label() + " — " + subjectName + "?",
                List.of("<gray>" + kindDescription()),
                () -> handOut(reason)).open();
    }

    private void handOut(Reason reason) {
        services().punishmentService().punish(viewer.getUniqueId(), viewer.getName(), subject,
                subjectName, kind, Sentence.forEver(), reason.label());
        tell("moderation.punished", "player", subjectName, "what", kind.past(),
                "length", reason.label());
        viewer.closeInventory();
    }

    @Override
    protected void render() {
        super.render();
        // The way out of the catalogue. Before this the 41 presets were the only thing the screens
        // offered, so anything they did not cover had to be done from the command line — the half of
        // the plugin a moderator on a phone, or mid-incident, is least able to reach.
        toolbar(2, Icons.of(Material.WRITABLE_BOOK, "<yellow>Type your own reason",
                        "<gray>For anything the list does not cover.",
                        "<dark_gray>You will be asked in chat.",
                        "",
                        "<dark_gray>Counts towards no ladder."),
                click -> askForOne());
    }

    /** The chat prompt, which is Core's — one question, one answer, and a timeout. */
    private void askForOne() {
        var verdict = services().staffRule().canAct(viewer.getUniqueId(), subject, permissionFor());
        if (verdict.isRefused()) {
            verdict.refusal().ifPresent(this::tell);
            return;
        }
        viewer.closeInventory();
        tell("moderation.type-a-reason", "player", subjectName,
                "what", kind.name().toLowerCase(Locale.ROOT));

        services().prompts().ask(viewer.getUniqueId(), "moderation", Duration.ofSeconds(120),
                typed -> {
                    Reason theirs;
                    try {
                        theirs = Reason.typedByHand(kind, typed);
                    } catch (IllegalArgumentException nothingUsable) {
                        tell("moderation.nothing-typed");
                        return;
                    }
                    // Asked again: the prompt is answered outside the menu, minutes may have passed,
                    // and a demotion in between has to take effect here as well as on the button.
                    var stillAllowed = services().staffRule()
                            .canAct(viewer.getUniqueId(), subject, permissionFor());
                    if (stillAllowed.isRefused()) {
                        stillAllowed.refusal().ifPresent(this::tell);
                        return;
                    }
                    if (kind.isLasting()) {
                        // Straight to the length: a typed reason has no ladder to suggest one.
                        // Parented on this page, so Back goes to the reason list rather than leaving
                        // Close as the only way out — see ScreenGrammarTest, which caught exactly that.
                        new DurationMenu(services(), viewer, this, subject, subjectName, theirs)
                                .open();
                        return;
                    }
                    handOut(theirs);
                },
                () -> tell("moderation.nothing-typed"));
    }

    private String kindDescription() {
        return kind == PunishmentKind.KICK
                ? "They are thrown off now and may come straight back."
                : "It goes on their record and stops nothing.";
    }

    private ModerationPermission permissionFor() {
        return switch (kind) {
            case BAN -> ModerationPermission.TEMPBAN;   // see CategoryMenu#permission
            case MUTE -> ModerationPermission.MUTE;
            case KICK -> ModerationPermission.KICK;
            case WARNING -> ModerationPermission.WARN;
            case FREEZE -> ModerationPermission.FREEZE;
        };
    }

    /** "second", "third" — because "their 2th" is the sort of thing nobody fixes afterwards. */
    private static String ordinal(int count) {
        return switch (count) {
            case 1 -> "first";
            case 2 -> "second";
            case 3 -> "third";
            case 4 -> "fourth";
            case 5 -> "fifth";
            default -> count + "th";
        };
    }

    @Override
    public String describe() {
        return "the reasons this server hands out a punishment for, cheapest first";
    }
}
