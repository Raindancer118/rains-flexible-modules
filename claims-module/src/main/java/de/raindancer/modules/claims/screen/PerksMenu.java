package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAtmosphere;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
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
                claim.atmosphere().overridesTime()
                        ? ClaimAtmosphere.clock(claim.atmosphere().effectiveTicks()) + " · "
                                + claim.atmosphere().timePreset().displayName()
                        : "follows the world",
                () -> claim.atmosphere().timePreset(ClaimAtmosphere.TimePreset.INHERIT),
                this::pickTime);

        toolbar(1, Icons.of(Material.BREWING_STAND, "<white>Choose the effects",
                        "<gray>" + claim.effects().size() + " granted, up to "
                                + services().config().maxClaimEffects()),
                click -> new EffectsMenu(services(), viewer, claim, this).open());

        toolbar(3, Icons.of(Material.POTION, "<white>Potions the effects drink",
                        "<gray>" + claim.potionStore().totalPotions() + " stocked",
                        "<dark_gray>only used when the server asks for them"),
                click -> new StoreMenu(services(), viewer, claim, this, StoreMenu.Kind.POTIONS).open());

        toolbar(5, Icons.of(Material.BREAD, "<white>Stock the pantry",
                        "<gray>Food the claim feeds hungry people with."),
                click -> new StoreMenu(services(), viewer, claim, this, StoreMenu.Kind.PANTRY).open());

        toolbar(7, Icons.of(Material.ARMOR_STAND, "<white>Auto-equip rules",
                        "<gray>" + claim.equipment().rules().size() + " rule(s), "
                                + claim.equipment().totalStock() + " item(s) stocked"),
                click -> new EquipMenu(services(), viewer, claim, this).open());
    }

    /**
     * The claim's time of day on Core's number picker: an hour, ten minutes, a minute or a single tick either way,
     * round midnight. Everybody standing in the claim sees each step as it is clicked; leaving without Accept
     * puts the time back as it was.
     */
    private void pickTime() {
        Claim claim = claim();
        ClaimAtmosphere atmosphere = claim.atmosphere();
        ClaimAtmosphere.TimePreset preset = atmosphere.timePreset();
        int custom = atmosphere.customTicks();
        org.bukkit.World world = services().server().getWorld(claim.worldId());
        long worldTicks = world == null ? viewer.getWorld().getTime() : world.getTime();
        int start = atmosphere.effectiveTicks() >= 0 ? atmosphere.effectiveTicks()
                : (int) Math.floorMod(worldTicks, (long) ClaimAtmosphere.DAY);
        new AmountChooser(viewer, services().brand(), this, "Time of day", start, 0, ClaimAtmosphere.DAY - 1,
                ticks -> {
                    atmosphere.customTicks(ticks);
                    claim.markDirty();
                    services().claimService().saveAsync(claim);
                    services().ambience().refreshClaim(claim);
                })
                .stepsOf(TIME_STEPS)
                .namingSteps(PerksMenu::timeStep)
                .wrapping()
                .showing(ticks -> ClaimAtmosphere.clock(ticks) + " (" + ticks + " ticks)")
                .previewing(ticks -> {
                    if (ticks == start) {
                        // Back where it opened: as it was, which for a claim following the world is following it.
                        if (custom >= 0) {
                            atmosphere.customTicks(custom);
                        } else {
                            atmosphere.timePreset(preset);
                        }
                    } else {
                        atmosphere.customTicks(ticks);
                    }
                    services().ambience().refreshClaim(claim);
                })
                .open();
    }

    /** An hour, ten minutes, a minute, a tick — a Minecraft day is 24000 ticks. */
    static final int[] TIME_STEPS = {1, 17, 167, 1000};

    /** "+1 hour", "-10 min", "+1 tick". */
    static String timeStep(int step) {
        String sign = step > 0 ? "+" : "-";
        return sign + switch (Math.abs(step)) {
            case 1000 -> "1 hour";
            case 167 -> "10 min";
            case 17 -> "1 min";
            case 1 -> "1 tick";
            default -> Math.abs(step) + " ticks";
        };
    }

    /**
     * One perk, with the three states it can be in stated rather than implied.
     *
     * <p>The important one is "the server took this away": the button stays, greyed, saying so. A perk that simply
     * vanished from the menu is a support question.
     *
     * <h2>Right click, for who it reaches</h2>
     * Asked for explicitly, after auto-equip shipped with no way to keep it from feeding every visitor: an
     * {@link ClaimFeature#audienceAware()} perk gets a second click, the same grammar {@code FlagChooser} already
     * teaches owners for flags — left click still flips the one on/off switch everybody wants, right click opens
     * {@link AudiencePage} for the three tiers. Two different questions ("does this run at all" vs "who does it
     * serve") stay two different clicks rather than one button trying to be both.
     */
    private void perk(int band, int column, ClaimFeature feature, Material icon, String name,
                      String detail, Runnable toggle) {
        perk(band, column, feature, icon, name, detail, toggle, null);
    }

    /** With {@code pick}: a click opens it, a shift click runs {@code toggle} instead. */
    private void perk(int band, int column, ClaimFeature feature, Material icon, String name,
                      String detail, Runnable toggle, Runnable pick) {
        boolean offered = services().features().isOffered(feature);
        boolean forced = services().features().isForced(feature);
        boolean on = services().features().isEnabled(claim(), feature);

        List<String> lore = new ArrayList<>(List.of(
                "<gray>" + feature.description(),
                "<dark_gray>" + detail,
                "",
                on ? "<green>✔ running" : "<red>✘ off",
                forced ? "<gold>the server keeps this on"
                        : !offered ? "<red>the server has switched this off"
                        : pick != null ? "<dark_gray>click to pick · shift click to switch off" : "<dark_gray>click to change"));
        if (offered && !forced && feature.audienceAware()) {
            lore.add("<dark_gray>right click for owners / trusted / visitors");
        }

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
            if (opensAudiencePage(click.getClick(), feature.audienceAware())) {
                new AudiencePage(services(), viewer, claim(), this, feature, name).open();
                return;
            }
            if (pick != null && !click.isShiftClick()) {
                pick.run();
                return;
            }
            toggle.run();
            claim().markDirty();
            services().claimService().saveAsync(claim());
            refresh();
        });
    }

    /** Extracted for {@code PerksMenuTest} — a right click opens who-it-serves, but only for a perk that
     *  has one; a feature with no audience to narrow does not gain a dead click. */
    static boolean opensAudiencePage(org.bukkit.event.inventory.ClickType click, boolean audienceAware) {
        return audienceAware && click.isRightClick();
    }

    /**
     * Who a perk reaches: owner, trusted, visitors — each toggled on its own, the same shape as
     * {@code FlagChooser.TierPage}, so a perk that narrows down and a flag that narrows down feel like one
     * mechanism rather than two menus that happen to agree.
     */
    private final class AudiencePage extends ClaimScreen {

        private final ClaimFeature feature;
        private final String featureName;

        private AudiencePage(ClaimServices services, Player viewer, Claim claim, Menu parent,
                             ClaimFeature feature, String featureName) {
            super(services, viewer, claim, parent, 3);
            this.feature = feature;
            this.featureName = featureName;
        }

        @Override
        protected Component title() {
            return Component.text(featureName);
        }

        @Override
        protected void render() {
            int column = 2;
            for (LandAudience audience : LandAudience.values()) {
                boolean served = claim().featureServes(feature, audience);
                band(MenuLayout.WHO, column, Icons.of(audience.icon(),
                                (served ? "<green>" : "<red>") + words(audience.nameKey()),
                                "<gray>" + words(audience.descriptionKey()),
                                "",
                                served ? "<green>✔ served" : "<red>✘ not served",
                                "<dark_gray>click to change"),
                        click -> {
                            claim().setFeatureAudience(feature, audience, !served);
                            claim().markDirty();
                            services().claimService().saveAsync(claim());
                            refresh();
                        });
                column += 2;
            }
        }

        private String words(String key) {
            return services().messages().raw(key);
        }
    }
}
