package de.raindancer.modules.playerutils.screen;

import org.bukkit.potion.PotionEffectType;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.playerutils.PlayerUtilsServices;
import de.raindancer.modules.playerutils.model.Action;
import de.raindancer.modules.playerutils.model.Verdict;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The effects on somebody, how strong and how long. Clicking one takes it off — which counts as healing them,
 * so it needs the heal node for that player.
 */
public final class EffectsMenu extends PaginatedMenu<PotionEffect> implements IPlayerUtilsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final PlayerUtilsServices services;
    private final UUID targetId;

    public EffectsMenu(PlayerUtilsServices services, Player viewer, Menu parent, UUID target) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.targetId = target;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Effects");
    }

    @Override
    public String breadcrumb() {
        return "Effects";
    }

    private Player target() {
        return services.server().getPlayer(targetId);
    }

    @Override
    protected List<PotionEffect> entries() {
        Player target = target();
        if (target == null) {
            return List.of();
        }
        return target.getActivePotionEffects().stream()
                .sorted(Comparator.comparing(effect -> effect.getType().getKey().getKey()))
                .toList();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.GLASS_BOTTLE, "<gray>No effects", "<gray>Nothing is on them right now.");
    }

    @Override
    protected ItemStack icon(PotionEffect effect) {
        boolean bad = effect.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL;
        String name = effect.getType().getKey().getKey().replace('_', ' ');
        String time = effect.isInfinite() ? "forever" : (effect.getDuration() / 20) + " s left";
        boolean may = mayTakeOff();
        return Icons.of(bad ? Material.FERMENTED_SPIDER_EYE : Material.POTION,
                (bad ? "<red>" : "<green>") + Character.toUpperCase(name.charAt(0)) + name.substring(1)
                        + " " + (effect.getAmplifier() + 1),
                "<gray>" + time + (effect.isAmbient() ? ", from a beacon" : ""),
                "",
                may ? "<dark_gray>Click to take it off." : "<dark_gray>Taking it off needs the heal node.");
    }

    private boolean mayTakeOff() {
        Player target = target();
        if (target == null) {
            return false;
        }
        Verdict verdict = services.targeting().verdict(viewer, Action.HEAL, target);
        return verdict.allowed();
    }

    @Override
    protected void onClick(PotionEffect effect, InventoryClickEvent event) {
        Player target = target();
        if (target == null || !mayTakeOff()) {
            services.messages().send(viewer, "playerutils.effects.may-not");
            return;
        }
        Scheduling.onOwner(services.plugin(), target, () -> target.removePotionEffect(effect.getType()));
        services.messages().send(viewer, "playerutils.effects.removed", "player", PlayerTargets.shownName(target),
                "effect", effect.getType().getKey().getKey().replace('_', ' ').toLowerCase(Locale.ROOT));
        Scheduling.entityLater(services.plugin(), viewer, 1L, this::refresh);
    }

    @Override
    public String describe() {
        return "the effects on one player";
    }
}
