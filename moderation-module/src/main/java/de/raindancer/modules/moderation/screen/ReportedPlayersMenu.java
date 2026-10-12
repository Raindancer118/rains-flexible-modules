package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.model.ReportPreset;
import de.raindancer.modules.moderation.model.ReportSeverity;
import de.raindancer.modules.moderation.rules.ReportSeverityRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Everybody with a report about them, the most live reports first, each named in the colour of the worst. */
public final class ReportedPlayersMenu extends ModerationList<UUID> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final ReportSeverityRule SEVERITY = new ReportSeverityRule();

    public ReportedPlayersMenu(ModerationServices services, Player viewer, Menu parent) {
        super(services, viewer, parent);
    }

    /** Asked here, not only by the door that led here: a report link in chat or a typed id reaches this too. */
    @Override
    public void open() {
        if (!may(ModerationPermission.REPORTS)) {
            tell("moderation.no-permission");
            return;
        }
        super.open();
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<white>Reported players");
    }

    @Override
    public String breadcrumb() {
        return "Reported players";
    }

    private Map<UUID, List<Report>> bySubject() {
        Map<UUID, List<Report>> grouped = new LinkedHashMap<>();
        for (Report report : services().reports().all()) {
            grouped.computeIfAbsent(report.subject(), ignored -> new ArrayList<>()).add(report);
        }
        return grouped;
    }

    @Override
    protected List<UUID> entries() {
        Map<UUID, List<Report>> grouped = bySubject();
        return grouped.keySet().stream().sorted(Comparator
                .comparing((UUID who) -> -grouped.get(who).stream().filter(Report::isLive).count())
                .thenComparing(who -> -grouped.get(who).size())).toList();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nobody has been reported");
    }

    @Override
    protected ItemStack icon(UUID who) {
        List<Report> all = services().reports().all();
        List<Report> theirs = bySubject().getOrDefault(who, List.of());
        long live = theirs.stream().filter(Report::isLive).count();
        Optional<ReportSeverity> worst = theirs.stream().filter(Report::isLive).map(report -> SEVERITY.judge(report, all))
                .max(Comparator.naturalOrder());
        Report latest = theirs.stream().max(Comparator.comparing(Report::at)).orElseThrow();
        List<String> lore = new ArrayList<>();
        lore.add("<white>" + live + "</white><gray> live, " + (theirs.size() - live) + " closed.");
        worst.ifPresent(severity -> lore.add(severity.bar() + " <gray>worst: " + severity.colour() + severity.title()));
        lore.add("<gray>Latest: " + ReportPreset.of(latest).title() + ", " + ReportListMenu.WHEN.format(latest.at()));
        lore.add("");
        lore.add("<dark_gray>Click: every report about them.");
        return Icons.head(who, worst.map(ReportSeverity::colour).orElse("<gray>") + latest.subjectName(), lore);
    }

    @Override
    protected void onClick(UUID who, InventoryClickEvent event) {
        if (!may(ModerationPermission.REPORTS)) {
            tell("moderation.no-permission");
            return;
        }
        String name = bySubject().getOrDefault(who, List.of()).stream().map(Report::subjectName).findFirst().orElse("somebody");
        new ReportListMenu(services(), viewer, this, ReportListMenu.Filter.INVOLVING, who, name).open();
    }

    @Override
    public String describe() {
        return "everybody with a report about them, most live reports first";
    }
}
