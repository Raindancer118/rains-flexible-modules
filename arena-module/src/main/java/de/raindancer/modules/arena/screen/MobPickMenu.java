package de.raindancer.modules.arena.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.api.WaveSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Which mobs a run is made of — click to take one in or out; closing keeps the choice. */
public final class MobPickMenu extends PaginatedMenu<String> implements IArenaScreen {

    private final List<String> chosen;
    private final Consumer<List<String>> done;

    public MobPickMenu(ArenaServices services, Player viewer, Menu parent, List<String> current,
                       Consumer<List<String>> done) {
        super(viewer, services.brand(), parent);
        this.chosen = new ArrayList<>(current);
        this.done = done;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Mobs — " + chosen.size() + " chosen");
    }

    @Override
    protected List<String> entries() {
        return WaveSpec.ALLOWED_MOBS;
    }

    @Override
    protected ItemStack icon(String mob) {
        Material egg = Material.matchMaterial(mob + "_SPAWN_EGG");
        boolean in = chosen.contains(mob);
        return Icons.of(egg == null ? Material.EGG : egg,
                (in ? "<green>✔ " : "<gray>") + mob.toLowerCase(Locale.ROOT).replace('_', ' '),
                in ? "<gray>Click to leave it out." : "<gray>Click to take it in.");
    }

    @Override
    protected void onClick(String mob, InventoryClickEvent event) {
        if (!chosen.remove(mob)) {
            chosen.add(mob);
        }
        refresh();
    }

    @Override
    protected void decorate() {
        super.decorate();
        toolbar(4, Icons.of(Material.LIME_CONCRETE, "<green>Done", "<gray>Use these " + chosen.size() + "."), click -> {
            if (chosen.isEmpty()) {
                return;
            }
            done.accept(List.copyOf(chosen));
        });
    }

    @Override
    public String breadcrumb() {
        return "mobs";
    }

    @Override
    public String describe() {
        return "choosing the mobs of a run";
    }
}
