package de.raindancer.modules.mannequin.screen;

import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.mannequin.MannequinServices;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.util.HealthPresets;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A mannequin's max health: a curated set of named presets ({@link HealthPresets}), a button to
 * type any raw number, and a button back to whatever the server default currently is.
 */
public final class HealthScreen extends PaginatedMenu<Map.Entry<String, Double>> implements IMannequinScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final MannequinServices services;
    private final String id;

    public HealthScreen(MannequinServices services, Player viewer, Mannequin mannequin, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.id = mannequin.id();
    }

    private Mannequin mannequin() {
        return services.registry().get(id).orElse(null);
    }

    @Override
    protected Component title() {
        Mannequin mannequin = mannequin();
        return MINI.deserialize("<dark_gray>Health — " + (mannequin == null ? id : mannequin.displayName()));
    }

    @Override
    public String breadcrumb() {
        return "Health";
    }

    @Override
    protected List<Map.Entry<String, Double>> entries() {
        return List.copyOf(HealthPresets.all().entrySet());
    }

    /** Never actually empty — the presets are a fixed table — but the grammar test wants one anyway. */
    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>No presets configured");
    }

    @Override
    protected ItemStack icon(Map.Entry<String, Double> preset) {
        Mannequin mannequin = mannequin();
        boolean current = mannequin != null && preset.getValue().equals(mannequin.maxHealthOverride());
        return Icons.of(spawnEggFor(preset.getKey()),
                (current ? "<green>" : "<white>") + readable(preset.getKey()),
                "<gray>" + String.format(Locale.ROOT, "%.0f", preset.getValue()) + " max HP",
                "",
                current ? "<dark_gray>Currently applied" : "<gray>Click to apply");
    }

    @Override
    protected void onClick(Map.Entry<String, Double> preset, InventoryClickEvent event) {
        apply(preset.getValue());
    }

    @Override
    protected void decorate() {
        super.decorate();
        Mannequin mannequin = mannequin();
        double effective = mannequin == null ? services.config().maxHealthClamped()
                : mannequin.resolvedMaxHealth(services.config().maxHealthClamped());

        toolbar(1, Icons.of(Material.NAME_TAG, "<white>Type a number",
                        "<gray>Any whole value, not just a preset."),
                click -> new AmountChooser(viewer, brand(), this, "Max health",
                        (int) Math.round(effective), 1, 2000, chosen -> apply((double) chosen)).open());

        boolean usingOverride = mannequin != null && mannequin.maxHealthOverride() != null;
        toolbar(7, usingOverride, Icons.of(Material.BARRIER, "<yellow>Use the server default",
                        "<gray>Currently <white>"
                                + String.format(Locale.ROOT, "%.0f", services.config().maxHealthClamped())
                                + "<gray> max HP.",
                        usingOverride ? "" : "<dark_gray>Already using it."),
                "This mannequin is already using the server default",
                click -> apply(null));
    }

    private void apply(Double health) {
        Mannequin mannequin = mannequin();
        if (mannequin == null) {
            return;
        }
        Mannequin updated = mannequin.withMaxHealthOverride(health);
        services.mannequins().save(updated);
        services.mannequins().liveEntity(id).ifPresent(entity ->
                entity.setMaxHealth(updated.resolvedMaxHealth(services.config().maxHealthClamped())));
        refresh();
    }

    private static String readable(String presetKey) {
        String spaced = presetKey.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static Material spawnEggFor(String presetKey) {
        return switch (presetKey) {
            case "player" -> Material.PLAYER_HEAD;
            case "zombie" -> Material.ZOMBIE_SPAWN_EGG;
            case "skeleton" -> Material.SKELETON_SPAWN_EGG;
            case "husk" -> Material.HUSK_SPAWN_EGG;
            case "spider" -> Material.SPIDER_SPAWN_EGG;
            case "cave_spider" -> Material.CAVE_SPIDER_SPAWN_EGG;
            case "witch" -> Material.WITCH_SPAWN_EGG;
            case "pillager" -> Material.PILLAGER_SPAWN_EGG;
            case "vindicator" -> Material.VINDICATOR_SPAWN_EGG;
            case "evoker" -> Material.EVOKER_SPAWN_EGG;
            case "piglin_brute" -> Material.PIGLIN_BRUTE_SPAWN_EGG;
            case "iron_golem" -> Material.IRON_GOLEM_SPAWN_EGG;
            case "ravager" -> Material.RAVAGER_SPAWN_EGG;
            case "wither" -> Material.WITHER_SPAWN_EGG;
            case "ender_dragon" -> Material.ENDER_DRAGON_SPAWN_EGG;
            case "warden" -> Material.WARDEN_SPAWN_EGG;
            default -> Material.HEART_OF_THE_SEA;
        };
    }

    @Override
    public String describe() {
        return "a mannequin's max health, by preset or by a typed number";
    }
}
