package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Everybody this server has ever learnt anything about, ranked by how worth checking they are.
 *
 * <h2>What earns somebody a place on this list</h2>
 * Having actually been found mining a watched ore at least once — nobody else. Everybody who has ever
 * broken a single block of anything has a {@code PlayerMiningProfile}, ore or not, and a leaderboard
 * built from that would list the whole server for a question most of them have never given a reason
 * to ask. Showing somebody here at a low percentage would read as "checked and found mostly clean"
 * for somebody nobody has actually looked at, which is a worse answer than simply leaving them off.
 *
 * <h2>What the number is, and what it is not</h2>
 * See {@code PlayerMiningProfile#probabilityPercent} for how it is built. It orders this list and
 * nothing else — it is not a verdict, not evidence on its own, and not something anybody is punished
 * for. Clicking an entry opens the same {@link XrayReviewMenu} a report's own investigation would, so
 * the number is never the last thing anybody sees before deciding what to do with it.
 */
public final class XraySuspicionMenu extends ModerationList<UUID> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final List<UUID> ranked;

    public XraySuspicionMenu(ModerationServices services, Player viewer, Menu parent) {
        super(services, viewer, parent);
        // Ranked once, on open, rather than recomputed on every render: a moderator paging through
        // this should see one consistent order throughout, not one that reshuffles under them as
        // people keep mining while the page is open.
        this.ranked = new ArrayList<>(services.xrayDetection().everybodyWorthReviewing());
        ranked.sort(Comparator.comparingInt(services.xrayDetection()::probabilityFor).reversed());
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Mining suspicion — <white>everybody</white>");
    }

    @Override
    public String breadcrumb() {
        return "X-ray";
    }

    @Override
    protected List<UUID> entries() {
        return ranked;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nobody has mined anything watched yet",
                "<gray>Nothing to rank until somebody breaks a watched ore block.");
    }

    @Override
    protected ItemStack icon(UUID who) {
        OfflinePlayer player = services().server().getOfflinePlayer(who);
        String name = player.getName() == null ? "somebody who has left" : player.getName();
        int probability = services().xrayDetection().probabilityFor(who);
        int observed = services().xrayDetection().approachesFor(who).size();

        List<String> lore = new ArrayList<>();
        // Inlined rather than a helper of its own — see XrayReviewMenu's own note on why
        // WordingTest forbids a method that returns a literal String of markup.
        String colour = probability >= 80 ? "<red>" : probability >= 50 ? "<yellow>" : "<green>";
        lore.add("<gray>Probability: " + colour + probability + "%");
        lore.add("<dark_gray>" + observed + " ore block(s) currently remembered in detail.");
        lore.add("");
        lore.add("<dark_gray>Click to see where each one came from.");

        return Icons.head(who, "<yellow>" + name, lore);
    }

    @Override
    protected void onClick(UUID who, InventoryClickEvent event) {
        if (!may(ModerationPermission.REPORTS)) {
            tell("moderation.no-permission");
            return;
        }
        OfflinePlayer player = services().server().getOfflinePlayer(who);
        String name = player.getName() == null ? "somebody who has left" : player.getName();
        new XrayReviewMenu(services(), viewer, this, who, name).open();
    }

    @Override
    protected void render() {
        super.render();
        toolbar(6, may(ModerationPermission.CONFIG),
                Icons.of(Material.DIAMOND_PICKAXE, "<yellow>Watched ores",
                        "<gray>" + services().config().xrayOres().size() + " watched right now.",
                        "<dark_gray>Click to add or remove one."),
                "An admin's decision",
                click -> new XrayOrePickerMenu(services(), viewer, this).open());
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Ranked by probability, most worth checking first.",
                "<gray>A number here is a reason to look, never a reason to ban on its own.");
    }

    @Override
    public String describe() {
        return "everybody this server has learnt anything about, ranked by how worth checking they are";
    }
}
