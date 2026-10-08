package de.raindancer.modules.moderation.service;

import de.raindancer.modules.moderation.util.WorldGrid;

import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.DigPath;
import de.raindancer.modules.moderation.model.MiningLedger;
import de.raindancer.modules.moderation.model.OreDensity;
import de.raindancer.modules.moderation.model.OreKind;
import de.raindancer.modules.moderation.model.RockBand;
import de.raindancer.modules.moderation.model.SeenOres;
import de.raindancer.modules.moderation.util.BlockGrid;
import de.raindancer.modules.moderation.rules.HiddenOreRule;
import de.raindancer.modules.moderation.rules.RevealRule;
import de.raindancer.modules.moderation.rules.SteeringRule;
import de.raindancer.modules.moderation.rules.XrayVerdictRule;
import de.raindancer.modules.moderation.store.OreDensityStore;
import de.raindancer.modules.moderation.store.XrayLedgers;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * X-ray detection, second generation: every block a player digs updates their {@link MiningLedger}
 * — what the dig revealed, whether a new vein was among it, whether it reached one of their bait
 * ores, whether a turn favoured hidden ore — and the {@link XrayVerdictRule} turns the ledger into
 * a probability. A report is filed once mining is less likely than one in ten to the
 * {@code xray.report-score} for somebody who cannot see through stone.
 */
public final class XrayEvidenceService implements IModerationService {

    private static final int CENSUS_RADIUS = 8;

    private final ReportService reports;
    private final XrayLedgers ledgers;
    private final OreDensityStore density;
    private final HoneypotService honeypots;
    private final LongSupplier clock;
    private final RevealRule reveals = new RevealRule();
    private final HiddenOreRule search = new HiddenOreRule();
    private final SteeringRule steering = new SteeringRule();
    private final XrayVerdictRule verdicts = new XrayVerdictRule();
    private final Map<UUID, SeenOres> seen = new ConcurrentHashMap<>();
    private final Map<UUID, DigPath> paths = new ConcurrentHashMap<>();
    private final Map<UUID, XrayVerdictRule.Verdict> latest = new ConcurrentHashMap<>();
    private final Cooldowns<UUID> between = new Cooldowns<>();
    private volatile ModerationSettings settings;
    private volatile Set<OreKind> watched = EnumSet.of(OreKind.DIAMOND);

    public XrayEvidenceService(ReportService reports, XrayLedgers ledgers, OreDensityStore density,
                               HoneypotService honeypots, LongSupplier clock, ModerationSettings settings) {
        this.reports = reports;
        this.ledgers = ledgers;
        this.density = density;
        this.honeypots = honeypots;
        this.clock = clock;
        settings(settings);
    }

    /** One block dug by a player, while it still stands — called from the break event. */
    public void dug(Player player, Block block) {
        ModerationSettings now = settings;
        if (!now.xrayDetectionEnabled()) {
            return;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return;
        }
        Material type = block.getType();
        OreKind dugKind = OreKind.of(type).orElse(null);
        if (dugKind == null && !OreKind.isHostRock(type)) {
            return;
        }
        UUID id = player.getUniqueId();
        World world = block.getWorld();
        String environment = world.getEnvironment().name();
        MiningLedger ledger = ledgers.of(id, player.getName());
        ledger.decay(clock.getAsLong(), now.xrayHalfLifeDays() * 86_400_000.0);
        SeenOres theirs = seen.computeIfAbsent(id, ignored -> new SeenOres());
        if (dugKind != null) {
            theirs.add(block.getX(), block.getY(), block.getZ());
        }

        WorldGrid grid = new WorldGrid(world);
        boolean changed = false;
        List<RevealRule.Revealed> shown = reveals.revealedBy(grid, block.getX(), block.getY(), block.getZ());
        List<int[]> rock = new ArrayList<>();
        for (RevealRule.Revealed revealed : shown) {
            RockBand band = RockBand.of(environment, revealed.y());
            ledger.revealed(band, 1);
            OreKind kind = OreKind.of(revealed.type()).orElse(null);
            if (kind != null) {
                if (!theirs.touches(revealed.x(), revealed.y(), revealed.z())) {
                    ledger.vein(band, kind);
                    changed |= watched.contains(kind);
                    ledger.dug(world.getName(), revealed.x(), revealed.y(), revealed.z(), kind.ordinal());
                }
                theirs.add(revealed.x(), revealed.y(), revealed.z());
            } else if (OreKind.isHostRock(revealed.type())) {
                rock.add(new int[]{revealed.x(), revealed.y(), revealed.z()});
            }
        }
        HoneypotService.Reach reach = honeypots.revealed(player, world, rock);
        if (reach.expected() > 0 || reach.reached() > 0) {
            ledger.bait(reach.expected(), reach.reached());
            changed |= reach.reached() > 0;
            for (HoneypotService.Key key : reach.positions()) {
                ledger.dug(world.getName(), key.x(), key.y(), key.z(), -2);
            }
        }
        ledger.dug(world.getName(), block.getX(), block.getY(), block.getZ(), dugKind == null ? -1 : dugKind.ordinal());

        if (dugKind == null) {
            changed |= steer(player, world, block, theirs, ledger);
        }
        if (changed || ledger.trail().size() % 50 == 0) {
            judge(player, ledger);
        }
    }

