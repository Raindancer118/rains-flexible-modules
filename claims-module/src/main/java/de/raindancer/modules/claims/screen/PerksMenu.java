package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * What a claim does for the people inside it.
 *
 * <p>Five perks that were five unrelated buttons on the old root menu, gathered because they are the same kind of
 * thing: something the claim gives you for standing in it. Each shows whether the server offers it, whether the
 * owner has switched it on, and — where it applies — whether it is stocked, because "my pantry is not working"
 * is almost always "the pantry is empty".
 */
public final class PerksMenu extends ClaimScreen {

    public PerksMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent);
    }

    @Override
    protected Component title() {
        return Component.text("Perks");
    }

    @Override
    protected void render() {
        Claim claim = claim();

        perk(MenuLayout.WHO, 2, ClaimFeature.EFFECTS, Material.BREWING_STAND, "Effects",
                claim.effects().size() + " granted inside",
                () -> claim.effectsEnabled(!claim.effectsEnabled()));

        perk(MenuLayout.WHO, 4, ClaimFeature.PANTRY, Material.BREAD, "Pantry",
                claim.pantry().totalItems() + " item(s) stocked",
                () -> claim.pantry().enabled(!claim.pantry().enabled()));

        perk(MenuLayout.WHO, 6, ClaimFeature.AUTO_EQUIP, Material.ARMOR_STAND, "Auto-equip",
                claim.equipment().rules().size() + " rule(s)",
                () -> claim.equipment().enabled(!claim.equipment().enabled()));

        perk(MenuLayout.RULES, 3, ClaimFeature.CLAIM_WEATHER, Material.WATER_BUCKET, "Its own weather",
                claim.atmosphere().weather().displayName(),
                () -> claim.atmosphere().weather(claim.atmosphere().weather().next()));

        perk(MenuLayout.RULES, 5, ClaimFeature.CLAIM_TIME, Material.CLOCK, "Its own time of day",
                claim.atmosphere().timePreset().displayName(),
                () -> claim.atmosphere().timePreset(
                        nextTime(claim.atmosphere().timePreset())));

        toolbar(3, Icons.of(Material.POTION, "<white>Potions the effects drink",
                        "<gray>" + claim.potionStore().totalPotions() + " stocked",
                        "<dark_gray>only used when the server asks for them"),
                click -> services().screens().potionStore(viewer, claim));

        toolbar(5, Icons.of(Material.BREAD, "<white>Stock the pantry",
                        "<gray>Food the claim feeds hungry people with."),
                click -> services().screens().pantry(viewer, claim));
    }

    /** The next time preset round the list. TimePreset has no next() of its own, and the order is the list's. */
    private static de.raindancer.modules.claims.model.ClaimAtmosphere.TimePreset nextTime(
            de.raindancer.modules.claims.model.ClaimAtmosphere.TimePreset current) {
        var all = de.raindancer.modules.claims.model.ClaimAtmosphere.TimePreset.values();
        return all[(current.ordinal() + 1) % all.length];
    }

    /**
     * One perk, with the three states it can be in stated rather than implied.
     *
     * <p>The important one is "the server took this away": the button stays, greyed, saying so. A perk that simply
     * vanished from the menu is a support question.
     */
    private void perk(int band, int column, ClaimFeature feature, Material icon, String name,
                      String detail, Runnable toggle) {
        boolean offered = services().features().isOffered(feature);
        boolean forced = services().features().isForced(feature);
        boolean on = services().features().isEnabled(claim(), feature);

        List<String> lore = List.of(
                "<gray>" + feature.description(),
                "<dark_gray>" + detail,
                "",
                on ? "<green>✔ running" : "<red>✘ off",
                forced ? "<gold>the server keeps this on"
                        : offered ? "<dark_gray>click to change" : "<red>the server has switched this off");

        if (!offered) {
            band(band, column, false, Icons.of(icon, "<dark_gray>" + name, lore),
                    "The server has switched this off", null);
            return;
        }
        if (forced || !services().features().isEditableByOwner(feature)) {
            band(band, column, false, Icons.of(icon, "<gold>" + name, lore),
                    "The server keeps this on", null);
            return;
        }
        band(band, column, Icons.of(icon, (on ? "<green>" : "<gray>") + name, lore), click -> {
            toggle.run();
            claim().markDirty();
            services().claimService().saveAsync(claim());
            refresh();
        });
    }
}
