package de.raindancer.modules.cosmetics;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.cosmetics.command.CosmeticsCommand;

import java.util.List;

/**
 * The commands, declared at bootstrap — Paper registers commands before any module runs — and pointed
 * at a supplier that {@link #ready} fills in once the module enables.
 */
public final class CosmeticsCommands {

    private static volatile CosmeticsServices services;

    private CosmeticsCommands() {
    }

    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("cosmetics", "Paints your name and puts a particle effect around you",
                                new CosmeticsCommand(CosmeticsCommands::require))
                        .aliased("cosmetic")
                        .taking("(nothing) — opens the menu",
                                "name | particle — opens that page",
                                "particle <particle> — wears a vanilla particle",
                                "particle shape ambient|aura|halo|trail|spiral — how it is drawn",
                                "particle colour <colour> — for dust and the tinted ones",
                                "particle density light|normal|dense|very_dense — how thick it is drawn",
                                "particle speed slow|normal|fast|very_fast — how fast it moves",
                                "particle off — takes it off",
                                "name preset <id> — wears a preset",
                                "name set <colours…> [bold|italic|…] — colours in the order typed",
                                "name reset — back to a plain name",
                                "name reset <player> — takes somebody's style off (staff)",
                                "clear [name|particles|all] — takes your own cosmetics off",
                                "clear [name|particles|all] <player> — takes somebody else's off (staff)",
                                "reload — re-reads the palette and presets (staff)"));
    }

    static void ready(CosmeticsServices live) {
        services = live;
    }

    static void stopped() {
        services = null;
    }

    public static boolean isRunning() {
        return services != null;
    }

    private static CosmeticsServices require() {
        CosmeticsServices live = services;
        if (live == null) {
            throw new IllegalStateException("the cosmetics module is not running");
        }
        return live;
    }
}
