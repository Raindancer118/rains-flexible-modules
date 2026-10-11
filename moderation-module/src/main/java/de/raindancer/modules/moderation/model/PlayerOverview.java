package de.raindancer.modules.moderation.model;

import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.world.time.Moments;
import de.raindancer.core.world.time.Times;
import org.bukkit.Material;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the player screen's top row says about somebody, gathered once and read as short lore lines — so
 * the screen and the console print the same thing, and both are tested without a server.
 *
 * @param oldNames  every name they chatted under, newest first; the current one is left out when shown
 * @param where     where they stand right now, null when offline
 * @param inForce   the punishments in force, any order
 * @param ruleOffences per rule title, how often they broke it
 * @param balance   their balance, formatted; null without an economy
 * @param owed      unpaid fines, formatted; null without an economy
 */
public record PlayerOverview(UUID id, String name, List<String> oldNames, Presence presence, Instant firstJoined,
                             Instant lastSeen, Duration playtime, Where where, List<Punishment> inForce,
                             int recordEntries, int recentWarnings, Map<String, Integer> ruleOffences,
                             int openReports, int reports, int notes, int attempts, Xray xray, String balance,
                             String owed) {

    /** Whether they are here. */
    public enum Presence {
        ONLINE(Material.LIME_CONCRETE, "<green>Online now"),
        AWAY(Material.ORANGE_CONCRETE, "<gold>Online, away"),
        OFFLINE(Material.RED_CONCRETE, "<red>Offline"),
        NEVER(Material.GRAY_CONCRETE, "<gray>Never joined");

        private final Material icon;
        private final String title;

        Presence(Material icon, String title) {
            this.icon = icon;
            this.title = title;
        }

        public Material icon() {
            return icon;
        }

        public String title() {
            return title;
        }

        public static Presence of(boolean online, boolean away, boolean playedBefore) {
            if (online) {
                return away ? AWAY : ONLINE;
            }
            return playedBefore ? OFFLINE : NEVER;
        }
    }

    /** The worst thing in force. */
    public enum Standing {
        BANNED(Material.RED_BANNER, "<red>Banned"),
        MUTED(Material.YELLOW_BANNER, "<yellow>Muted"),
        FROZEN(Material.LIGHT_BLUE_BANNER, "<aqua>Frozen"),
        CLEAR(Material.LIGHT_GRAY_BANNER, "<gray>Nothing in force");

        private final Material icon;
        private final String title;

        Standing(Material icon, String title) {
            this.icon = icon;
            this.title = title;
        }

        public Material icon() {
            return icon;
        }

        public String title() {
            return title;
        }

        static Standing of(PunishmentKind kind) {
            return switch (kind) {
                case BAN -> BANNED;
                case MUTE -> MUTED;
                case FREEZE -> FROZEN;
                default -> CLEAR;
            };
        }
    }

    /** Where somebody online is, and how they are connected. */
    public record Where(String world, int x, int y, int z, int ping, String client) {
    }

    /** The x-ray ledger in brief. */
    public record Xray(int percent, double baitReached, double baitExpected, int finds) {
        public static final Xray NONE = new Xray(0, 0, 0, 0);
    }

    public PlayerOverview {
        oldNames = List.copyOf(oldNames);
        inForce = List.copyOf(inForce);
        ruleOffences = Map.copyOf(ruleOffences);
    }

    /** Floodgate gives Bedrock players an id with nothing in its upper half, and usually a name starting with a dot. */
    public static boolean isBedrock(UUID id, String name) {
        return (id != null && id.getMostSignificantBits() == 0) || (name != null && name.startsWith("."));
    }

    public boolean bedrock() {
        return isBedrock(id, name);
    }

    public Standing standing() {
        return inForce.stream().map(one -> Standing.of(one.kind()))
                .min(Comparator.naturalOrder()).orElse(Standing.CLEAR);
    }

    // ------------------------------------------------------------------ the cards

    public List<String> presenceLore(ZoneId zone, Instant now, OverviewAccess access) {
        List<String> lore = new ArrayList<>();
        if (where != null && access.location()) {
            lore.add("<gray>In <white>" + safe(where.world()) + "</white> at <white>" + where.x() + " " + where.y() + " "
                    + where.z());
            lore.add("<gray>Ping <white>" + where.ping() + " ms</white>"
                    + (where.client() == null ? "" : " · client <white>" + where.client()));
        } else if (lastSeen != null && presence == Presence.OFFLINE) {
            lore.add("<gray>Last seen <white>" + Moments.show(lastSeen, zone));
            lore.add("<dark_gray>" + Times.brief(Duration.between(lastSeen, now)) + " ago");
        }
        lore.add("<gray>" + (bedrock() ? "Bedrock" : "Java") + " edition");
        return lore;
    }

    public List<String> standingLore(Instant now) {
        List<String> lore = new ArrayList<>();
        List<Punishment> sorted = inForce.stream()
                .sorted(Comparator.comparing(one -> Standing.of(one.kind()))).toList();
        for (Punishment one : sorted) {
            String left = one.remainingAt(now)
                    .map(rest -> Times.brief(rest.toMinutes() > 0 ? Duration.ofMinutes((rest.toSeconds() + 59) / 60) : rest) + " left")
                    .orElse(one.isPermanent() ? "for ever" : "over");
            lore.add(Standing.of(one.kind()).title() + " <dark_gray>· <gray>" + left);
            lore.add("<dark_gray>  " + safe(one.reason()));
        }
        if (lore.isEmpty()) {
            lore.add("<green>Nothing in force.");
        }
        return lore;
    }

    public List<String> identityLore(ZoneId zone, OverviewAccess access) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>" + id);
        lore.add("<gray>" + (bedrock() ? "Bedrock" : "Java") + " edition");
        List<String> before = oldNames.stream().filter(old -> !old.equalsIgnoreCase(name)).toList();
        if (!before.isEmpty() && access.chat()) {
            lore.add("<gray>Also known as <white>" + safe(String.join(", ", before.subList(0, Math.min(4, before.size())))));
        }
        if (firstJoined != null) {
            lore.add("<gray>First joined <white>" + Moments.show(firstJoined, zone));
        }
        if (playtime != null) {
            lore.add("<gray>Played <white>" + Times.brief(playtime));
        }
        return lore;
    }

    public List<String> recordLore(OverviewAccess access) {
        List<String> lore = new ArrayList<>();
        if (access.record()) {
            recordLines(lore);
        }
        if (access.reports()) {
            lore.add("<gray>Reports: <white>" + openReports + "</white> open of <white>" + reports);
        }
        if (access.notes()) {
            lore.add("<gray>Notes: <white>" + notes);
        }
        if (access.attempts()) {
            lore.add("<gray>Attempts while punished: <white>" + attempts);
        }
        if (access.record() && balance != null) {
            lore.add("<gray>Balance: <white>" + balance + (owed == null ? "" : "</white> · owes <white>" + owed));
        }
        if (lore.isEmpty()) {
            lore.add("<dark_gray>Nothing here you may read.");
        }
        return lore;
    }

    private void recordLines(List<String> lore) {
        lore.add("<gray>On record: <white>" + recordEntries + "</white> · recent warnings: <white>" + recentWarnings);
        ruleOffences.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(4)
                .forEach(rule -> lore.add("<gray>· " + safe(rule.getKey()) + ": <white>" + rule.getValue() + "×"));
    }

    public List<String> xrayLore(OverviewAccess access) {
        List<String> lore = new ArrayList<>();
        if (!access.reports()) {
            lore.add("<dark_gray>For whoever may read reports.");
            return lore;
        }
        Xray ledger = xray == null ? Xray.NONE : xray;
        String colour = ledger.percent() >= 80 ? "<red>" : ledger.percent() >= 50 ? "<yellow>" : "<green>";
        lore.add("<gray>Suspicion " + colour + ledger.percent() + "%");
        lore.add("<gray>Baits reached <white>" + number(ledger.baitReached()) + " of " + number(ledger.baitExpected())
                + "</white> expected");
        lore.add("<gray>Veins and baits on record: <white>" + ledger.finds());
        return lore;
    }

    /** Text somebody typed — a reason, a name — kept out of the markup. */
    private static String safe(String typed) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(typed == null ? "" : typed);
    }

    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    // ------------------------------------------------------------------ building

    public static Builder of(UUID id, String name) {
        return new Builder(id, name);
    }

    /** Filled in piece by piece as the screen gathers it; everything not given is empty or zero. */
    public static final class Builder {
        private final UUID id;
        private final String name;
        private List<String> oldNames = List.of();
        private Presence presence = Presence.NEVER;
        private Instant firstJoined;
        private Instant lastSeen;
        private Duration playtime;
        private Where where;
        private List<Punishment> inForce = List.of();
        private int recordEntries;
        private int recentWarnings;
        private Map<String, Integer> ruleOffences = Map.of();
        private int openReports;
        private int reports;
        private int notes;
        private int attempts;
        private Xray xray = Xray.NONE;
        private String balance;
        private String owed;

        private Builder(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        public Builder oldNames(List<String> names) {
            this.oldNames = names == null ? List.of() : names;
            return this;
        }

        public Builder presence(Presence now) {
            this.presence = now;
            return this;
        }

        public Builder seen(Instant first, Instant last) {
            this.firstJoined = first;
            this.lastSeen = last;
            return this;
        }

        public Builder playtime(Duration played) {
            this.playtime = played;
            return this;
        }

        public Builder where(Where at) {
            this.where = at;
            return this;
        }

        public Builder inForce(List<Punishment> punishments) {
            this.inForce = punishments == null ? List.of() : punishments;
            return this;
        }

        public Builder record(int entries, int warnings, Map<String, Integer> offences) {
            this.recordEntries = entries;
            this.recentWarnings = warnings;
            this.ruleOffences = offences == null ? Map.of() : offences;
            return this;
        }

        public Builder reports(int open, int all) {
            this.openReports = open;
            this.reports = all;
            return this;
        }

        public Builder notes(int count) {
            this.notes = count;
            return this;
        }

        public Builder attempts(int count) {
            this.attempts = count;
            return this;
        }

        public Builder xray(Xray ledger) {
            this.xray = ledger == null ? Xray.NONE : ledger;
            return this;
        }

        public Builder money(String balance, String owed) {
            this.balance = balance;
            this.owed = owed;
            return this;
        }

        public PlayerOverview build() {
            return new PlayerOverview(id, name, oldNames, presence, firstJoined, lastSeen, playtime, where, inForce,
                    recordEntries, recentWarnings, ruleOffences, openReports, reports, notes, attempts, xray, balance,
                    owed);
        }
    }
}
