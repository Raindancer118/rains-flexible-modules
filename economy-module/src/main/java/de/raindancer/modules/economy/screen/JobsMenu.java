package de.raindancer.modules.economy.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Contract;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;

/** Everybody you employ and everybody who employs you. Click one to end it. */
public final class JobsMenu extends PaginatedMenu<Contract> implements IEconomyScreen {

    private final EconomyServices services;

    public JobsMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Jobs");
    }

    @Override
    public String breadcrumb() {
        return "Jobs";
    }

    @Override
    protected List<Contract> entries() {
        return services.hire().contractsOf(viewer.getUniqueId());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No jobs", "<dark_gray>/hire <player> <wage> <every> [job]");
    }

    @Override
    protected ItemStack icon(Contract job) {
        boolean employer = job.employer().equals(viewer.getUniqueId());
        String other = employer ? job.employeeName() : job.employerName();
        Duration next = Duration.ofMillis(Math.max(0, job.nextAt() - System.currentTimeMillis()));
        return Icons.head(employer ? job.employee() : job.employer(),
                (employer ? "<yellow>You employ " : "<green>You work for ") + other,
                job.title().isEmpty() ? "" : "<gray>As <white>" + MiniMessage.miniMessage().escapeTags(job.title()),
                "<gray>" + Mini.of(services.currency().render(job.wage())) + " <gray>every "
                        + Times.describe(Duration.ofMinutes(job.everyMinutes())),
                "<gray>Next wage in " + Times.describe(next),
                job.missed() > 0 ? "<red>" + job.missed() + " wage(s) missed" : "",
                "", "<yellow>Click<gray> to " + (employer ? "let them go" : "quit"));
    }

    @Override
    protected void onClick(Contract job, InventoryClickEvent event) {
        boolean employer = job.employer().equals(viewer.getUniqueId());
        String other = employer ? job.employeeName() : job.employerName();
        new ConfirmScreen(viewer, services.brand(), this, employer ? "<red>Let " + other + " go?" : "<red>Quit?",
                List.of(employer ? "<gray>No more wages are paid to them." : "<gray>No more wages from " + other + "."),
                () -> {
                    services.hire().end(viewer, job);
                    open();
                }).open();
    }

    @Override
    public String describe() {
        return "the jobs somebody has and gives";
    }
}
