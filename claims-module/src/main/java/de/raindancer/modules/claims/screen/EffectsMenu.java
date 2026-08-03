package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimEffect;
import de.raindancer.modules.claims.visual.EffectIcons;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which potion effects a claim grants to everybody standing inside it, and at what level.
 *
 * <p>{@code PerksMenu} only ever had a switch for whether granted effects run at all — nothing chose which
 * ones, so switching it on did nothing. This is that choice: the effects already granted, with their level
 * and whether they show particles, and a picker for adding another.
 *
 * <p>The picker is filtered by the server's blocklist ({@link de.raindancer.modules.claims.ClaimSettings#blockedEffects()}):
 * a blocked effect never appears here at all, so an owner cannot wither or poison a visitor by accident or
 * on purpose. That filtering is the one thing about this screen with real consequences.
 */
public final class EffectsMenu extends PaginatedMenu<ClaimEffect> implements IClaimScreen {

    private final ClaimServices services;
    private final Claim claim;

    public EffectsMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.claim = claim;
    }

    @Override
    protected Component title() {
        return Component.text("Effects granted here");
    }

    @Override
    protected List<String> helpLines() {
        return List.of(
                "<gray>Left-click an effect to raise its level,",
                "<gray>right click to lower it.",
                "<gray>Middle-click toggles its particles.",
                "<gray>Shift + right-click removes it.");
    }

    private boolean mayManage() {
        return services.rights().canManage(claim, viewer(), ClaimAdminPermission.MANAGE_FLAGS);
    }

    @Override
    protected List<ClaimEffect> entries() {
        List<ClaimEffect> effects = new ArrayList<>(claim.effects().values());
        effects.sort((left, right) -> left.displayName().compareToIgnoreCase(right.displayName()));
        return effects;
    }

    @Override
    protected ItemStack icon(ClaimEffect effect) {
        return Icons.of(EffectIcons.iconFor(effect.type()).getType(),
                "<aqua>" + effect.displayName() + " <white>" + roman(effect.level()),
                "<gray>Everybody inside the claim gets this.",
                "<white>Level: <yellow>" + effect.level(),
                "<white>Particles: " + (effect.showParticles() ? "<green>shown" : "<gray>hidden"),
                "",
                "<yellow>Left-click <gray>raise the level",
                "<yellow>Right-click <gray>lower the level",
                "<yellow>Middle-click <gray>toggle particles",
                "<yellow>Shift + right-click <gray>remove");
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> "V";
        };
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.GLASS_BOTTLE, "<gray>No effects granted",
                "<gray>Add one below and everybody inside",
                "<gray>the claim receives it while they stay.");
    }

    @Override
    protected void onClick(ClaimEffect effect, InventoryClickEvent event) {
        if (!mayManage()) {
            services.messages().send(viewer(), "error.no-claim-permission");
            return;
        }
        if (event.isShiftClick() && event.isRightClick()) {
            claim.removeEffect(effect.type());
            save();
            services.messages().send(viewer(), "effect.removed",
                    "effect", effect.displayName(), "claim", claim.name());
            return;
        }
        if (event.getClick() == ClickType.MIDDLE || event.getClick().isCreativeAction()) {
            effect.showParticles(!effect.showParticles());
            save();
            return;
        }
        int max = services.config().maxEffectAmplifier();
        int step = event.isRightClick() ? -1 : 1;
        effect.amplifier(Math.max(0, Math.min(max, effect.amplifier() + step)));
        save();
    }

    private void save() {
        claim.markDirty();
        services.claimService().saveAsync(claim);
        refresh();
    }

    @Override
    protected void decorate() {
        super.decorate();

        int limit = services.config().maxClaimEffects();
        boolean atLimit = claim.effects().size() >= limit;
        if (mayManage() && !atLimit) {
            toolbar(4, Icons.of(Material.BREWING_STAND, "<green><bold>Add an effect",
                            "<white>Used: <yellow>" + claim.effects().size() + "<gray>/" + limit,
                            "<gray>Pick from the effects this server allows."),
                    click -> new EffectPickerMenu(services, viewer(), this, claim).open());
        } else {
            toolbar(4, Icons.locked(
                    Icons.of(Material.GRAY_DYE, "<gray>Add an effect",
                            "<white>Used: <yellow>" + claim.effects().size() + "<gray>/" + limit),
                    atLimit ? "Remove one first" : "The owner's to change"),
                    click -> { });
        }
    }

    /** The list of effects the server permits — the blocked ones are never in it. */
    private static final class EffectPickerMenu extends PaginatedMenu<PotionEffectType> implements IClaimScreen {

        private final ClaimServices services;
        private final Claim claim;

        EffectPickerMenu(ClaimServices services, Player viewer, Menu parent, Claim claim) {
            super(viewer, services.brand(), parent);
            this.services = services;
            this.claim = claim;
        }

        @Override
        protected Component title() {
            return Component.text("Choose an effect");
        }

        @Override
        protected List<String> helpLines() {
            return List.of("<gray>Effects the server blocks are never shown here at all.");
        }

        @Override
        protected List<PotionEffectType> entries() {
            List<PotionEffectType> types = new ArrayList<>();
            for (PotionEffectType type : Registry.EFFECT) {
                if (services.config().isEffectBlocked(type.getKey().getKey())) {
                    continue;
                }
                if (claim.effects().containsKey(type)) {
                    continue;
                }
                types.add(type);
            }
            types.sort((left, right) -> left.getKey().getKey().compareTo(right.getKey().getKey()));
            return types;
        }

        @Override
        protected ItemStack icon(PotionEffectType type) {
            String raw = type.getKey().getKey().replace('_', ' ');
            String label = raw.substring(0, 1).toUpperCase(Locale.ROOT) + raw.substring(1);
            List<String> lore = new ArrayList<>();
            lore.add("<dark_gray>" + EffectIcons.sourceLabel(type));
            if (services.config().effectsRequirePotions() && !EffectIcons.hasRealPotion(type)) {
                lore.add("");
                lore.add("<gold>No potion brews this, so it cannot be");
                lore.add("<gold>fuelled while potion costs are on.");
            }
            lore.add("");
            lore.add("<yellow>Click to grant this in the claim");
            return Icons.of(EffectIcons.iconFor(type).getType(), "<white>" + label, lore);
        }

        @Override
        protected ItemStack emptyIcon() {
            return Icons.of(Material.BARRIER, "<gray>Nothing left to add",
                    "<gray>Every allowed effect is already granted.");
        }

        @Override
        protected void onClick(PotionEffectType type, InventoryClickEvent event) {
            claim.addEffect(new ClaimEffect(type, 0, true));
            claim.markDirty();
            services.claimService().saveAsync(claim);
            services.messages().send(viewer(), "effect.added",
                    "effect", type.getKey().getKey().replace('_', ' '), "claim", claim.name());
            if (parent() != null) {
                parent().open();
            }
        }
    }
}
