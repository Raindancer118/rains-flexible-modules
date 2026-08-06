package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.service.GameControlService;
import de.raindancer.modules.hungergames.service.PreflightCheckService;
import de.raindancer.modules.hungergames.service.PreflightCheckService.CheckResult;
import de.raindancer.modules.hungergames.service.PreflightCheckService.Severity;
import de.raindancer.modules.hungergames.store.GameSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Supplier;

/**
 * Eleven questions, answered green, yellow or red, before {@code /startup} is allowed to touch the round.
 *
 * <h2>Why a red finding blocks the start button and a yellow one does not</h2>
 * That distinction is not this screen's to make — it is {@link PreflightCheckService#hasBlockingErrors},
 * the same judgement {@link PreflightCheckService#canStart} reaches through Core's rule chain. This page
 * reads the answer rather than recomputing it, so the button and the sentence above it can never disagree
 * about whether the round may start.
 */
public final class PreflightMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final GameSession session;
    private final PreflightCheckService preflight;
    private final Supplier<List<BorderPhaseConfig>> borderPhases;
    private final GameControlService gameControl;

    public PreflightMenu(Player viewer, Brand brand, Menu parent, GameSession session,
                         PreflightCheckService preflight, Supplier<List<BorderPhaseConfig>> borderPhases,
                         GameControlService gameControl) {
        super(viewer, brand, parent);
        this.session = session;
        this.preflight = preflight;
        this.borderPhases = borderPhases;
        this.gameControl = gameControl;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<aqua>Preflight Check");
    }

    @Override
    public String breadcrumb() {
        return "Preflight";
    }

    @Override
    protected void render() {
        List<CheckResult> results = preflight.runAll(borderPhases.get());
        boolean blocked = preflight.hasBlockingErrors(results);
        long warnings = results.stream().filter(r -> r.severity() == Severity.WARNING).count();

        int slot = 9;
        for (CheckResult result : results) {
            if (slot >= 45) {
                break;
            }
            set(slot++, Icons.of(iconFor(result.severity()), colourFor(result.severity()) + result.name(),
                    "<gray>" + result.detail()));
        }

        set(4, Icons.of(blocked ? Material.RED_CONCRETE
                        : warnings > 0 ? Material.YELLOW_CONCRETE : Material.LIME_CONCRETE,
                blocked ? "<red>Start blocked" : warnings > 0 ? "<yellow>Ready, with warnings" : "<green>Ready",
                blocked ? "<gray>Red findings must be fixed first."
                        : warnings > 0 ? "<gray>" + warnings + " warning(s) — an admin may proceed anyway."
                        : "<gray>Every check passed."));

        GamePhase phase = session.phase();
        if (blocked) {
            set(40, Icons.locked(Icons.of(Material.GRAY_DYE, "<dark_gray>Cannot proceed",
                    "<gray>Fix the red findings above first."), "Blocked by a red finding"));
        } else if (gameControl.canStartup()) {
            set(40, Icons.of(Material.REDSTONE_LAMP, "<green>Run the start-up sequence",
                            warnings > 0 ? "<gray>Proceeds despite " + warnings + " warning(s)."
                                    : "<gray>Tributes into the tubes."),
                    click -> {
                        gameControl.startup(viewer.getUniqueId());
                        refresh();
                    });
        } else if (gameControl.canStart(viewer.getUniqueId())) {
            set(40, Icons.of(Material.LIME_CONCRETE, "<green>Start the round",
                            warnings > 0 ? "<gray>Proceeds despite " + warnings + " warning(s)."
                                    : "<gray>Runs the countdown."),
                    click -> new ConfirmScreen(viewer, brand(), this, "<green>Start the round?",
                            List.of("<gray>Every tribute is released from their platform at once.",
                                    "<gray>There is no putting that back."),
                            () -> {
                                gameControl.start(viewer.getUniqueId());
                                refresh();
                            }).open());
        } else {
            set(40, Icons.of(Material.GRAY_DYE, "<dark_gray>No start step available",
                    "<gray>Current phase: " + phase));
        }
    }

    private static Material iconFor(Severity severity) {
        return switch (severity) {
            case OK -> Material.LIME_DYE;
            case WARNING -> Material.YELLOW_DYE;
            case ERROR -> Material.RED_DYE;
        };
    }

    private static String colourFor(Severity severity) {
        return switch (severity) {
            case OK -> "<green>";
            case WARNING -> "<yellow>";
            case ERROR -> "<red>";
        };
    }

    @Override
    public String describe() {
        return "eleven checks, green, yellow or red, before a round may start";
    }
}
