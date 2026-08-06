package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.hungergames.model.BorderConflict;
import de.raindancer.modules.hungergames.model.BorderMath;
import de.raindancer.modules.hungergames.model.BorderResolution;
import de.raindancer.modules.hungergames.model.BorderSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A border configuration change that turned out to be impossible, and the ways out of it.
 *
 * <h2>What this page does and does not persist</h2>
 * {@link BorderMath#validate} and {@link BorderMath#resolutions} are the whole computation — this page only
 * renders what they return and, on a confirmed choice, calls {@link BorderMath#apply} and hands the result
 * to {@link #onResolved}. Writing the result to {@code border-phases.yml} or the settings file is the
 * caller's job: whoever opened this page already holds {@code BorderPhaseStore} and the settings store, and
 * is the one place "a config change just failed validation" and "here is how to save one" naturally meet.
 * This screen never touches either file itself.
 *
 * <h2>Second click confirms</h2>
 * The same double-click-to-confirm the source plugin's own version used, kept rather than replaced with
 * {@code ConfirmScreen}: every option here already needs to be seen before it can be judged — the numbers in
 * its lore are the entire point — and a separate three-row dialog would just repeat the same lore a second
 * time. What matters is that nothing is applied on the first click, and it is not.
 *
 * <h2>No parent, by design</h2>
 * What opens this page is not a click inside another one of this module's screens — it is a config edit
 * that has just been found to be impossible, wherever that edit was made. See {@code ScreenGrammarTest}'s
 * exempt list.
 */
public final class BorderConflictMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final BorderSettings draftSettings;
    private final Optional<Duration> draftGameDuration;
    private final BorderConflict conflict;
    private final Consumer<BorderMath.ApplyResult> onResolved;
    private final Runnable onDiscard;

    private BorderResolution pendingConfirmation;

    public BorderConflictMenu(Player viewer, Brand brand, BorderSettings draftSettings,
                              Optional<Duration> draftGameDuration, BorderConflict conflict,
                              Consumer<BorderMath.ApplyResult> onResolved, Runnable onDiscard) {
        super(viewer, brand, null);
        this.draftSettings = draftSettings;
        this.draftGameDuration = draftGameDuration;
        this.conflict = conflict;
        this.onResolved = onResolved;
        this.onDiscard = onDiscard;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Border conflict");
    }

    @Override
    public String breadcrumb() {
        return "Border conflict";
    }

    @Override
    protected void render() {
        band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>Phase " + (conflict.phaseIndex() + 1),
                describeConflict(),
                String.format(Locale.ROOT, "<dark_gray>Implied speed: %.2f blocks/s (limit %.2f)",
                        conflict.impliedSpeed(), conflict.limit()),
                "<gold>Nothing is saved yet."));

        List<BorderResolution> options = BorderMath.resolutions(draftSettings, conflict, draftGameDuration);
        int column = 1;
        for (BorderResolution option : options) {
            if (column > 7) {
                break;
            }
            band(MenuLayout.RULES, column++, icon(option), click -> choose(option));
        }
    }

    private void choose(BorderResolution option) {
        if (option instanceof BorderResolution.Discard) {
            viewer.sendMessage(MINI.deserialize("<yellow>Discarded — nothing was saved."));
            onDiscard.run();
            return;
        }
        if (!option.equals(pendingConfirmation)) {
            pendingConfirmation = option;
            refresh();
            return;
        }
        BorderMath.ApplyResult applied = BorderMath.apply(draftSettings, conflict.phaseIndex(), option);
        onResolved.accept(applied);
        viewer.sendMessage(MINI.deserialize("<green>Resolved — the change and the fix are saved together."));
        viewer.closeInventory();
    }

    private ItemStack icon(BorderResolution option) {
        boolean pending = option.equals(pendingConfirmation);
        record Display(Material material, String title, List<String> effects) {
        }
        Display display = switch (option) {
            case BorderResolution.AdjustDuration r -> new Display(Material.CLOCK, "Lengthen the phase",
                    List.of("New duration: " + r.newDuration().toSeconds() + "s",
                            String.format(Locale.ROOT, "Speed: %.2f blocks/s per edge", r.resultingSpeed())));
            case BorderResolution.AdjustTarget r -> new Display(Material.TARGET, "Raise the target size",
                    List.of("New target: " + (int) r.newTarget() + " blocks",
                            String.format(Locale.ROOT, "Speed: %.2f blocks/s per edge", r.resultingSpeed())));
            case BorderResolution.ShiftStart r -> new Display(Material.REPEATER, "Move the start earlier",
                    List.of("New start: " + r.newStart().toMinutes() + "m into the round"));
            case BorderResolution.AdjustGameTime r -> new Display(Material.SUNFLOWER, "Lengthen the round",
                    List.of("New round length: " + r.newGameDuration().toMinutes() + "m",
                            "This phase stays as configured"));
            case BorderResolution.UseSpeedAsMax r -> new Display(Material.COMPARATOR, "Treat speed as a ceiling",
                    List.of(String.format(Locale.ROOT, "Effective speed: %.2f blocks/s", r.effectiveSpeed()),
                            "Resulting duration: " + r.effectiveDuration().toSeconds() + "s"));
            case BorderResolution.Discard ignored -> new Display(Material.BARRIER, "Discard the change",
                    List.of("Nothing is saved"));
        };

        List<String> lore = new ArrayList<>(display.effects());
        lore.add("");
        lore.add(pending ? "<gold>CLICK AGAIN TO CONFIRM" : "<yellow>Click to choose this — asks again to confirm.");

        return Icons.of(pending ? Material.LIME_CONCRETE : display.material(),
                (pending ? "<green>" : "<yellow>") + display.title(), lore);
    }

    private String describeConflict() {
        return "<gray>" + switch (conflict.type()) {
            case SPEED_EXCEEDS_MAX -> "The border would shrink faster than the fairness limit.";
            case TARGET_BELOW_MINIMUM -> "The target size is below the minimum.";
            case TARGET_NOT_SHRINKING -> "The target size is not smaller than the phase's start size.";
            case EXCEEDS_GAME_TIME -> "The phase ends after the round is scheduled to end.";
            case PHASES_OUT_OF_ORDER -> "The phases' triggers are not in chronological order.";
        };
    }

    @Override
    public String describe() {
        return "a border conflict, and the concrete ways to resolve it";
    }
}
