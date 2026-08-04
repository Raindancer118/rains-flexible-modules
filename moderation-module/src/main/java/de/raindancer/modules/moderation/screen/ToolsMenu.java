package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.command.VitalsCommand;
import de.raindancer.modules.moderation.model.ModerationPermission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Flight, invulnerability and one-hit-kill, for one person.
 *
 * <h2>Why these are on a page of their own</h2>
 * Because they are not punishments and do not belong beside the ban door — nothing here goes on anybody's
 * record as something they did wrong. They are the three switches a moderator flips while <em>working</em>:
 * fly up to look at a build, survive the fall, clear the mob farm somebody left running.
 *
 * <h2>Why each one says it does not survive a restart</h2>
 * Because it does not, and the alternative to saying so is a moderator who assumes it does, jumps off
 * something tall after a restart, and blames the plugin. See {@code PlayerPowers}: an invincible player
 * nobody remembers granting it to is indistinguishable from a bug in the damage system.
 */
public final class ToolsMenu extends ModerationScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final UUID subject;
    private final String subjectName;

    public ToolsMenu(ModerationServices services, Player viewer, Menu parent, UUID subject,
                     String subjectName) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Tools — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return "Tools";
    }

    /** Whether the viewer is looking at themselves, which needs no permission over the subject. */
    private boolean themselves() {
        return viewer.getUniqueId().equals(subject);
    }

    @Override
    protected void render() {
        Player them = services().server().getPlayer(subject);
        boolean here = them != null;

        band(MenuLayout.WHO, 4, Icons.head(subject, "<white>" + subjectName,
                here ? "<green>Here now." : "<gray>Not on the server."));

        // ── flight ────────────────────────────────────────────────────────────────────────────
        boolean flying = here && them.getAllowFlight();
        tool(2, ModerationPermission.FLY, Material.FEATHER, "Flight", flying, here,
                "<gray>The game remembers this one — it survives a restart.",
                () -> {
                    services().players().flight(subject, !flying);
                    services().staff().recordSelfTool(viewer, subject, subjectName,
                            de.raindancer.modules.moderation.command.SelfToolCommand.Tool.FLY,
                            !flying);
                });

        // ── invulnerability ───────────────────────────────────────────────────────────────────
        boolean invulnerable = services().powers().isInvulnerable(subject);
        tool(4, ModerationPermission.GOD, Material.TOTEM_OF_UNDYING, "Invulnerable", invulnerable, here,
                "<dark_gray>Goes off when they log out.",
                () -> {
                    boolean nowOn = services().powers().toggleGod(subject);
                    services().staff().recordSelfTool(viewer, subject, subjectName,
                            de.raindancer.modules.moderation.command.SelfToolCommand.Tool.GOD, nowOn);
                });

        // ── one hit ───────────────────────────────────────────────────────────────────────────
        boolean oneHit = services().powers().killsInOneHit(subject);
        tool(6, ModerationPermission.INSTAKILL, Material.NETHERITE_SWORD, "One-hit kill", oneHit, here,
                "<red>Everything they hit dies. <dark_gray>Mind the livestock.",
                () -> {
                    boolean nowOn = services().powers().toggleInstakill(subject);
                    services().staff().recordSelfTool(viewer, subject, subjectName,
                            de.raindancer.modules.moderation.command.SelfToolCommand.Tool.INSTAKILL,
                            nowOn);
                });

        // ── instant breaking ──────────────────────────────────────────────────────────────────
        boolean breaking = services().powers().breaksInstantly(subject);
        tool(8, ModerationPermission.INSTABREAK, Material.NETHERITE_PICKAXE, "Instant breaking",
                breaking, here,
                "<gray>Blocks give way at once. <dark_gray>Claims still refuse them.",
                () -> {
                    boolean nowOn = services().powers().toggleInstaBreak(subject);
                    services().staff().recordSelfTool(viewer, subject, subjectName,
                            de.raindancer.modules.moderation.command.SelfToolCommand.Tool.INSTABREAK,
                            nowOn);
                });

        // ── heal, feed, hurt, starve ──────────────────────────────────────────────────────────
        // On their own row because they are not switches: each one happens when clicked and there is
        // nothing to show as "on". Drawn beside the toggles anyway, because from the moderator's side
        // they are the same kind of thing — something you do to somebody standing in front of you.
        int column = 2;
        for (VitalsCommand.Vital vital : VitalsCommand.Vital.values()) {
            vital(column, vital, here);
            column += 2;
        }
    }

    /**
     * One thing that happens when clicked.
     *
     * <p>The harmful two confirm first. Not ceremony: heal and hurt sit two slots apart on the same row
     * and look alike at a glance, and the cost of a mis-click is somebody losing half their health in a
     * fight — which they will report as the server cheating, and nobody will be able to tell them
     * otherwise.
     */
    private void vital(int column, VitalsCommand.Vital vital, boolean here) {
        boolean allowed = may(vital.permission()) && here
                && (themselves() || canAct(subject, vital.permission()).isAllowed());

        List<String> lore = new ArrayList<>();
        lore.add((vital.harmful() ? "<red>" : "<green>") + vital.describe() + ".");
        if (vital.harmful()) {
            lore.add("<dark_gray>Asks first.");
        }
        lore.add("");
        lore.add(allowed ? "<dark_gray>Click to do it."
                : here ? "<dark_gray>Not yours to do."
                : "<dark_gray>They are not on the server.");

        band(MenuLayout.LAND, column, allowed,
                Icons.of(vital.icon(), (vital.harmful() ? "<red>" : "<green>") + label(vital), lore),
                here ? "Not yours to do" : "They are not here",
                click -> {
                    // Re-asked at the click: the page may have been open for minutes, and a demotion
                    // in between has to take effect on the button as well as on the command.
                    if (!may(vital.permission())
                            || (!themselves() && canAct(subject, vital.permission()).isRefused())) {
                        tell("moderation.no-permission");
                        return;
                    }
                    if (services().server().getPlayer(subject) == null) {
                        tell("moderation.not-here", "player", subjectName);
                        return;
                    }
                    if (vital.harmful()) {
                        new ConfirmScreen(services(), viewer, this,
                                "<red>" + label(vital) + " — " + subjectName + "?",
                                List.of("<gray>" + vital.describe() + ".",
                                        "<dark_gray>Nothing about this goes on their record."),
                                () -> doVital(vital)).open();
                    } else {
                        doVital(vital);
                    }
                });
    }

    /** "Heal", "Feed" — the enum's own word, with a capital. */
    private static String label(VitalsCommand.Vital vital) {
        String word = vital.word();
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    /** Actually does it, and says so — the same wording the command uses. */
    private void doVital(VitalsCommand.Vital vital) {
        switch (vital) {
            case HEAL -> services().players().heal(subject);
            case FEED -> services().players().feed(subject);
            case HURT -> services().players().damage(subject, 10.0);
            case STARVE -> services().players().food(subject, 6);
        }
        services().staff().recordVital(viewer, subject, subjectName, vital);
        tell(vital.messageKey() + (themselves() ? ".done" : ".done-other"), "player", subjectName);
        if (!themselves()) {
            Player them = services().server().getPlayer(subject);
            if (them != null) {
                services().messages().send(them, vital.messageKey() + ".received",
                        "player", viewer.getName());
            }
        }
        changed();
    }

    /**
     * One switch.
     *
     * <p>Greyed with the reason when the viewer may not use it or the person is not here — rather than
     * absent, so the page is the same shape whoever opens it.
     */
    private void tool(int column, ModerationPermission permission, Material icon, String name,
                      boolean on, boolean here, String note, Runnable flip) {
        boolean allowed = may(permission) && here && (themselves() || canAct(subject, permission)
                .isAllowed());

        List<String> lore = new ArrayList<>();
        lore.add(on ? "<green>On." : "<gray>Off.");
        lore.add(note);
        lore.add("");
        lore.add(allowed
                ? "<dark_gray>Click to turn it " + (on ? "off." : "on.")
                : here ? "<dark_gray>Not yours to change."
                : "<dark_gray>They are not on the server.");

        band(MenuLayout.RULES, column, allowed,
                Icons.of(icon, (on ? "<green>" : "<gray>") + name, lore),
                here ? "Not yours to change" : "They are not here",
                click -> {
                    // Re-asked at the click: the page may have been open for minutes.
                    if (!may(permission) || (!themselves() && canAct(subject, permission).isRefused())) {
                        tell("moderation.no-permission");
                        return;
                    }
                    if (services().server().getPlayer(subject) == null) {
                        tell("moderation.not-here", "player", subjectName);
                        return;
                    }
                    flip.run();
                    changed();
                });
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>These are working tools, not punishments — nothing here goes on a record.",
                "<gray>Invulnerability and one-hit-kill end when the player logs out.",
                "<gray>Every switch is written to the audit trail, including on yourself.");
    }

    @Override
    public String describe() {
        return "flight, invulnerability and one-hit-kill for one person";
    }
}
