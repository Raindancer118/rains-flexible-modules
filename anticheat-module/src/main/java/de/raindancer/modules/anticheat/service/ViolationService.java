package de.raindancer.modules.anticheat.service;

import de.raindancer.modules.anticheat.AntiCheatSettings;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.Evidence;
import de.raindancer.modules.anticheat.model.Flag;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import de.raindancer.modules.anticheat.rules.ActionRule;
import de.raindancer.modules.anticheat.store.EvidenceLog;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Where every failed check goes: it raises the violation level, is written down, alerts staff and, at
 * the levels the check sets, kicks or bans. The caller gets the decision back and does the setback or
 * cancel itself, because only the caller holds the event or the position to undo.
 */
public final class ViolationService implements IAntiCheatService {

    private final ActionRule rule;
    private final AlertService alerts;
    private final PunishService punishments;
    private final EvidenceLog evidence;
    private final de.raindancer.modules.anticheat.rules.ImprobableRule improbable = new de.raindancer.modules.anticheat.rules.ImprobableRule();
    private final java.util.Map<java.util.UUID, Long> lastImprobable = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile AntiCheatSettings settings = AntiCheatSettings.DEFAULTS;

    private volatile de.raindancer.modules.anticheat.store.ReplayStore replays;
    private final java.util.Map<String, Long> lastReplay = new java.util.concurrent.ConcurrentHashMap<>();

    public ViolationService(ActionRule rule, AlertService alerts, PunishService punishments, EvidenceLog evidence) {
        this.rule = rule;
        this.alerts = alerts;
        this.punishments = punishments;
        this.evidence = evidence;
    }

    /** Whether a check is worth running for this player right now. */
    public boolean runs(PlayerTrack track, CheckType check) {
        AntiCheatSettings now = settings;
        if (!rule.runs(check, now) || track.bypassAll || track.bypassed.contains(check)) {
            return false;
        }
        return !(track.bedrock && now.exemptBedrock());
    }

    public ActionRule.Decision flag(Player player, PlayerTrack track, Flag flag) {
        CheckType check = flag.check();
        if (!runs(track, check)) {
            return ActionRule.Decision.NOTHING;
        }
        AntiCheatSettings now = settings;
        double level = track.violations().add(check, flag.weight());
        ActionRule.Decision decision = rule.decide(check, level, now);
        if (now.evidence()) {
            Location at = player.getLocation();
            evidence.add(player.getUniqueId(), player.getName(), new Evidence(System.currentTimeMillis(), check.key(),
                    level, flag.detail(), at.getWorld() == null ? "?" : at.getWorld().getName(), at.getBlockX(),
                    at.getBlockY(), at.getBlockZ(), track.ping, Bukkit.getTPS()[0]));
        }
        alerts.verbose(player, check, level, flag.detail());
        if (decision.alert()) {
            alerts.alert(player, check, level, flag.detail(), track.ping);
            freeze(track, check, flag.detail());
        }
        if (decision.ban()) {
            punishments.ban(player, track, check, level);
            track.violations().scale(check, 0.5);
        } else if (decision.kick()) {
            punishments.kick(player, track, check, level);
            track.violations().scale(check, 0.5);
        }
        if (check != CheckType.IMPROBABLE) {
            judgeImprobable(player, track);
        }
        return decision;
    }

    private void judgeImprobable(Player player, PlayerTrack track) {
        long now = track.now();
        Long last = lastImprobable.get(track.id());
        if (last != null && now - last < 60_000) {
            return;
        }
        de.raindancer.modules.anticheat.rules.Judgement judged = improbable.judge(track.violations().snapshot());
        if (judged.failed()) {
            lastImprobable.put(track.id(), now);
            flag(player, track, Flag.of(CheckType.IMPROBABLE, judged.reason()));
        }
    }

    public void forget(java.util.UUID player) {
        lastImprobable.remove(player);
        lastReplay.keySet().removeIf(key -> key.startsWith(player.toString()));
    }

    /** Where frozen replays go; without one, alerts keep no replay. */
    public void replaysTo(de.raindancer.modules.anticheat.store.ReplayStore store) {
        this.replays = store;
    }

    /** The movement leading up to an alert, kept at most once per check every ten seconds. */
    private void freeze(PlayerTrack track, CheckType check, String detail) {
        de.raindancer.modules.anticheat.store.ReplayStore store = replays;
        if (store == null) {
            return;
        }
        long now = track.now();
        String key = track.id() + ":" + check.key();
        Long last = lastReplay.get(key);
        if (last != null && now - last < 10_000) {
            return;
        }
        lastReplay.put(key, now);
        store.add(track.id(), new de.raindancer.modules.anticheat.model.Replays.Replay(System.currentTimeMillis(), check.key(),
                detail, track.recorder.frames()));
    }

    public AntiCheatSettings settings() {
        return settings;
    }

    @Override
    public void settings(AntiCheatSettings fresh) {
        this.settings = fresh == null ? AntiCheatSettings.DEFAULTS : fresh;
        evidence.capacity(this.settings.evidencePerPlayer());
    }

    @Override
    public String describe() {
        return "turning failed checks into alerts, setbacks, kicks and bans";
    }
}
