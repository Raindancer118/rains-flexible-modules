package de.raindancer.modules.essentials.screen;

import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.model.HouseRule;
import de.raindancer.modules.essentials.model.RulePreset;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** One preset's rules: click one to add just that rule to the rules in use, or take the whole preset. */
public final class RulePresetMenu extends PaginatedMenu<HouseRule> {

    private final EssentialsServices services;
    private final String presetName;

    public RulePresetMenu(EssentialsServices services, Player viewer, String presetName, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.presetName = presetName;
    }

    @Override
    protected Component title() {
        return Component.text("Preset: " + presetName);
    }

    @Override
    protected List<HouseRule> entries() {
        return services.rules().book().preset(presetName).map(RulePreset::rules).orElse(List.of());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>This preset has no rules");
    }

    @Override
    protected ItemStack icon(HouseRule rule) {
        boolean have = services.rules().book().has(rule);
        List<String> lore = new ArrayList<>(RulesMenu.lore(rule.text()));
        lore.add("");
        lore.add(have ? "<green>Already in your rules" : "<dark_gray>click to add it to your rules");
        return Icons.of(have ? Material.LIME_DYE : rule.drawnAs(),
                (have ? "<gray>" : "<white>") + RulesMenu.escaped(rule.title()), lore);
    }

    @Override
    protected void onClick(HouseRule rule, InventoryClickEvent event) {
        services.rules().takeFromPreset(viewer, presetName, entries().indexOf(rule) + 1);
        refresh();
    }

    @Override
    protected void render() {
        super.render();
        int current = services.rules().book().rules().size();
        toolbar(4, Icons.of(Material.ENCHANTED_BOOK, "<gold>Use the whole preset",
                        "<gray>Replaces all " + current + " rule(s) in use."),
                click -> new ConfirmMenu(viewer, services.brand(), this, "<gold>Use these rules?",
                        List.of("<gray>The " + current + " rule(s) in use now are replaced",
                                "<gray>by the " + entries().size() + " of " + RulesMenu.escaped(presetName) + ".",
                                "<gray>To add only some, click them one by one instead."),
                        () -> services.rules().applyPreset(viewer, presetName)).open());
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Click a rule to add it to the end of yours.",
                "<gray>Green ones you already have.");
    }
}
