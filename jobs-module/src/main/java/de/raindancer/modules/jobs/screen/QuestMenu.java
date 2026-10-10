package de.raindancer.modules.jobs.screen;

import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.roles.PlayerRoles;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.jobs.JobsServices;
import de.raindancer.modules.jobs.model.Quest;
import de.raindancer.modules.jobs.model.QuestDay;
import de.raindancer.modules.jobs.model.QuestTask;
import de.raindancer.modules.jobs.model.QuestTemplate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@code /quests}: today's personal quests, how far along each is and what it pays. */
public final class QuestMenu extends PaginatedMenu<Quest> implements IJobsScreen {

    private static final int BAR = 20;
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final JobsServices services;

    public QuestMenu(JobsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Your quests");
    }

    @Override
    public String breadcrumb() {
        return "Quests";
    }

    @Override
    protected List<Quest> entries() {
        return services.quests().today(viewer.getUniqueId()).quests();
    }

    @Override
    protected ItemStack emptyIcon() {
        return services.quests().settings().enabled()
                ? Icons.of(Material.COBWEB, "<gray>No quests today", "<dark_gray>There are none in quests.yml for you.")
                : Icons.of(Material.BARRIER, "<gray>Quests are switched off on this server");
    }

    @Override
    protected void render() {
        super.render();
        QuestDay day = services.quests().today(viewer.getUniqueId());
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        Duration untilNew = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.getZone()));
        List<String> lore = new ArrayList<>(List.of(
                "<gray>A few quests every day; each pays",
                "<gray>the moment it is done.",
                ""));
        lore.add("<gray>Your tier today: <white>" + day.tier());
        lore.add("<dark_gray>The more money you have, the higher");
        lore.add("<dark_gray>the tier: quests ask more and pay more.");
        PlayerRoles.of(viewer.getUniqueId()).ifPresentOrElse(
                role -> lore.add("<gray>Role quests: <" + role.colour() + ">" + MINI.escapeTags(role.title())
                        + " <dark_gray>(+" + services.quests().settings().roleBonusPercent() + "% pay)"),
                () -> lore.add("<dark_gray>Take a role (/role) for quests of its own."));
        lore.add("");
        lore.add("<gray>New quests in <white>" + Times.describe(untilNew));
        toolbar(4, Icons.of(Material.WRITABLE_BOOK, "<white>Your quests", lore), click -> { });
        toolbar(6, Icons.of(Material.LECTERN, "<white>Job board", "<gray>The server's shared goals",
                "<yellow>Click<gray> to open"), click -> new JobBoardMenu(services, viewer, this).open());
    }

    @Override
    protected ItemStack icon(Quest quest) {
        Optional<QuestTemplate> found = services.quests().template(quest);
        String title = found.map(QuestTemplate::title).orElse(quest.template());
        List<String> lore = new ArrayList<>();
        found.ifPresent(template -> {
            if (template.task() == QuestTask.TRAVEL) {
                lore.add("<gray>Travel <white>" + quest.amount() + "</white> blocks, on foot or riding");
            } else {
                lore.add("<gray>" + template.task().verb() + ": <white>" + MINI.escapeTags(template.things().says()));
            }
            if (template.forRole()) {
                lore.add("<dark_aqua>For your role");
            }
        });
        lore.add(bar(quest.progress(), quest.amount()) + " <white>" + quest.progress() + "<gray>/" + quest.amount());
        lore.add("<gray>Pays <white>" + money(quest.pay()));
        lore.add("");
        lore.add(switch (quest.state()) {
            case PAID -> "<green>✔ Done and paid";
            case OWED -> "<yellow>Done — paid as soon as the treasury can";
            case OPEN -> "<dark_gray>Counts as you play.";
        });
        Material icon = found.map(template -> Material.matchMaterial(template.icon())).filter(Material::isItem)
                .orElse(Material.PAPER);
        ItemStack item = Icons.of(quest.state() == Quest.State.PAID ? Material.LIME_DYE : icon,
                (quest.state() == Quest.State.OPEN ? "<gold>" : "<green>") + MINI.escapeTags(title), lore);
        return item;
    }

    @Override
    protected void onClick(Quest quest, InventoryClickEvent event) {
        // The quests count by themselves; nothing to hand in.
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
        return List.of("Your quests for today.", "Richer players get harder ones that pay more.");
    }

    @Override
    public String describe() {
        return "a player's personal quests for the day";
    }
}
