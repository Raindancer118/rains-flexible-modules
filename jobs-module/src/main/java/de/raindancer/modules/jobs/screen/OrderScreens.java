package de.raindancer.modules.jobs.screen;

import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.ui.prompt.Parsers;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.jobs.JobsServices;
import de.raindancer.modules.jobs.service.OrderService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Asking for work: the amount typed into an anvil, then the work it becomes, to take or to ask again. */
public final class OrderScreens implements IJobsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private OrderScreens() {
    }

    /** The anvil: how much? Then the offer. */
    public static void ask(JobsServices services, Player viewer, Menu parent) {
        AnvilInput.open(viewer, "How much do you want to earn?", "", Parsers.text(24),
                typed -> answer(services, viewer, parent, typed), parent == null ? null : parent::open);
    }

    /** What an amount someone typed becomes: an offer on screen, or a line saying why not. */
    public static void answer(JobsServices services, Player viewer, Menu parent, String typed) {
        Money asked = Fees.amount(typed);
        if (!asked.isPositive()) {
            services.messages().send(viewer, "jobs.order.unreadable", "text", typed);
            return;
        }
        OrderService.Answer answer = services.orders().ask(viewer.getUniqueId(), asked);
        if (answer.offer() == null) {
            services.messages().send(viewer, answer.refusal(), "amount", Fees.format(answer.amount()));
            return;
        }
        offer(services, viewer, parent, answer.offer(), typed);
    }

    private static void offer(JobsServices services, Player viewer, Menu parent, OrderService.Offer offer, String typed) {
        List<String> lines = new ArrayList<>();
        lines.add("<white>" + MINI.escapeTags(offer.says()));
        lines.add("<gray>Time: <white>" + Times.describe(offer.time()) + " <gray>from when you take it");
        lines.add("<gray>Pays: <gold>" + MINI.escapeTags(Fees.format(offer.pay())) + " <gray>if done in time, else nothing");
        lines.add("");
        int left = services.orders().rerollsLeft(viewer.getUniqueId());
        lines.add(left > 0 ? "<dark_gray>Not your thing? Say no and ask for " + MINI.escapeTags(typed)
                + " again — " + left + " other offer(s) left today."
                : "<dark_gray>No other offers left today.");
        new ConfirmMenu(viewer, services.brand(), parent, "<dark_gray>Take this order?", lines,
                "<dark_gray>The clock starts the moment you say yes.",
                () -> services.orders().accept(viewer, offer)).open();
    }

    @Override
    public String describe() {
        return "asking for work by the amount, and the offer it becomes";
    }
}
