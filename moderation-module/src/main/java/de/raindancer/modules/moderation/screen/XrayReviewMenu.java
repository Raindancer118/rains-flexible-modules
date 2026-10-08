package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.OreKind;
import de.raindancer.modules.moderation.service.XrayEvidenceService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One player's x-ray evidence: the verdict and each test behind it, every ore vein their digging
 * revealed and every bait ore they reached, newest first — each one a click away.
 */
public final class XrayReviewMenu extends ModerationList<XrayEvidenceService.Find> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String subjectName;
    private final UUID subject;
    private final List<XrayEvidenceService.Find> finds;

    public XrayReviewMenu(ModerationServices services, Player viewer, Menu parent, UUID subject,
                          String subjectName) {
        super(services, viewer, parent);
        this.subjectName = subjectName == null || subjectName.isBlank() ? "somebody" : subjectName;
        this.subject = subject;
        // Read once on open, so the list does not reshuffle while a moderator pages through it.
        this.finds = new ArrayList<>(services.xrayDetection().findsFor(subject));
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Mining evidence — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return "Mining evidence";
    }

    @Override
    protected List<XrayEvidenceService.Find> entries() {
        return finds;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing found yet",
                "<gray>" + subjectName + " has not revealed a watched ore vein or reached a bait ore lately.");
    }

    @Override
    protected ItemStack icon(XrayEvidenceService.Find find) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + find.world() + " " + find.x() + ", " + find.y() + ", " + find.z());
        lore.add("");
        if (find.kind() == null) {
            lore.add("<red>A bait ore — fake, sealed in rock, visible only to an x-ray.");
            lore.add("<dark_gray>Their digging reached it.");
        } else {
            lore.add("<gray>A new vein their digging uncovered.");
        }
        lore.add("");
        lore.add("<dark_gray>Click to fly there and look for yourself.");
        Material icon = find.kind() == null ? Material.REDSTONE_BLOCK : materialOf(find.kind());
        String name = find.kind() == null ? "<red>Bait ore reached" : "<yellow>" + find.kind().title();
        return Icons.of(icon, name, lore);
    }

    @Override
    protected void onClick(XrayEvidenceService.Find find, InventoryClickEvent event) {
        World world = Bukkit.getWorld(find.world());
        if (world == null) {
            tell("moderation.xray.world-missing", "world", find.world());
            return;
        }
        viewer.closeInventory();
        // One block above the spot: the ore is gone or still rock, and landing inside it suffocates.
        viewer.teleportAsync(new Location(world, find.x() + 0.5, find.y() + 1, find.z() + 0.5));
    }

    private static Material materialOf(OreKind kind) {
        return switch (kind) {
            case DIAMOND -> Material.DIAMOND_ORE;
            case ANCIENT_DEBRIS -> Material.ANCIENT_DEBRIS;
            case EMERALD -> Material.EMERALD_ORE;
            case GOLD -> Material.GOLD_ORE;
            case NETHER_GOLD -> Material.NETHER_GOLD_ORE;
            case IRON -> Material.IRON_ORE;
            case COPPER -> Material.COPPER_ORE;
            case LAPIS -> Material.LAPIS_ORE;
            case REDSTONE -> Material.REDSTONE_ORE;
            case COAL -> Material.COAL_ORE;
            case QUARTZ -> Material.NETHER_QUARTZ_ORE;
        };
    }

    @Override
    protected void render() {
        super.render();
        var evidence = services().xrayDetection();
        var verdict = evidence.verdictFor(subject);
        List<String> lore = new ArrayList<>();
        String colour = verdict.score() >= 6 ? "<red>" : verdict.score() >= 3 ? "<yellow>" : "<green>";
        lore.add("<gray>Honest mining like this: about one in " + colour + "10^"
                + String.format(Locale.ROOT, "%.1f", verdict.score()));
        for (var signal : verdict.signals()) {
            lore.add("<dark_gray> · <white>" + signal.name() + "<gray> 10^-" + String.format(Locale.ROOT, "%.1f", signal.score()));
            lore.add("<dark_gray>   " + MINI.escapeTags(signal.summary()));
        }
        if (verdict.signals().isEmpty()) {
            lore.add("<dark_gray>Not enough digging seen yet to say anything.");
        }
        lore.add("<dark_gray>Bait ores around them now: " + evidence.baitsAround(subject));
        toolbar(3, Icons.of(Material.SPYGLASS, "<yellow>The evidence", lore), click -> { });
        var ledger = evidence.ledgerOf(subject);
        toolbar(5, ledger != null, Icons.of(Material.ENDER_EYE, "<yellow>Show me their tunnels",
                        "<gray>Draws their recent digging around you for 30 seconds,",
                        "<gray>only you can see it: <white>grey</white> rock, <aqua>aqua</aqua> ore veins,",
                        "<red>red</red> bait ores only an x-ray shows.", "<dark_gray>Stand near where they mined."),
                "Nothing of theirs is recorded yet", click -> {
                    viewer.closeInventory();
                    int drawn = de.raindancer.modules.moderation.visual.XrayReplay.show(services().plugin(), viewer, ledger);
                    tell(drawn > 0 ? "moderation.xray.replay-shown" : "moderation.xray.replay-empty",
                            "player", subjectName, "count", drawn);
                });
    }

    @Override
    public String describe() {
        return "one player's x-ray evidence and every vein and bait behind it";
    }
}
