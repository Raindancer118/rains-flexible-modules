package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The creeper hazard, on a page of its own: how often mining or looting spawns one, and how often the
 * one it spawns is charged.
 *
 * <h2>Why it moved off the compass' front page</h2>
 * Four percentages spread across two bands were most of what that page showed, so the two questions
 * somebody actually opens the compass with — what ends this run, and what happens when somebody dies —
 * were outnumbered four to two by a hazard most servers never switch on. This is the shape the claim
 * screens already use: the front page is the race, and a thing that holds four settings is a door.
 *
 * <h2>Chances go through Core's picker</h2>
 * {@link AmountChooser}, the same one {@code EntryFeeMenu} uses and for the reason written there:
 * a ±5 pair is twenty clicks to cross the range, and nothing is written until Accept. A page that
 * reaches two amounts two different ways teaches whoever is looking at it that it is inconsistent.
 *
 * <h2>Charged is shown as what it is — a chance of a chance</h2>
 * A charged percentage only ever applies to a creeper the setting above it spawned, so with the spawn
 * chance at zero the charged one is not "0%", it is *nothing at all*. The lore says so rather than
 * leaving somebody to set it to 100 and wonder why nothing changed.
 */
public final class SpeedrunHazardMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SpeedrunLobby lobby;

    public SpeedrunHazardMenu(SpeedrunLobby lobby, Brand brand, Player viewer, Menu parent) {
        super(viewer, brand, parent);
        this.lobby = lobby;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Creeper hazard");
    }

    @Override
    public String breadcrumb() {
        return "Hazard";
    }

    @Override
    protected void render() {
        SpeedrunSettings config = lobby.config();

        set(MenuLayout.HEADER_SUBJECT, Icons.of(
                config.creeperSpawnChanceOnBreakPercent() > 0
                        || config.creeperSpawnChanceOnContainerPercent() > 0
                        ? Material.CREEPER_HEAD : Material.BARRIER,
                config.creeperSpawnChanceOnBreakPercent() > 0
                        || config.creeperSpawnChanceOnContainerPercent() > 0
                        ? "<gold>The hazard is on" : "<gray>The hazard is off",
                "<gray>Creepers that appear where a racer",
                "<gray>mines a block or opens a container.",
                "<dark_gray>Changes reach a run already under way."));

        band(MenuLayout.WHO, 2, chance(Material.IRON_PICKAXE, "Breaking a block",
                        config.creeperSpawnChanceOnBreakPercent(),
                        "<gray>How often mining spawns one, right where it broke."),
                click -> choose("Creeper chance on block break %",
                        "creeper-spawn-chance-on-break-percent",
                        config.creeperSpawnChanceOnBreakPercent()));

        band(MenuLayout.WHO, 6, charged("Charged, from a block",
                        config.chargedCreeperChanceOnBreakPercent(),
                        config.creeperSpawnChanceOnBreakPercent()),
                click -> choose("Charged chance on block break %",
                        "charged-creeper-chance-on-break-percent",
                        config.chargedCreeperChanceOnBreakPercent()));

        band(MenuLayout.LAND, 2, chance(Material.CHEST, "Opening a container",
                        config.creeperSpawnChanceOnContainerPercent(),
                        "<gray>How often looting spawns one where they stand."),
                click -> choose("Creeper chance on container open %",
                        "creeper-spawn-chance-on-container-percent",
                        config.creeperSpawnChanceOnContainerPercent()));

        band(MenuLayout.LAND, 6, charged("Charged, from a container",
                        config.chargedCreeperChanceOnContainerPercent(),
                        config.creeperSpawnChanceOnContainerPercent()),
                click -> choose("Charged chance on container open %",
                        "charged-creeper-chance-on-container-percent",
                        config.chargedCreeperChanceOnContainerPercent()));
    }

    private org.bukkit.inventory.ItemStack chance(Material icon, String name, int percent, String what) {
        return Icons.of(percent > 0 ? icon : Material.BARRIER,
                "<white>" + name + ": " + (percent > 0 ? "<green>" + percent + "%" : "<red>off"),
                what,
                "<dark_gray>Click to choose a number.");
    }

    private org.bukkit.inventory.ItemStack charged(String name, int percent, int spawnChance) {
        if (spawnChance == 0) {
            return Icons.of(Material.GRAY_DYE,
                    "<gray>" + name + ": <dark_gray>nothing to charge",
                    "<gray>Nothing spawns from that yet, so this",
                    "<gray>changes nothing until it does.",
                    "<dark_gray>Click to choose a number anyway.");
        }
        return Icons.of(percent > 0 ? Material.TNT : Material.GUNPOWDER,
                "<white>" + name + ": " + (percent > 0 ? "<green>" + percent + "%" : "<red>never"),
                "<gray>Of the creepers that do spawn there,",
                "<gray>how many come out charged.",
                "<dark_gray>Click to choose a number.");
    }

    private void choose(String label, String key, int current) {
        new AmountChooser(viewer, brand(), this, label, current, 0, 100, value -> {
            lobby.settings().set(key, String.valueOf(value));
            refresh();
        }).open();
    }

    public String describe() {
        return "how often a racer's pickaxe or a chest lid produces a creeper, and how often it is charged";
    }
}
