package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ApproachReading;
import de.raindancer.modules.moderation.model.MinedBlock;
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
 * What a player's mining actually looked like, block by block — the thing worth reading before
 * touching the ban button on an x-ray report.
 *
 * <h2>Why this exists next to the ratio the report already gives</h2>
 * "37 of the last 200 blocks mined were valuable ore" is a fact about how much somebody found, and
 * says nothing about how. A diamond somebody stumbled into at the end of an ordinary branch tunnel and
 * a diamond somebody walked straight at through solid stone both raise that ratio by the same amount
 * — only the shape of the path leading to each one tells those two apart, and that shape is exactly
 * what {@code MiningTrail} keeps and this page shows.
 *
 * <h2>What "directness" means here, and what it does not prove</h2>
 * Each entry is one ore block, with how straight a line the digging immediately before it took — high
 * for somebody who covered nearly the whole distance in a straight line, low for an ordinary winding
 * tunnel that happened to break through beside one. It is a pattern, not a confession: a short natural
 * cave, a lucky vein followed along its own straight seam, or simply too little context remembered yet
 * can all read high without anybody having cheated. It is offered as one more thing worth flying out
 * to look at with your own eyes, exactly as the class doc for {@code MiningTrail} says — never as an
 * answer by itself.
 */
public final class XrayReviewMenu extends ModerationList<ApproachReading> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String subjectName;
    private final List<ApproachReading> readings;

    public XrayReviewMenu(ModerationServices services, Player viewer, Menu parent, UUID subject,
                          String subjectName) {
        super(services, viewer, parent);
        this.subjectName = subjectName == null || subjectName.isBlank() ? "somebody" : subjectName;
        // Read once, on open, rather than on every render: a moderator paging through a long list
        // should see the same order all the way through, not one that reshuffles under them because
        // the subject mined another block while the page was open.
        this.readings = new ArrayList<>(services.xrayDetection().approachesFor(subject));
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Mining history — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return "Mining history";
    }

    @Override
    protected List<ApproachReading> entries() {
        return readings;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing remembered yet",
                "<gray>" + subjectName + " has not mined any watched ore recently — or has not "
                        + "mined at all since the server last restarted, which forgets every trail.");
    }

    @Override
    protected ItemStack icon(ApproachReading reading) {
        MinedBlock ore = reading.ore();
        int directness = reading.directnessPercent();
        // Inlined rather than pulled out into a method of their own: WordingTest holds every module
        // to never returning a literal String of markup from a helper, precisely because a value
        // that looks like ordinary text at its call site is how markup ends up somewhere it is
        // escaped instead of rendered. Kept as local variables inside the one method that actually
        // builds the icon, so the colour and the words it belongs to are never handed anywhere else.
        String colour = directness >= 80 ? "<red>" : directness >= 50 ? "<yellow>" : "<green>";
        String judgement = directness >= 80 ? "<red>Went almost straight at it."
                : directness >= 50 ? "<yellow>Somewhat direct — worth a look."
                : "<green>Reads like ordinary winding digging.";

        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + ore.world() + " " + ore.x() + ", " + ore.y() + ", " + ore.z());
        lore.add("");
        lore.add("<gray>Directness: " + colour + directness + "%");
        lore.add("<dark_gray>" + String.format(Locale.ROOT, "%.1f", reading.straightLineDistance())
                + " blocks in a straight line, over " + reading.pathLength()
                + " mined getting there.");
        lore.add("");
        lore.add(judgement);
        lore.add("");
        lore.add("<dark_gray>Click to fly there and look for yourself.");

        return Icons.of(materialOf(ore), "<yellow>" + prettyName(ore.material()), lore);
    }

    @Override
    protected void onClick(ApproachReading reading, InventoryClickEvent event) {
        MinedBlock ore = reading.ore();
        World world = Bukkit.getWorld(ore.world());
        if (world == null) {
            tell("moderation.xray.world-missing", "world", ore.world());
            return;
        }
        viewer.closeInventory();
        // The block's own centre, one step above it — landing inside solid stone, which mining leaves
        // exactly where the ore itself was, is the one thing this button must never do.
        viewer.teleportAsync(new Location(world, ore.x() + 0.5, ore.y() + 1, ore.z() + 0.5));
    }

    private static Material materialOf(MinedBlock ore) {
        Material found = Material.matchMaterial(ore.material());
        return found == null ? Material.DIAMOND_ORE : found;
    }

    private static String prettyName(String materialName) {
        Material found = Material.matchMaterial(materialName);
        String rawName = found == null ? materialName : found.name();
        String words = rawName.replace('_', ' ').toLowerCase(Locale.ROOT);
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    @Override
    public String describe() {
        return "one player's recent mining, block by block, sorted most direct approach first";
    }
}
