package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Supplier;

/**
 * {@link GameEndpoints.GameControl}, over the real services — {@link GameControlService},
 * {@link PreflightCheckService}, {@link BorderService} and {@link VirtualTime}.
 *
 * <h2>Why this exists at all</h2>
 * {@code GameEndpoints} was written, and fully tested, against a small interface stating exactly what the
 * HTTP layer needs — deliberately, so that wiring the real services in would be "one line wherever this
 * module assembles its services", per that class's own note. Nothing ever wrote that line: this class is
 * it. Before this, every one of {@code GameEndpoints}'s eighteen routes answered from a null field, and
 * the module's own boot log had no way to say so — the socket never opened at all, because
 * {@link HttpApiService} itself was never constructed either.
 *
 * <p>Package-private like the interface it implements: nothing outside {@code service} needs to know this
 * adapter exists, only that {@code HungerGamesWiring} can build one from services it already has.
 */
public final class GameControlApiAdapter implements GameEndpoints.GameControl {

    private final GameControlService control;
    private final PreflightCheckService preflight;
    private final BorderService border;
    private final VirtualTime virtualTime;
    private final Supplier<List<BorderPhaseConfig>> borderPhases;

    public GameControlApiAdapter(GameControlService control, PreflightCheckService preflight, BorderService border,
                          VirtualTime virtualTime, Supplier<List<BorderPhaseConfig>> borderPhases) {
        this.control = control;
        this.preflight = preflight;
        this.border = border;
        this.virtualTime = virtualTime;
        this.borderPhases = borderPhases;
    }

    @Override
    public boolean canInit() {
        return control.canInit();
    }

    @Override
    public boolean canStartup() {
        return control.canStartup();
    }

    @Override
    public boolean canStart() {
        // The API has no console-style "who is asking" concept for the countdown-already-running guard —
        // every caller is anonymous behind a key. False is the safe answer to "is somebody's countdown
        // already ticking": it only makes canStart() answer no when a real caller-scoped check would have
        // said yes anyway, never the other way round.
        return control.canStart(new java.util.UUID(0, 0));
    }

    @Override
    public boolean canEndRound() {
        return control.canEndRound();
    }

    @Override
    public int minPlayers() {
        return GameControlService.MIN_PLAYERS;
    }

    @Override
    public int maxPlayers() {
        return GameControlService.MAX_PLAYERS;
    }

    @Override
    public boolean init(Player admin, int playerCount) {
        return control.init(admin.getUniqueId(), playerCount).isEmpty();
    }

    @Override
    public boolean startup(Player admin) {
        return control.startup(admin.getUniqueId()).isEmpty();
    }

    @Override
    public boolean start(Player admin) {
        return control.start(admin.getUniqueId()).isEmpty();
    }

    @Override
    public boolean endRound(String actor) {
        return control.endRound();
    }

    @Override
    public void prepareNextRound(String actor) {
        control.prepareNextRound();
    }

    @Override
    public List<GameEndpoints.PreflightResult> preflight() {
        return preflight.runAll(borderPhases.get()).stream()
                .map(result -> new GameEndpoints.PreflightResult(result.name(), result.severity().name(),
                        result.detail(), result.isError()))
                .toList();
    }

    @Override
    public double timeMultiplier() {
        return virtualTime.multiplier();
    }

    @Override
    public double setTimeMultiplier(double multiplier) {
        virtualTime.setMultiplier(multiplier);
        return virtualTime.multiplier();
    }

    @Override
    public double borderCurrentSize() {
        return border.currentSize();
    }

    @Override
    public int borderNextPhaseIndex() {
        return border.nextPhaseIndex();
    }

    @Override
    public long borderShrinkTo(double size) {
        return border.overrideShrinkTo(size);
    }

    @Override
    public void borderResetToInitial() {
        border.resetToInitial();
    }
}
