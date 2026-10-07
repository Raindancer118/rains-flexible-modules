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
    private volatile AntiCheatSettings settings = AntiCheatSettings.DEFAULTS;

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
        }
        if (decision.ban()) {
            punishments.ban(player, track, check, level);
            track.violations().scale(check, 0.5);
        } else if (decision.kick()) {
            punishments.kick(player, track, check, level);
            track.violations().scale(check, 0.5);
        }
        return decision;
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
