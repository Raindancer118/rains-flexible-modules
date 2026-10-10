package de.raindancer.modules.jobs.screen;

import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.ui.prompt.Parsers;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.jobs.JobsServices;
import de.raindancer.modules.jobs.service.OrderService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Asking for work: the amount typed into an anvil, then the offers it becomes, to pick one from or ask again. */
public final class OrderScreens extends PaginatedMenu<OrderService.Offer> implements IJobsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final JobsServices services;
    private final List<OrderService.Offer> offers;
    private final String typed;

    private OrderScreens(JobsServices services, Player viewer, Menu parent, List<OrderService.Offer> offers, String typed) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.offers = List.copyOf(offers);
        this.typed = typed;
    }

    /** The anvil: how much? Then the offers. */
    public static void ask(JobsServices services, Player viewer, Menu parent) {
        AnvilInput.open(viewer, "How much do you want to earn?", "", Parsers.text(24),
                typed -> answer(services, viewer, parent, typed), parent == null ? null : parent::open);
    }

    /** What an amount someone typed becomes: offers on screen, or a line saying why not. */
    public static void answer(JobsServices services, Player viewer, Menu parent, String typed) {
        Money asked = Fees.amount(typed);
        if (!asked.isPositive()) {
            services.messages().send(viewer, "jobs.order.unreadable", "text", typed);
            return;
        }
        OrderService.Answer answer = services.orders().ask(viewer.getUniqueId(), asked);
        if (answer.offers().isEmpty()) {
            services.messages().send(viewer, answer.refusal(), "amount", Fees.format(answer.amount()));
            return;
        }
        new OrderScreens(services, viewer, parent, answer.offers(), typed).open();
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Pick your work");
    }

    @Override
    public String breadcrumb() {
        return "Work for " + typed;
    }

    @Override
    protected List<OrderService.Offer> entries() {
        return offers;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No work to offer");
    }

    @Override
    protected void render() {
        super.render();
        String pay = MINI.escapeTags(offers.isEmpty() ? "" : Fees.format(offers.getFirst().pay()));
        toolbar(4, Icons.of(Material.CLOCK, "<white>Each pays <gold>" + pay,
                "<gray>Done in time, it pays all of it;",
                "<gray>out of time, nothing.",
                "<gray>The clock starts when you pick one."), click -> { });
        int left = services.orders().rerollsLeft(viewer.getUniqueId());
        toolbar(6, left > 0
                        ? Icons.of(Material.HOPPER, "<white>Other offers", "<gray>A fresh set for " + MINI.escapeTags(typed),
                        "<dark_gray>" + left + " left today", "<yellow>Click<gray> to ask again")
                        : Icons.of(Material.BARRIER, "<gray>No other offers left today"),
                click -> {
                    if (left > 0) {
                        answer(services, viewer, parent(), typed);
                    }
                });
    }

    @Override
    protected ItemStack icon(OrderService.Offer offer) {
        Material icon = Material.matchMaterial(offer.work().icon());
        return Icons.of(icon == null || !icon.isItem() ? Material.PAPER : icon,
                "<gold>" + MINI.escapeTags(offer.says()),
                "<gray>Time: <white>" + Times.describe(offer.time()),
                "<gray>Pays: <white>" + MINI.escapeTags(Fees.format(offer.pay())) + " <gray>if done in time",
                "", "<yellow>Click<gray> to take it — the clock starts at once");
    }

    @Override
    protected void onClick(OrderService.Offer offer, InventoryClickEvent event) {
        if (services.orders().accept(viewer, offer)) {
            viewer.closeInventory();
        }
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Work for the amount you named.", "Pick one; the clock starts at once.");
    }

    @Override
    public String describe() {
        return "the work an asked-for amount becomes, to pick from";
    }
}
