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

/** Every preset: click puts its rules in place of the current ones, shift-click deletes it. */
public final class RulePresetsMenu extends PaginatedMenu<RulePreset> {

    private static final int RULES_IN_LORE = 8;

    private final EssentialsServices services;

    public RulePresetsMenu(EssentialsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Rule presets");
    }

    @Override
    protected List<RulePreset> entries() {
        return services.rules().book().presets();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>No presets",
                "<dark_gray>Keep the current rules as one with the button below.");
    }

    @Override
    protected ItemStack icon(RulePreset preset) {
        List<String> lore = new ArrayList<>();
        if (!preset.description().isEmpty()) {
            lore.addAll(RulesMenu.lore(preset.description()));
            lore.add("");
        }
        List<HouseRule> rules = preset.rules();
        for (int index = 0; index < Math.min(RULES_IN_LORE, rules.size()); index++) {
            lore.add("<dark_gray>" + (index + 1) + ". <white>" + RulesMenu.escaped(rules.get(index).title()));
        }
        if (rules.size() > RULES_IN_LORE) {
            lore.add("<dark_gray>… and " + (rules.size() - RULES_IN_LORE) + " more");
        }
        lore.add("");
        lore.add("<dark_gray>click to use these rules");
        lore.add("<dark_gray>shift-click to delete the preset");
        return Icons.of(Material.ENCHANTED_BOOK, "<gold>" + RulesMenu.escaped(preset.name()), lore);
    }

    @Override
    protected void onClick(RulePreset preset, InventoryClickEvent event) {
        if (event.isShiftClick()) {
            new ConfirmMenu(viewer, services.brand(), this, "<red>Delete this preset?",
                    List.of("<gray>The preset " + RulesMenu.escaped(preset.name()) + " is gone for good.",
                            "<gray>The rules in use right now do not change."),
                    () -> {
                        services.rules().deletePreset(viewer, preset.name());
                        open();
                    }).open();
            return;
        }
        int current = services.rules().book().rules().size();
        new ConfirmMenu(viewer, services.brand(), this, "<gold>Use these rules?",
                List.of("<gray>The " + current + " rule(s) in use now are replaced",
                        "<gray>by the " + preset.rules().size() + " of " + RulesMenu.escaped(preset.name()) + ".",
                        "<gray>Keep the current ones as a preset first if you want them back."),
                () -> {
                    services.rules().applyPreset(viewer, preset.name());
                    parentOrClose();
                }).open();
    }

    private void parentOrClose() {
        if (parent() != null) {
            parent().open();
        } else {
            viewer.closeInventory();
        }
    }

    @Override
    protected void render() {
        super.render();
        toolbar(4, Icons.of(Material.WRITABLE_BOOK, "<green>Keep the current rules as a preset",
                        "<gray>Type a name in chat. The same name", "<gray>as an existing preset replaces it."),
                click -> askName());
    }

    private void askName() {
        viewer.closeInventory();
        services.messages().send(viewer, "essentials.rules.type-preset-name");
        boolean asked = services.core().prompts().ask(viewer.getUniqueId(), "Essentials", RulesMenu.PROMPT_TIMEOUT,
                typed -> {
                    services.rules().savePreset(viewer, typed, "");
                    open();
                },
                this::open);
        if (!asked) {
            services.messages().send(viewer, "essentials.rules.already-asking");
        }
    }
}
