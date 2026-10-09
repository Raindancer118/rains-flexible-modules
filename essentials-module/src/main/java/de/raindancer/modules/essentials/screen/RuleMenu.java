package de.raindancer.modules.essentials.screen;

import de.raindancer.core.ui.choose.ItemChooser;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.model.HouseRule;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** One rule: its title, text, icon, whether it is shown, where it stands — and removing it. */
public final class RuleMenu extends Menu {

    private final EssentialsServices services;
    private final String ruleId;

    public RuleMenu(EssentialsServices services, Player viewer, String ruleId, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.ruleId = ruleId;
    }

    private HouseRule rule() {
        return services.rules().book().byId(ruleId).orElse(null);
    }

    @Override
    protected Component title() {
        HouseRule rule = rule();
        return Component.text(rule == null ? "Rule" : rule.title());
    }

    @Override
    protected void render() {
        HouseRule rule = rule();
        if (rule == null) {
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>This rule is gone",
                    "<gray>Somebody removed it while you were here."));
            return;
        }
        int number = services.rules().book().rules().indexOf(rule) + 1;
        int total = services.rules().book().rules().size();

        band(MenuLayout.WHO, 2, Icons.of(Material.NAME_TAG, "<white>Title",
                        "<gray>" + RulesMenu.escaped(rule.title()), "", "<dark_gray>click, then type the new one"),
                click -> ask("essentials.rules.type-title", typed -> services.rules().retitle(viewer, ruleId, typed)));
        List<String> textLore = new ArrayList<>(RulesMenu.lore(rule.text()));
        textLore.add("");
        textLore.add("<dark_gray>click, then type the new one");
        band(MenuLayout.WHO, 4, Icons.of(Material.WRITABLE_BOOK, "<white>Text", textLore),
                click -> ask("essentials.rules.type-text", typed -> services.rules().retext(viewer, ruleId, typed)));
        band(MenuLayout.WHO, 6, Icons.of(rule.drawnAs(), "<white>Icon",
                        "<gray>What it is drawn as in this editor.", "",
                        "<dark_gray>click to pick one",
                        "<dark_gray>right-click: the item in your hand"),
                click -> {
                    if (click.isRightClick()) {
                        iconFromHand();
                    } else {
                        new ItemChooser(viewer, services.brand(), this, "Icon for " + rule.title(), chosen -> {
                            services.rules().book().update(ruleId, current -> current.withIcon(chosen));
                            open();
                        }).open();
                    }
                });

        band(MenuLayout.RULES, 2, Icons.of(rule.enabled() ? Material.LIME_DYE : Material.GRAY_DYE,
                        rule.enabled() ? "<green>Shown to players" : "<red>Switched off",
                        "<gray>Off keeps the rule without showing it.", "",
                        "<dark_gray>click to switch " + (rule.enabled() ? "off" : "on")),
                click -> {
                    services.rules().book().update(ruleId, current -> current.withEnabled(!current.enabled()));
                    refresh();
                });
        band(MenuLayout.RULES, 4, number > 1, Icons.of(Material.ARROW, "<white>Move up",
                        "<gray>Now number " + number + " of " + total + "."),
                "Already first", click -> {
                    services.rules().book().move(ruleId, -1);
                    refresh();
                });
        band(MenuLayout.RULES, 6, number < total, Icons.of(Material.ARROW, "<white>Move down",
                        "<gray>Now number " + number + " of " + total + "."),
                "Already last", click -> {
                    services.rules().book().move(ruleId, 1);
                    refresh();
                });

        danger(Icons.of(Material.LAVA_BUCKET, "<red>Remove this rule"),
                click -> new ConfirmMenu(viewer, services.brand(), this, "<red>Remove this rule?",
                        List.of("<gray>" + RulesMenu.escaped(rule.title()) + " is taken out of /rules.",
                                "<gray>Switching it off instead keeps it for later."),
                        () -> {
                            services.rules().remove(viewer, ruleId);
                            parentOrClose();
                        }).open());
    }

    private void parentOrClose() {
        if (parent() != null) {
            parent().open();
        } else {
            viewer.closeInventory();
        }
    }

    private void iconFromHand() {
        ItemStack held = viewer.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            services.messages().send(viewer, "essentials.rules.icon-empty-hand");
            return;
        }
        services.rules().book().update(ruleId, current -> current.withIcon(held.getType()));
        refresh();
    }

    private void ask(String key, Consumer<String> answered) {
        viewer.closeInventory();
        services.messages().send(viewer, key);
        boolean asked = services.core().prompts().ask(viewer.getUniqueId(), "Essentials", RulesMenu.PROMPT_TIMEOUT,
                typed -> {
                    answered.accept(typed);
                    open();
                },
                this::open);
        if (!asked) {
            services.messages().send(viewer, "essentials.rules.already-asking");
        }
    }
}
