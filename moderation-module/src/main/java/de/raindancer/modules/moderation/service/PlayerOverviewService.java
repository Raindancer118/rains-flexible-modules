package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.presence.Away;
import de.raindancer.core.moderation.rules.ServerRule;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.MiningLedger;
import de.raindancer.modules.moderation.model.PlayerOverview;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Gathers a {@link PlayerOverview}: what is kept in memory at once, what lives in a database (old names, attempts,
 * balance) off the server's threads, and where somebody online stands on their own thread. {@code then} is
 * called on whichever thread finished last — hop to the viewer's before touching a window.
 */
public final class PlayerOverviewService implements IModerationService {

    private final ModerationServices services;

    public PlayerOverviewService(ModerationServices services) {
        this.services = services;
    }

    @Override
    public void settings(ModerationSettings settings) {
        // Reads the live services each time; nothing to keep.
    }

    public void gather(UUID subject, String name, Consumer<PlayerOverview> then) {
        PlayerOverview.Builder built = inMemory(subject, name);
        Scheduling.async(services.plugin(), () -> {
            fromDatabases(subject, built);
            Player online = services.server().getPlayer(subject);
            if (online == null) {
                then.accept(built.build());
                return;
            }
            Scheduling.entity(services.plugin(), online, () -> {
                Location at = online.getLocation();
                String client = de.raindancer.core.platform.bukkit.ClientVersions.of(subject)
                        .map(version -> version.name()).orElse(null);
                built.where(new PlayerOverview.Where(at.getWorld().getName(), at.getBlockX(), at.getBlockY(),
                        at.getBlockZ(), online.getPing(), client));
                then.accept(built.build());
            });
        });
    }

    private PlayerOverview.Builder inMemory(UUID subject, String name) {
        OfflinePlayer them = services.server().getOfflinePlayer(subject);
        boolean online = services.server().getPlayer(subject) != null;
        List<Punishment> inForce = new ArrayList<>();
        for (PunishmentKind kind : PunishmentKind.values()) {
            if (kind.isLasting()) {
                services.punishments().active(subject, kind).ifPresent(inForce::add);
            }
        }
        List<Punishment> record = services.punishmentService().history(subject);
        Map<String, Integer> offences = new LinkedHashMap<>();
        for (ServerRule rule : services.ruleBreaches().rules()) {
            int count = services.ruleBreaches().offencesAgainst(subject, rule);
            if (count > 0) {
                offences.put(rule.number() + ". " + rule.title(), count);
            }
        }
        var reports = services.reportService().about(subject);
        MiningLedger ledger = services.xrayDetection().ledgerOf(subject);
        PlayerOverview.Xray xray = new PlayerOverview.Xray(services.xrayDetection().probabilityFor(subject),
                ledger == null ? 0 : ledger.baitReached(), ledger == null ? 0 : ledger.baitExpected(),
                services.xrayDetection().findsFor(subject).size());
        var playtime = de.raindancer.core.RainsCore.isAvailable()
                ? de.raindancer.core.RainsCore.get().playtime() : null;
        return PlayerOverview.of(subject, name)
                .presence(PlayerOverview.Presence.of(online, online && Away.isAway(subject),
                        them.hasPlayedBefore() || online || (playtime != null && playtime.isKnown(subject))))
                .seen(them.getFirstPlayed() > 0 ? Instant.ofEpochMilli(them.getFirstPlayed()) : null,
                        them.getLastSeen() > 0 ? Instant.ofEpochMilli(them.getLastSeen()) : null)
                .playtime(playtime != null && playtime.isKnown(subject)
                        ? Duration.ofMinutes(playtime.minutes(subject)) : null)
                .inForce(inForce)
                .record(services.standingRule().entriesOnRecord(record),
                        services.standingRule().recentWarnings(record, Instant.now()), offences)
                .reports((int) reports.stream().filter(report -> report.isOpen()).count(), reports.size())
                .notes(services.noteService().countAbout(subject))
                .xray(xray);
    }

    private void fromDatabases(UUID subject, PlayerOverview.Builder built) {
        if (!de.raindancer.core.RainsCore.isAvailable()) {
            return;
        }
        var core = de.raindancer.core.RainsCore.get();
        if (core.chatLog() != null) {
            built.oldNames(core.chatLog().namesOf(subject));
        }
        if (core.blockedAttempts() != null) {
            built.attempts(core.blockedAttempts().count(List.of(subject)));
        }
        de.raindancer.core.social.economy.Economies.current().ifPresent(economy -> {
            try {
                built.money(economy.format(economy.balance(subject)),
                        services.fines().hasEconomy()
                                ? de.raindancer.core.social.economy.Fees.format(services.fines().owed(subject)) : null);
            } catch (RuntimeException unreadable) {
                // An economy that cannot answer leaves the balance out rather than the page.
            }
        });
    }
}
