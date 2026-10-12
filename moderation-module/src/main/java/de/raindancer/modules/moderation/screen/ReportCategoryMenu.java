package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ReportGroup;
import de.raindancer.modules.moderation.model.ReportPreset;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a player is reporting somebody for — first the kind of thing, then the exact reason.
 *
 * <h2>Why a player gets a menu at all</h2>
 * Because the commonest useless report is the one-word one — "griefing", nothing else, nobody knows
 * where — and asking for a reason first turns that into a reason <em>plus</em> whatever they add. The
 * reason also tells staff how bad it looks before they read a word, and whether the chat around it matters.
 *
 * <p>These are <b>reasons, not verdicts</b>: what a moderator does afterwards is their own judgement.
 * {@code /report <player> <anything>} bypasses this entirely and always will.
 *
 * <pre>
 *  row 1      [cheating & exploits]  ·  [griefing & stealing]  ·  [chat & behaviour]
 *  row 2              [builds & names]  ·  [something else]
 * </pre>
 */
public final class ReportCategoryMenu extends ModerationScreen {

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
        return MINI.deserialize("<white>Report " + subjectName);
    }

    @Override
    public String breadcrumb() {
        return "Report";
    }

    @Override
    protected void render() {
        set(MenuLayout.HEADER_SUBJECT, Icons.head(subject, "<white>" + subjectName, "<gray>Who you are reporting."));
        door(MenuLayout.WHO, 2, ReportGroup.CHEATING);
        door(MenuLayout.WHO, 4, ReportGroup.GRIEFING);
        door(MenuLayout.WHO, 6, ReportGroup.CHAT);
        door(MenuLayout.RULES, 3, ReportGroup.BUILDS);
        door(MenuLayout.RULES, 5, ReportGroup.OTHER);
    }

    private void door(int band, int column, ReportGroup group) {
        List<String> lore = new ArrayList<>();
        for (ReportPreset preset : ReportPreset.in(group)) {
            lore.add("<gray>· " + preset.title());
        }
        band(band, column, Icons.of(group.icon(), "<yellow>" + group.title(), lore),
                click -> new ReportPresetMenu(services(), viewer, this, subject, subjectName, group).open());
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Pick what fits best, then say what happened.",
                "<gray>You can also type <white>/report " + subjectName + " …</white> directly.",
                "<gray>A moderator decides what to do — a report is not a punishment.");
    }

    @Override
    public String describe() {
        return "what a player is reporting somebody for, first the kind of thing";
    }
}
