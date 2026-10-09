package de.raindancer.modules.jobs.screen;

import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.jobs.JobsServices;
import de.raindancer.modules.jobs.model.Goal;
import de.raindancer.modules.jobs.model.GoalKind;
import de.raindancer.modules.jobs.service.GoalService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code /jobs}: the server's goals, how far along each is, and your part. Click a delivery to hand in. */
public final class JobBoardMenu extends PaginatedMenu<Goal> implements IJobsScreen {

    private static final int BAR = 20;
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final JobsServices services;

    public JobBoardMenu(JobsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Job board");
    }

    @Override
    public String breadcrumb() {
        return "Job board";
    }

    @Override
    protected List<Goal> entries() {
        return services.goals().goals();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No goals right now", "<dark_gray>A new one goes up within a minute.");
    }

    @Override
    protected void render() {
        super.render();
        List<String> lore = new ArrayList<>(List.of(
                "<gray>Everybody works on the same goals.",
                "<gray>Whoever gave 30% of one is paid",
                "<gray>30% of its reward. A goal missed",
                "<gray>pays for the part that was reached.",
                ""));
        services.goals().poolNow().ifPresentOrElse(
                reward -> lore.add("<gray>A goal pays <white>" + money(reward) + " <dark_gray>(the median balance)"),
                () -> lore.add("<dark_gray>No economy: goals pay nothing."));
        toolbar(4, Icons.of(Material.LECTERN, "<white>How it works", lore), click -> { });
    }

    @Override
    protected ItemStack icon(Goal goal) {
        List<String> lore = new ArrayList<>();
        String what = MINI.escapeTags(GoalService.what(goal));
        lore.add(goal.kind() == GoalKind.DELIVER ? "<gray>Deliver: <white>" + what : "<gray>Catch: <white>" + what);
        lore.add(bar(goal.progress(), goal.amount()) + " <white>" + goal.progress() + "<gray>/" + goal.amount());
        Duration left = Duration.ofMillis(Math.max(0, goal.endsAt() - System.currentTimeMillis()));
        lore.add("<gray>Ends in <white>" + Times.describe(left));
        services.goals().poolNow().ifPresent(reward -> lore.add("<gray>Reward: <white>" + money(reward)
                + " <dark_gray>shared by what each gave"));
        int mine = goal.givenBy(viewer.getUniqueId());
        if (mine > 0) {
            int percent = (int) Math.round(mine * 100.0 / Math.max(1, goal.progress()));
            Optional<Money> now = services.goals().estimate(goal, viewer.getUniqueId());
            lore.add("<aqua>You gave " + mine + " (" + percent + "%)"
                    + now.map(money -> " — about " + money(money) + " if it ended now").orElse(""));
        }
        List<Map.Entry<java.util.UUID, Integer>> leaders = goal.leaders(3);
        if (!leaders.isEmpty()) {
            lore.add("<dark_gray>Most: " + String.join(", ", leaders.stream()
                    .map(each -> MINI.escapeTags(services.goals().nameOf(each.getKey())) + " " + each.getValue()).toList()));
        }
        lore.add("");
        if (goal.kind() == GoalKind.DELIVER) {
            int carrying = services.goals().carrying(viewer, goal);
            lore.add(carrying > 0 ? "<yellow>Click<gray> to hand in what you carry (" + Math.min(carrying, goal.left()) + ")"
                    : "<dark_gray>You carry none of it.");
        } else {
            lore.add("<gray>Fish with a rod — every catch counts.");
        }
        return Icons.of(services.goals().iconOf(goal), "<gold>" + MINI.escapeTags(goal.title()), lore);
    }

    @Override
    protected void onClick(Goal goal, InventoryClickEvent event) {
        if (goal.kind() == GoalKind.DELIVER) {
            services.goals().deliver(viewer, goal.number());
            refresh();
        } else {
            services.messages().send(viewer, "jobs.fish-how", "goal", goal.title());
        }
    }

    private static String bar(int progress, int amount) {
        int filled = (int) Math.min(BAR, Math.round(progress * (double) BAR / Math.max(1, amount)));
        return "<green>" + "|".repeat(filled) + "<dark_gray>" + "|".repeat(BAR - filled);
    }

    private static String money(Money amount) {
        return Economies.current().map(bank -> MINI.serialize(bank.currency().render(amount)))
                .orElse(String.valueOf(amount.minor()));
    }

    @Override
    protected List<String> helpLines() {
        return List.of("The server's goals: deliver, or fish.", "Paid by your share when one ends.");
    }

    @Override
    public String describe() {
        return "the server's goals and your part in them";
    }
}
