package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Who a player is reporting.
 *
 * <h2>Why this exists at all</h2>
 * Because a player who has just been griefed should not have to know the exact spelling of somebody's
 * name to tell anybody about it. A capital letter, an underscore, a zero for an O — and the report
 * command answers "the server has never seen anybody called that", which reads as being told they are
 * wrong about what just happened to them.
 *
 * <h2>Why it shows only who is here</h2>
 * This is the one screen in the module a player with no permission at all can open, so it deliberately
 * is <em>not</em> the staff directory. Reporting somebody who logged off last March is not a thing
 * anybody needs, and the full list of everybody the server has ever seen is not a list to hand a player.
 * Somebody who genuinely needs to report an absent player can still type the name.
 */
public final class WhoToReportMenu extends ModerationList<Player> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    public WhoToReportMenu(ModerationServices services, Player viewer, Menu parent) {
        super(services, viewer, parent);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Report who?");
    }

    @Override
    public String breadcrumb() {
        return "Report";
    }

    @Override
    protected List<Player> entries() {
        List<Player> everybody = new ArrayList<>();
        for (Player who : services().server().getOnlinePlayers()) {
            if (who.equals(viewer)) {
                continue;   // nobody reports themselves, so they are not offered
            }
            // Somebody vanished is invisible for a reason, and a player who could pick them out of this
            // list would learn that a moderator was standing next to them.
            if (services().vanish().isVanished(who.getUniqueId())) {
                continue;
            }
            everybody.add(who);
        }
        everybody.sort((left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        return everybody;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nobody else is here",
                "<gray>You are the only player online.",
                "<dark_gray>Use <white>/report <name> …</white> for somebody who has left.");
    }

    @Override
    protected ItemStack icon(Player who) {
        return Icons.head(who.getUniqueId(), "<yellow>" + who.getName(),
                List.of("<gray>Report this player.",
                        "",
                        "<dark_gray>You will be asked what for,",
                        "<dark_gray>and then what happened."));
    }

    @Override
    protected void onClick(Player who, InventoryClickEvent event) {
        // Directly, handing this page over as the parent — so Back returns to the list rather than
        // closing the inventory and leaving a player who picked the wrong name with nothing.
        new ReportCategoryMenu(services(), viewer, this, who.getUniqueId(), who.getName()).open();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Pick who, then what for, then say what happened.",
                "<gray>A report goes to the staff — it is not a punishment by itself.",
                "<gray>For somebody who has logged off, type <white>/report <name> …</white>");
    }

    @Override
    public String describe() {
        return "who a player is reporting — everybody online but themselves";
    }
}
