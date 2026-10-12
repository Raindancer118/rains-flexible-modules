package de.raindancer.modules.guilds.screen;

import de.raindancer.core.platform.util.Times;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Contract;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;

/** The guild's contracts for this week, how far along each is, and when the week turns over. */
public final class ContractsMenu extends PaginatedMenu<Contract> implements IGuildsScreen {

    private final GuildsServices services;

    public ContractsMenu(GuildsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Contracts", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Contracts";
    }

    @Override
    protected List<Contract> entries() {
        return services.guilds().guildOf(viewer.getUniqueId()).map(services.xp()::contracts).orElse(List.of());
    }

    @Override
    protected ItemStack icon(Contract contract) {
        long left = Math.max(0, services.xp().weekEnds() - System.currentTimeMillis());
        return Icons.of(contract.done() ? Material.LIME_DYE : contract.source().icon(),
                (contract.done() ? "<green>✔ " : "<white>") + contract.title(),
                "<gray>" + contract.progress() + " of " + contract.goal() + " " + contract.source().unit(),
                GuildMenu.bar(contract.fraction()),
                "<gray>Pays <white>" + contract.reward() + "<gray> experience to the guild",
                contract.done() ? "<green>Done — paid." : "<dark_gray>New contracts in " + Times.describe(Duration.ofMillis(left)),
                "", "<gray>Click to tell the guild how far it is.");
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No contracts", "<gray>Contracts are switched off, or you", "<gray>are in no guild.");
    }

    @Override
    protected void onClick(Contract contract, InventoryClickEvent event) {
        services.guilds().guildOf(viewer.getUniqueId()).ifPresent(guild -> services.notifier().members(guild,
                "guilds.contract.status", "player", services.notifier().name(viewer.getUniqueId()),
                "contract", contract.title(), "progress", contract.progress(), "goal", contract.goal()));
        services.messages().send(viewer, "guilds.contract.shared");
    }

    @Override
    public String describe() {
        return "the guild's contracts this week";
    }
}
