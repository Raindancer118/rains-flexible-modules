package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.RuntimeStore;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Deop-and-re-op for tributes who are also server operators: their OP rests for the round rather than giving
 * whoever plays and administrates at once a command nobody else at the table has.
 *
 * <h2>Why the snapshot survives a restart</h2>
 * A tribute de-opped for the round who is never re-opped by a normal path — the server going down before
 * the round finished — must not stay a non-operator forever. {@link RuntimeStore#saveOpSnapshot} is what
 * {@link #restoreFromStore()} reads back after such a restart, so {@link #onPhaseChanged} at
 * {@link GamePhase#FINISHED} still has the right roster to hand OP back to.
 *
 * <h2>Why the OP change itself is a seam</h2>
 * {@code OfflinePlayer.setOp} and {@code isOp} are Bukkit, and {@code ReuseTest} bans
 * {@code getOfflinePlayer(} outright — resolving an offline player's identity is exactly the blocking lookup
 * {@code core.ui.choose.PlayerDirectory} exists to keep off the main thread. So this class never resolves one
 * itself: {@link OpAccess} is handed a UUID and is somebody else's business how that becomes a real
 * operator flag, on whichever thread that takes.
 *
 * <h2>A found bug: the source applied creative mode and the post-elimination teleport unconditionally</h2>
 * {@code OpTrackerService} in the plugin this replaces scheduled the creative-mode switch and the
 * teleport-to-centre for <em>every</em> eliminated tribute who was ever tracked here, ten ticks after their
 * death, with no check that {@code adminReopOnElimination} — or indeed anything about being an admin at
 * all — still applied. Read closely, the early return above it (`if (!deoppedAdmins.contains(uuid)) return;`)
 * already limits it to admin tributes, which is correct; what is missing is that the *same* condition is not
 * re-checked before the effects are applied, so a server with {@code adminCreativeOnElimination} switched
 * off still received a scheduled task that would have silently done nothing useful, holding a
 * {@code Bukkit.getPlayer} lookup and a delayed task alive for no reason. Ported as {@link #onEliminated},
 * which returns a plain {@link EliminationOutcome} the caller only has to act on when either flag in it is
 * {@code true} — there is nothing left running when neither setting is on.
 */
public final class OpTrackerService implements IHungerGamesService {

    /** Reading and changing one player's operator status, resolved however the host resolves an OP. */
    public interface OpAccess {
        boolean isOp(UUID uuid);

        void setOp(UUID uuid, boolean op);
    }

    /** Tells a player something about their OP status, if they happen to be online. A no-op for anybody who
     * is not is exactly the right behaviour — nobody is waiting to read it. */
    @FunctionalInterface
    public interface Notifier {
        void tell(UUID uuid, String message);
    }

    /** What a caller should still do about an eliminated tribute, once this tracker's own bookkeeping is done. */
    public record EliminationOutcome(boolean applyCreative, boolean teleportToCentre) {

        public static final EliminationOutcome NONE = new EliminationOutcome(false, false);

        public boolean isNoop() {
            return !applyCreative && !teleportToCentre;
        }
    }

    private final GameSession session;
    private final OpAccess opAccess;
    private final RuntimeStore runtimeStore;
    private final RoundLogService roundLog;
    private final Notifier notifier;

    private final Set<UUID> deoppedAdmins = new LinkedHashSet<>();

    private HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;

    public OpTrackerService(GameSession session, OpAccess opAccess, RuntimeStore runtimeStore,
                             RoundLogService roundLog, Notifier notifier) {
        this.session = session;
        this.opAccess = opAccess;
        this.runtimeStore = runtimeStore;
        this.roundLog = roundLog;
        this.notifier = notifier;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /** Loads the snapshot after a restart — call before the round resumes ticking. */
    public void restoreFromStore() {
        deoppedAdmins.clear();
        deoppedAdmins.addAll(runtimeStore.loadOpSnapshot());
        if (!deoppedAdmins.isEmpty()) {
            roundLog.log("OP", "OP snapshot restored: " + deoppedAdmins.size() + " de-opped admin(s)");
        }
    }

    /** Who is currently de-opped for the round. */
    public Set<UUID> deoppedAdmins() {
        return Set.copyOf(deoppedAdmins);
    }

    /** Tributes who are operators right now — the plan {@link PreflightCheckService} shows before the round
     * starts, not the roster this class is actually tracking mid-round. */
    public Set<UUID> opParticipants() {
        Set<UUID> result = new LinkedHashSet<>();
        for (var participant : session.participants().all()) {
            if (opAccess.isOp(participant.uuid())) {
                result.add(participant.uuid());
            }
        }
        return Set.copyOf(result);
    }

    /** Called on every round phase change: de-ops on the way into {@link GamePhase#RUNNING}, restores on the
     * way into {@link GamePhase#FINISHED}. */
    public void onPhaseChanged(GamePhase oldPhase, GamePhase newPhase) {
        if (newPhase == GamePhase.RUNNING) {
            deopParticipatingAdmins();
        } else if (newPhase == GamePhase.FINISHED) {
            restoreAllOps();
        }
    }

    private void deopParticipatingAdmins() {
        if (!settings.adminDeopOnStart()) {
            return;
        }
        for (var participant : session.participants().all()) {
            UUID uuid = participant.uuid();
            if (!opAccess.isOp(uuid)) {
                continue;
            }
            opAccess.setOp(uuid, false);
            deoppedAdmins.add(uuid);
            roundLog.log("OP", participant.lastKnownName() + " (tribute) de-opped for the round");
            notifier.tell(uuid, "You are playing this round — your OP rests until you are eliminated or "
                    + "the round ends.");
        }
        runtimeStore.saveOpSnapshot(deoppedAdmins);
    }

    private void restoreAllOps() {
        if (deoppedAdmins.isEmpty() || !settings.adminReopOnFinish()) {
            return;
        }
        for (UUID uuid : Set.copyOf(deoppedAdmins)) {
            reop(uuid, "the round finishing");
        }
        deoppedAdmins.clear();
        runtimeStore.saveOpSnapshot(deoppedAdmins);
    }

    /**
     * A tribute was eliminated. Re-ops them here and now when configured to, and reports what — if anything
     * — the caller still owes them: creative mode, a teleport above the arena's centre, both, or neither.
     *
     * <p>Only acts at all when {@code uuid} is a tribute this tracker de-opped in the first place — an
     * eliminated tribute who was never an operator playing along gets {@link EliminationOutcome#NONE}, every
     * time.
     */
    public EliminationOutcome onEliminated(UUID uuid) {
        if (!deoppedAdmins.contains(uuid)) {
            return EliminationOutcome.NONE;
        }
        if (settings.adminReopOnElimination()) {
            reop(uuid, "being eliminated");
            deoppedAdmins.remove(uuid);
            runtimeStore.saveOpSnapshot(deoppedAdmins);
        }
        return new EliminationOutcome(settings.adminCreativeOnElimination(),
                settings.adminTeleportCenterOnElimination());
    }

    /** A tribute was brought back into the round: an admin who is still an operator has their OP rested again,
     * the same way it would have been had they never been eliminated. */
    public void onRevived(UUID uuid) {
        if (session.phase() != GamePhase.RUNNING || !settings.adminDeopOnStart()) {
            return;
        }
        if (opAccess.isOp(uuid)) {
            opAccess.setOp(uuid, false);
            deoppedAdmins.add(uuid);
            runtimeStore.saveOpSnapshot(deoppedAdmins);
            roundLog.log("OP", nameOf(uuid) + " revived — de-opped again");
        }
    }

    private void reop(UUID uuid, String reason) {
        opAccess.setOp(uuid, true);
        roundLog.log("OP", nameOf(uuid) + " re-opped (" + reason + ")");
        notifier.tell(uuid, "Your OP status has been restored (" + reason + ").");
    }

    private String nameOf(UUID uuid) {
        return session.participants().nameOf(uuid).orElse(uuid.toString());
    }

    @Override
    public String describe() {
        return "deop and re-op for tributes who are also operators";
    }
}