    private boolean steer(Player player, World world, Block block, SeenOres theirs, MiningLedger ledger) {
        DigPath path = paths.computeIfAbsent(player.getUniqueId(), ignored -> new DigPath());
        var bend = path.dug(block.getX(), block.getY(), block.getZ());
        if (bend.isEmpty()) {
            return false;
        }
        int[] at = bend.get().at();
        if (!owned(world, at)) {
            return false;
        }
        UUID id = player.getUniqueId();
        UUID worldId = world.getUID();
        BlockGrid seenByThem = new BlockGrid() {
            private final WorldGrid real = new WorldGrid(world);

            @Override
            public Material at(int x, int y, int z) {
                Material bait = honeypots.fakeAt(id, worldId, x, y, z);
                return bait != null ? bait : real.at(x, y, z);
            }

            @Override
            public boolean open(int x, int y, int z) {
                return real.open(x, y, z);
            }
        };
        HiddenOreRule.Census census = search.around(seenByThem, at, CENSUS_RADIUS,
                type -> OreKind.of(type).map(watched::contains).orElse(false), OreKind::isHostRock);
        SteeringRule.Pair pair = steering.judgeCensus(bend.get().before(), bend.get().after(), at, census.ores(), census.rocks());
        if (pair == SteeringRule.Pair.NO_DIFFERENCE) {
            return false;
        }
        ledger.turn(pair == SteeringRule.Pair.ORE_ONLY);
        return true;
    }

    /** Folia: the census reads around the bend, and every chunk it reads must be this thread's. */
    private static boolean owned(World world, int[] at) {
        int reach = CENSUS_RADIUS + HiddenOreRule.DEPTH;
        return Bukkit.isOwnedByCurrentRegion(world, (at[0] - reach) >> 4, (at[2] - reach) >> 4)
                && Bukkit.isOwnedByCurrentRegion(world, (at[0] + reach) >> 4, (at[2] + reach) >> 4)
                && Bukkit.isOwnedByCurrentRegion(world, (at[0] - reach) >> 4, (at[2] + reach) >> 4)
                && Bukkit.isOwnedByCurrentRegion(world, (at[0] + reach) >> 4, (at[2] - reach) >> 4);
    }

    private void judge(Player player, MiningLedger ledger) {
        XrayVerdictRule.Verdict verdict = verdicts.judge(ledger, density.density(), watched);
        latest.put(player.getUniqueId(), verdict);
        if (verdict.score() < settings.xrayReportScore() || !between.tryUse(player.getUniqueId())) {
            return;
        }
        StringBuilder why = new StringBuilder(String.format(Locale.ROOT,
                "x-ray: mining this pattern honestly is about one in 10^%.0f", verdict.score()));
        for (XrayVerdictRule.Signal signal : verdict.signals()) {
            if (signal.score() >= 2) {
                why.append("; ").append(signal.summary());
            }
        }
        reports.file(null, null, player.getUniqueId(), player.getName(), why.toString());
    }

    /** The current verdict for anybody with a ledger, worked out afresh. */
    public XrayVerdictRule.Verdict verdictFor(UUID player) {
        MiningLedger ledger = ledgers.find(player);
        if (ledger == null) {
            return XrayVerdictRule.Verdict.NOTHING;
        }
        ledger.decay(clock.getAsLong(), settings.xrayHalfLifeDays() * 86_400_000.0);
        XrayVerdictRule.Verdict verdict = verdicts.judge(ledger, density.density(), watched);
        latest.put(player, verdict);
        return verdict;
    }

    public Set<UUID> everybody() {
        return ledgers.everybody();
    }

    /** Out of a hundred, how sure the evidence is — for ranking and a quick read, never a verdict on its own. */
    public int probabilityFor(UUID player) {
        return player == null ? 0 : verdictFor(player).percent();
    }

    /** One thing their digging turned up: a new ore vein (its kind), or a bait ore (kind null). */
    public record Find(String world, int x, int y, int z, OreKind kind) {
    }

    /** Veins revealed and baits reached, newest first. */
    public java.util.List<Find> findsFor(UUID player) {
        MiningLedger ledger = ledgers.find(player);
        if (ledger == null) {
            return java.util.List.of();
        }
        java.util.List<Find> finds = new java.util.ArrayList<>();
        String world = ledger.trailWorld();
        OreKind[] kinds = OreKind.values();
        for (int[] step : ledger.trail()) {
            if (step[3] == -2) {
                finds.add(new Find(world, step[0], step[1], step[2], null));
            } else if (step[3] >= 0 && step[3] < kinds.length) {
                finds.add(new Find(world, step[0], step[1], step[2], kinds[step[3]]));
            }
        }
        java.util.Collections.reverse(finds);
        return finds;
    }

    public String nameOf(UUID player) {
        return ledgers.nameOf(player);
    }

    public MiningLedger ledgerOf(UUID player) {
        return ledgers.find(player);
    }

    public OreDensity density() {
        return density.density();
    }

    public int baitsAround(UUID player) {
        return honeypots.count(player);
    }

    public void forget(UUID player) {
        seen.remove(player);
        paths.remove(player);
        latest.remove(player);
        honeypots.forget(player);
    }

    public void load() {
        ledgers.load();
        density.load();
    }

    public boolean flush() {
        boolean ledgersOk = ledgers.flush();
        boolean densityOk = density.flush();
        return ledgersOk && densityOk;
    }

    @Override
    public void settings(ModerationSettings fresh) {
        this.settings = fresh == null ? ModerationSettings.DEFAULTS : fresh;
        between.every(Duration.ofSeconds(Math.max(60, this.settings.xrayCooldownSeconds())));
        Set<OreKind> kinds = EnumSet.noneOf(OreKind.class);
        for (String name : this.settings.xrayOres()) {
            OreKind.of(name).ifPresent(kinds::add);
        }
        this.watched = kinds.isEmpty() ? EnumSet.of(OreKind.DIAMOND) : kinds;
    }

    @Override
    public String describe() {
        return "judging mining against what an honest miner could know";
    }
}
