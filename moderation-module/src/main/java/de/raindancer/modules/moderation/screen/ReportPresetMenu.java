package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ReportGroup;
import de.raindancer.modules.moderation.model.ReportPlace;
import de.raindancer.modules.moderation.model.ReportPreset;
import de.raindancer.modules.moderation.service.ReportService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The exact reason, within one kind of thing; then what happened, typed. */
public final class ReportPresetMenu extends ModerationList<ReportPreset> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final UUID subject;
    private final String subjectName;
    private final ReportGroup group;

    public ReportPresetMenu(ModerationServices services, Player viewer, Menu parent, UUID subject, String subjectName,
                            ReportGroup group) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName;
        this.group = group;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<white>" + group.shortTitle());
    }

    @Override
    public String breadcrumb() {
        return group.shortTitle();
    }

    @Override
    protected List<ReportPreset> entries() {
        return ReportPreset.in(group);
    }

    @Override
    protected ItemStack icon(ReportPreset preset) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Report " + subjectName + " for this.");
        lore.add("");
        lore.add("<dark_gray>You will be asked what happened,");
        lore.add("<dark_gray>so the staff know where to look.");
        if (preset.chat()) {
            lore.add("<dark_gray>Staff can read the chat around it.");
        }
        return Icons.of(preset.icon(), "<yellow>" + preset.title(), lore);
    }

    @Override
    protected void onClick(ReportPreset preset, InventoryClickEvent event) {
        // Where both stand is read now, on the reporter's own thread — the typed answer may arrive anywhere.
        ReportPlace reporterAt = ReportService.placeOf(viewer);
        ReportPlace subjectAt = ReportService.placeOf(services().server().getPlayer(subject));
        viewer.closeInventory();
        tell("moderation.report.type-what-happened", "category", preset.title(), "player", subjectName);
        services().prompts().ask(viewer.getUniqueId(), "moderation", Duration.ofSeconds(120),
                typed -> {
                    // The reason in front of what they typed, so the queue reads as a reason and the detail survives.
                    String text = preset.title() + " — " + (typed == null ? "" : typed.trim());
                    services().reportService()
                            .file(viewer.getUniqueId(), viewer.getName(), subject, subjectName, text, preset,
                                    reporterAt, subjectAt)
                            .ifPresentOrElse(
                                    filed -> tell("moderation.report.filed", "id", filed.id(), "player", subjectName),
                                    () -> tellWhyNot(text));
                },
                () -> tell("moderation.nothing-typed"));
    }

    /** Says which rule refused it, rather than leaving a player with a menu that did nothing. */
    private void tellWhyNot(String text) {
        var verdict = services().reportService().mayFile(viewer.getUniqueId(), subject, text);
        verdict.refusal().ifPresentOrElse(
                reason -> tell(reason, "detail", verdict.detail() == null ? "" : verdict.detail()),
                () -> tell("moderation.report.not-taken", "player", subjectName));
    }

    @Override
    public String describe() {
        return "the exact reason a player is reporting somebody for";
    }
}
