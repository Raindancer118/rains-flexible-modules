package de.raindancer.modules.moderation.screen;

import de.raindancer.core.moderation.rules.RulePenalty;
import de.raindancer.core.moderation.rules.ServerRule;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.rules.RuleBreachRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Which of the server's rules somebody broke. Each shows what it costs them now — the next rung of the rule's
 * own ladder, from how often they broke it before — and is greyed with the reason when this moderator may not
 * hand that rung out.
 */
public final class RuleBreachMenu extends ModerationList<ServerRule> {

    private final UUID subject;
    private final String subjectName;

    public RuleBreachMenu(ModerationServices services, Player viewer, Menu parent, UUID subject, String subjectName) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName;
    }

    @Override
    protected Component title() {
        return Component.text("Which rule did " + subjectName + " break?");
    }

    @Override
    public String breadcrumb() {
        return "a broken rule";
    }

    @Override
    protected List<ServerRule> entries() {
        return services().ruleBreaches().rules();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>This server has no rules yet",
                "<dark_gray>They are written with /rules edit.");
    }

    @Override
    protected ItemStack icon(ServerRule rule) {
        String title = "<white>" + rule.number() + ". " + escaped(rule.title());
        int before = services().ruleBreaches().offencesAgainst(subject, rule);
        Optional<RuleBreachRule.Breach> next = services().ruleBreaches().next(subject, rule, "");
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + escaped(rule.text()));
        lore.add("");
        if (next.isEmpty()) {
            lore.add("<dark_gray>No fixed punishment: use the doors on their page.");
            return Icons.of(Material.PAPER, title, lore);
        }
        lore.add("<gray>Broken before: <white>" + before + "×");
        lore.add("<red>Now: " + next.get().penalty().describe()
                + " <dark_gray>(" + RulePenalty.ordinal(next.get().offence()) + " time)");
        lore.add("<dark_gray>" + escaped(RulePenalty.describe(rule.ladder())));
        lore.add("");
        Verdict allowed = services().ruleBreaches().mayHandOut(viewer.getUniqueId(), subject, next.get());
        if (allowed.isRefused()) {
            return Icons.locked(Icons.of(Material.IRON_BARS, title, lore), "Not yours to hand out");
        }
        lore.add("<yellow>Click<gray> to hand it out");
        return Icons.of(Material.IRON_BARS, title, lore);
    }

    @Override
    protected void onClick(ServerRule rule, InventoryClickEvent event) {
        Optional<RuleBreachRule.Breach> next = services().ruleBreaches().next(subject, rule, "");
        if (next.isEmpty() || services().ruleBreaches().mayHandOut(viewer.getUniqueId(), subject, next.get()).isRefused()) {
            return;
        }
        new ConfirmScreen(services(), viewer, this,
                "<red>" + next.get().penalty().describe() + " for " + subjectName + "?",
                List.of("<gray>For breaking rule " + rule.number() + ": " + escaped(rule.title()) + ".",
                        "<gray>Their " + RulePenalty.ordinal(next.get().offence()) + " time."),
                () -> services().ruleBreaches().breakRule(viewer, viewer.getUniqueId(), viewer.getName(), subject,
                        subjectName, rule, "")).open();
    }

    private static String escaped(String text) {
        return MiniMessage.miniMessage().escapeTags(text);
    }

    @Override
    public String describe() {
        return "punishing somebody for breaking one of the server's rules";
    }
}
