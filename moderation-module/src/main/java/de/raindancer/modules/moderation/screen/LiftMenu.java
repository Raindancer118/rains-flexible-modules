package de.raindancer.modules.moderation.screen;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.util.Players;
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
import java.util.Locale;
import java.util.UUID;

/**
 * Who is currently banned, muted or frozen — and the way to end it.
 *
 * <h2>Why this is not the player picker</h2>
 * Because the question is different. Punishing starts from "who", and the answer could be anybody the
 * server has ever seen; lifting starts from "who is serving one", and the answer is nearly always a
 * handful. Sending somebody through nine hundred names to be told "nothing to lift" wastes the one
 * thing the screen was opened to answer, and it is a list nobody can scan for the name they half
 * remember.
 *
 * <h2>Why it shows the sentence rather than just the name</h2>
 * A moderator lifting a ban is usually deciding <em>whether</em> to, and "permanent, four days ago, for
 * griefing" is the decision. Making them open a second page to find out is how the lift happens
 * uninformed.
 */
public final class LiftMenu extends ModerationList<Punishment> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final PunishmentKind kind;
    private final ModerationPermission permission;

    public LiftMenu(ModerationServices services, Player viewer, Menu parent, PunishmentKind kind,
                    ModerationPermission permission) {
        super(services, viewer, parent);
        this.kind = kind;
        this.permission = permission;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Currently " + kind.past());
    }

    @Override
    public String breadcrumb() {
        return "Lift";
    }

    @Override
    protected List<Punishment> entries() {
        List<Punishment> serving = new ArrayList<>(services().punishmentService().activeOf(kind));
        // Newest first, like the record: the one somebody has come to lift is usually the one just
        // handed out, and an argument about it is freshest then.
        serving.sort(Comparator.comparing(Punishment::givenAt).reversed());
        return serving;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nobody is " + kind.past(),
                "<gray>There is nothing of this kind to lift.",
                "<dark_gray>Punishments that have already ended stay on the record.");
    }

    @Override
    protected ItemStack icon(Punishment serving) {
        Instant now = Instant.now();
        String name = nameOf(serving.target());

        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + serving.reason());
        lore.add("<dark_gray>" + Times.describe(Duration.between(serving.givenAt(), now)) + " ago");
        lore.add("<dark_gray>for " + serving.length());
        lore.add("<dark_gray>by <gray>" + nameOf(serving.moderator()));
        lore.add("");
        lore.add("<red>" + serving.remainingAt(now)
                .map(left -> Times.brief(left) + " left")
                .orElse("Does not end on its own"));
        lore.add("");
        lore.add(may(permission) ? "<dark_gray>Click to lift it. Asks first."
                : "<dark_gray>Not yours to lift.");

        return Icons.head(serving.target(), "<yellow>" + name, lore);
    }

    @Override
    protected void onClick(Punishment serving, InventoryClickEvent event) {
        String name = nameOf(serving.target());
        // Re-asked on the click. The page was rendered at least one click ago and a permission can be
        // taken away in between — the greyed state of an old render is not a permission check.
        if (!may(permission)) {
            tell("moderation.no-permission");
            return;
        }
        var verdict = services().staffRule().canAct(viewer.getUniqueId(), serving.target(),
                permission);
        if (verdict.isRefused()) {
            verdict.refusal().ifPresent(this::tell);
            return;
        }
        new ConfirmScreen(services(), viewer, this,
                "<green>Lift the " + kind.name().toLowerCase(Locale.ROOT) + " — " + name + "?",
                List.of("<gray>They will be able to " + afterLifting() + " again.",
                        "<dark_gray>It stays on their record either way."),
                () -> lift(serving, name)).open();
    }

    private void lift(Punishment serving, String name) {
        var stillAllowed = services().staffRule().canAct(viewer.getUniqueId(), serving.target(),
                permission);
        if (stillAllowed.isRefused()) {
            stillAllowed.refusal().ifPresent(this::tell);
            return;
        }
        boolean lifted = services().punishmentService().lift(viewer.getUniqueId(), viewer.getName(),
                serving.target(), name, kind, "lifted from the menu");
        if (lifted) {
            tell("moderation.lifted", "player", name, "what", kind.past());
        } else {
            // Somebody else got there first, or it expired while the confirmation was open. Saying so
            // beats a silent no-op that reads as the button being broken.
            tell("moderation.nothing-to-lift", "player", name);
        }
        // Redrawn, so the entry that was just lifted leaves the list rather than sitting there
        // looking liftable — a button that does nothing on a second click reads as broken.
        open();
    }

    /** What they can do again, in the words somebody would actually use. */
    private String afterLifting() {
        return switch (kind) {
            case BAN -> "join";
            case MUTE -> "talk";
            case FREEZE -> "move";
            case KICK, WARNING -> "carry on";
        };
    }

    private String nameOf(UUID who) {
        if (who == null) {
            return "the console";
        }
        return Players.nameOf(services().server().getOfflinePlayer(who));
    }

    @Override
    public String describe() {
        return "everybody currently " + kind.past() + ", and the way to end it";
    }
}
