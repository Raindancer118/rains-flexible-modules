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

import java.time.Duration;
import java.util.List;

/** Asking for work: the amount typed into an anvil, how long, then the offers it becomes — to pick one or ask again. */
public final class OrderScreens extends PaginatedMenu<OrderService.Offer> implements IJobsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final JobsServices services;
    private final List<OrderService.Offer> offers;
    private final String typed;
    private final Duration time;

    private OrderScreens(JobsServices services, Player viewer, Menu parent, List<OrderService.Offer> offers, String typed,
                         Duration time) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.offers = List.copyOf(offers);
        this.typed = typed;
        this.time = time;
    }

    /** The anvil: how much? Then how long, then the offers. */
    public static void ask(JobsServices services, Player viewer, Menu parent) {
        AnvilInput.open(viewer, "How much do you want to earn?", "", Parsers.text(24),
                typed -> answer(services, viewer, parent, typed), parent == null ? null : parent::open);
    }

    /** An amount someone typed: refused at once if it cannot be, else on to choosing the time. */
    public static void answer(JobsServices services, Player viewer, Menu parent, String typed) {
        Money asked = Fees.amount(typed);
        if (!asked.isPositive()) {
            services.messages().send(viewer, "jobs.order.unreadable", "text", typed);
            return;
        }
        java.util.Optional<OrderService.Answer> refused = services.orders().refusal(viewer.getUniqueId(), asked);
        if (refused.isPresent()) {
            services.messages().send(viewer, refused.get().refusal(), "amount", Fees.format(refused.get().amount()));
            return;
        }
        new TimeChoice(services, viewer, parent, typed).open();
    }

    /** The offers for an amount and a time (null: the work decides), or a line saying why there are none. */
    public static void offers(JobsServices services, Player viewer, Menu parent, String typed, Duration time) {
        Money asked = Fees.amount(typed);
        if (!asked.isPositive()) {
            services.messages().send(viewer, "jobs.order.unreadable", "text", typed);
            return;
        }
        OrderService.Answer answer = services.orders().ask(viewer.getUniqueId(), asked, time);
        if (answer.offers().isEmpty()) {
            viewer.closeInventory();
            services.messages().send(viewer, answer.refusal(), "amount", Fees.format(answer.amount()));
            return;
        }
        new OrderScreens(services, viewer, parent, answer.offers(), typed, time).open();
    }

    /** How long somebody wants to work for it. */
    private static final class TimeChoice extends PaginatedMenu<Duration> {

        /** Zero stands for "let the work decide". */
        private static final List<Duration> TIMES = List.of(Duration.ZERO, Duration.ofMinutes(10), Duration.ofMinutes(30),
                Duration.ofHours(1), Duration.ofHours(3), Duration.ofHours(12), Duration.ofDays(1), Duration.ofDays(3));

        private final JobsServices services;
        private final String typed;

        TimeChoice(JobsServices services, Player viewer, Menu parent, String typed) {
            super(viewer, services.brand(), parent);
            this.services = services;
            this.typed = typed;
        }

        @Override
        protected Component title() {
            return MINI.deserialize("<dark_gray>How long?");
        }

        @Override
        public String breadcrumb() {
            return "Time for " + typed;
        }

        @Override
        protected List<Duration> entries() {
            return TIMES;
        }

        @Override
        protected ItemStack emptyIcon() {
            return Icons.of(Material.CLOCK, "<gray>No times");
        }

        @Override
        protected ItemStack icon(Duration time) {
            return time.isZero()
                    ? Icons.of(Material.COMPASS, "<gold>Let the work decide", "<gray>Each offer comes with the time",
                    "<gray>its work takes at the order's pace.")
                    : Icons.of(Material.CLOCK, "<gold>" + Times.describe(time), "<gray>Work that fits this time.",
                    "<dark_gray>Less time is not less work —", "<dark_gray>more time is more work, same pay.");
        }

        @Override
        protected void onClick(Duration time, InventoryClickEvent event) {
            offers(services, viewer, parent(), typed, time.isZero() ? null : time);
        }

        @Override
        protected List<String> helpLines() {
            return List.of("How long you want to work for it.");
        }
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
                time == null ? "<gray>Each with the time its work takes." : "<gray>Each in " + Times.describe(time) + ".",
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
                        offers(services, viewer, parent(), typed, time);
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
