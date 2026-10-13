package de.raindancer.modules.ranks.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.ranks.RanksServices;
import de.raindancer.modules.ranks.model.Rank;
import de.raindancer.modules.ranks.service.RankService.Outcome;
import de.raindancer.modules.ranks.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** {@code /rank manage}: every rank, most senior first. Click one to restyle or change it; a new one is made from the toolbar. */
public final class RankAdminMenu extends PaginatedMenu<Rank> implements IRanksScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final RanksServices services;

    public RankAdminMenu(RanksServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Manage ranks");
    }

    @Override
    public String breadcrumb() {
        return "Manage ranks";
    }

    @Override
    protected List<Rank> entries() {
        return services.ranks().ranks().stream().sorted(java.util.Comparator.comparingInt(Rank::weight).reversed()
                .thenComparing(Rank::id)).toList();
    }

    @Override
    protected ItemStack icon(Rank rank) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + MINI.escapeTags(rank.title()) + " <dark_gray>(" + rank.id() + ")");
        lore.add("<gray>Weight <white>" + rank.weight() + "<gray>" + (rank.team() ? ", on the team." : "."));
        lore.add("<gray>Held by <white>" + services.ranks().holdersOf(rank).size() + "<gray>.");
        lore.add("");
        lore.add("<yellow>Click<gray> to change it.");
        return Icons.of(rank.team() ? Material.GOLDEN_HELMET : Material.LEATHER_HELMET, rank.tag(), lore);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<red>There are no ranks", "<gray>Make the first one from the toolbar.");
    }

    @Override
    protected void onClick(Rank rank, InventoryClickEvent event) {
        if (!viewer.hasPermission(PermissionNodes.ADMIN)) {
            viewer.closeInventory();
            return;
        }
        new RankEditMenu(services, viewer, this, rank.id()).open();
    }

    @Override
    protected void render() {
        if (!viewer.hasPermission(PermissionNodes.ADMIN)) {
            viewer.closeInventory();
            return;
        }
        super.render();
        toolbar(4, Icons.of(Material.ANVIL, "<white>New rank",
                "<gray>Starts at weight 50 with the tag you type.", "", "<gray>Click, then type its id and its tag,", "<gray>e.g. <white>vip VIP</white>."),
                click -> IRanksScreen.ask(services, viewer, this, "ranks.ask.new", typed -> {
                    if (!viewer.hasPermission(PermissionNodes.ADMIN)) {
                        return;
                    }
                    String[] words = typed.split("\\s+", 2);
                    if (words.length < 2) {
                        services.messages().send(viewer, "ranks.usage-manage");
                        return;
                    }
                    answer(services.ranks().create(words[0].toLowerCase(java.util.Locale.ROOT), words[1], 50));
                }));
    }

    private void answer(Outcome outcome) {
        switch (outcome) {
            case DONE -> services.messages().send(viewer, "ranks.managed");
            case ALREADY -> services.messages().send(viewer, "ranks.invalid");
            case NOT_SAVED -> services.messages().send(viewer, "ranks.not-saved");
            default -> services.messages().send(viewer, "ranks.invalid");
        }
    }

    @Override
    public String describe() {
        return "every rank, to restyle or change";
    }
}
