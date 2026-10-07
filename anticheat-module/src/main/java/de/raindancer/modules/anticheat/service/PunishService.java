package de.raindancer.modules.anticheat.service;

import de.raindancer.core.RainsCore;
import de.raindancer.core.moderation.punishment.Durations;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.anticheat.AntiCheatSettings;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** Kicks and bans, through RainsCore's own records, so they show up in every player's history. */
public final class PunishService implements IAntiCheatService {

    private final Plugin plugin;
    private final RainsCore core;
    private final Messages messages;
    private final AlertService alerts;
    private volatile AntiCheatSettings settings = AntiCheatSettings.DEFAULTS;

    public PunishService(Plugin plugin, RainsCore core, Messages messages, AlertService alerts) {
        this.plugin = plugin;
        this.core = core;
        this.messages = messages;
        this.alerts = alerts;
    }

    public static String reason(CheckType check) {
        return "Unfair advantage: " + check.title() + " (RainsAntiCheat)";
    }

    public void kick(Player player, PlayerTrack track, CheckType check, double level) {
        if (track.kicking) {
            return;
        }
        track.kicking = true;
        Scheduling.entity(plugin, player, () -> {
            core.punishments().punish(player.getUniqueId(), PunishmentKind.KICK, null, reason(check), null);
            player.kick(messages.get("anticheat.kick-screen", "check", check.title()));
            alerts.staff("anticheat.kicked", "player", player.getName(), "check", check.title(),
                    "level", String.format(Locale.ROOT, "%.1f", level));
        });
    }

    public void ban(Player player, PlayerTrack track, CheckType check, double level) {
        if (track.kicking) {
            return;
        }
        track.kicking = true;
        String text = settings.banLength();
        Duration length = Durations.isForEver(text) ? null : Durations.parse(text).orElse(Duration.ofDays(7));
        String shown = length == null ? "forever" : Durations.describe(length);
        Scheduling.entity(plugin, player, () -> {
            core.punishments().punish(player.getUniqueId(), PunishmentKind.BAN, null, reason(check), length);
            core.banBridge().mirrorBan(player.getUniqueId(), reason(check),
                    length == null ? null : Instant.now().plus(length));
            player.kick(messages.get("anticheat.ban-screen", "check", check.title(), "length", shown));
            alerts.staff("anticheat.banned", "player", player.getName(), "check", check.title(),
                    "level", String.format(Locale.ROOT, "%.1f", level), "length", shown);
        });
    }

    /** A client the server refuses, kicked without a record: it is a rule about software, not a sentence. */
    public void refuseClient(Player player, String what) {
        Scheduling.entity(plugin, player, () -> player.kick(messages.get("anticheat.client-screen", "client", what)));
    }

    @Override
    public void settings(AntiCheatSettings fresh) {
        this.settings = fresh == null ? AntiCheatSettings.DEFAULTS : fresh;
    }

    @Override
    public String describe() {
        return "kicking and banning through RainsCore's records";
    }
}
