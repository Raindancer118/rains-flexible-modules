package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.store.Reasons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * What a player is reporting somebody for.
 *
 * <h2>Why a player gets a menu at all</h2>
 * Because the commonest useless report is the one-word one — "griefing", nothing else, nobody knows
 * where — and asking for a category first turns that into a category <em>plus</em> whatever they add. It
 * also means the queue can be read at a glance, which nine lines of free text never allows.
 *
 * <p>These are <b>categories, not verdicts</b>. A player saying "this looks like cheating" is not the
 * server deciding that it was, which is why they map to no punishment: what a moderator does afterwards
 * comes from the punishment reasons and their own judgement.
 *
 * <h2>Typing still works</h2>
 * {@code /report <player> <anything>} bypasses this entirely and always will. A player describing
 * something no list covers is the case a report system exists for — the menu is the fast path, not the
 * only one.
 */
public final class ReportCategoryMenu extends ModerationList<String> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final UUID subject;
    private final String subjectName;

    public ReportCategoryMenu(ModerationServices services, Player viewer, Menu parent, UUID subject,
                              String subjectName) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Report — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return "Report";
    }

    @Override
    protected List<String> entries() {
        return Reasons.reportCategories();
    }

    @Override
    protected ItemStack icon(String category) {
        return Icons.of(Material.PAPER, "<yellow>" + category,
                List.of("<gray>Report " + subjectName + " for this.",
                        "",
                        "<dark_gray>You will be asked what happened,",
                        "<dark_gray>so the staff know where to look."));
    }

    @Override
    protected void onClick(String category, InventoryClickEvent event) {
        viewer.closeInventory();
        tell("moderation.report.type-what-happened", "category", category, "player", subjectName);

        services().prompts().ask(viewer.getUniqueId(), "moderation", Duration.ofSeconds(120),
                typed -> {
                    // The category in front of what they typed, so the queue reads as a category and
                    // the detail survives. The rule still judges the whole thing, so a category alone
                    // is not enough to file — which is the point of asking.
                    String text = category + " — " + (typed == null ? "" : typed.trim());
                    services().reportService()
                            .file(viewer.getUniqueId(), viewer.getName(), subject, subjectName, text)
                            .ifPresentOrElse(
                                    filed -> tell("moderation.report.filed", "id", filed.id(),
                                            "player", subjectName),
                                    () -> tellWhyNot(text));
                },
                () -> tell("moderation.nothing-typed"));
    }

    /** Says which rule refused it, rather than leaving a player with a menu that did nothing. */
    private void tellWhyNot(String text) {
        var verdict = services().reportService().mayFile(viewer.getUniqueId(), subject, text);
        verdict.refusal().ifPresentOrElse(
                reason -> tell(reason, "detail",
                        verdict.detail() == null ? "" : verdict.detail()),
                () -> tell("moderation.report.not-taken", "player", subjectName));
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Pick what fits best, then say what happened.",
                "<gray>You can also type <white>/report " + subjectName + " …</white> directly.",
                "<gray>A moderator decides what to do — a report is not a punishment.");
    }

    @Override
    public String describe() {
        return "what a player is reporting somebody for, as categories rather than a blank line";
    }
}
