package de.raindancer.modules.invsnap.screen;

import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.invsnap.InvSnapServices;
import de.raindancer.modules.invsnap.model.Snapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One snapshot's whole inventory, drawn with the real items rather than icons standing in for them
 * — a durability bar, an enchantment, a written book's own pages are exactly what they would be if
 * this were restored. Entirely read-only: every slot is inert, so looking closely at what somebody
 * had never risks moving it.
 *
 * <h2>Compare mode</h2>
 * Opened either plain ({@code live} is {@code null}) or against a snapshot of what the target is
 * carrying right now, slot for slot in restore order. A slot whose encoded text does not match gets
 * one more line of lore saying so and what is there instead — the snapshot item itself is never
 * altered beyond that lore, so what this shows is always exactly what a restore would put there.
 */
public final class SnapshotDetailScreen extends Menu implements IInvSnapScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Main (36) then armour (4, boots→helmet) then off hand (1) — one flat list, in restore order. */
    private final List<String> slots;
    private final List<String> liveSlots;
    private final String targetName;
    private final Instant takenAt;

    public SnapshotDetailScreen(InvSnapServices services, Player viewer, Menu parent,
                                Snapshot snapshot, Snapshot live, String targetName) {
        super(viewer, services.brand(), parent);
        this.slots = flatten(snapshot);
        this.liveSlots = live == null ? null : flatten(live);
        this.targetName = targetName == null ? "them" : targetName;
        this.takenAt = snapshot.takenAt();
    }

    private static List<String> flatten(Snapshot snapshot) {
        List<String> flat = new ArrayList<>(41);
        flat.addAll(snapshot.mainInventory());
        flat.addAll(snapshot.armor());
        flat.add(snapshot.offHand());
        return flat;
    }

    @Override
    protected Component title() {
        String mode = liveSlots == null ? "Snapshot" : "Compare";
        return MINI.deserialize("<dark_gray>" + mode + " — " + targetName + " — " + STAMP.format(takenAt));
    }

    @Override
    public String breadcrumb() {
        return "This snapshot";
    }

    @Override
    protected void render() {
        for (int index = 0; index < 36; index++) {
            set(index, iconFor(index, mainLabel(index)));
        }
        // Boots→helmet is getArmorContents()'s own order, indices 36..39 here; laid out top to
        // bottom the way a player expects to see their own equipment, then off hand set apart.
        set(38, iconFor(39, "Helmet"));
        set(39, iconFor(38, "Chestplate"));
        set(40, iconFor(37, "Leggings"));
        set(41, iconFor(36, "Boots"));
        set(43, iconFor(40, "Off hand"));
    }

    private static String mainLabel(int index) {
        return index < 9 ? "Hotbar slot " + (index + 1) : "Inventory slot " + (index - 8);
    }

    private ItemStack iconFor(int index, String label) {
        String encoded = slots.get(index);
        ItemStack decoded = ItemText.decode(encoded);
        boolean empty = decoded == null;
        ItemStack shown = empty ? Icons.filler(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                : decoded.clone();

        ItemMeta meta = shown.getItemMeta();
        if (meta == null) {
            return shown;
        }
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(Icons.loreLine("<dark_gray>" + label));
        if (liveSlots != null) {
            lore.add(Icons.loreLine(liveNoteFor(index, encoded)));
        }
        meta.lore(lore);
        if (empty) {
            meta.displayName(Icons.name("<gray>" + label + " — empty"));
        }
        shown.setItemMeta(meta);
        return shown;
    }

    private String liveNoteFor(int index, String encoded) {
        String liveEncoded = liveSlots.get(index);
        if (Objects.equals(nullToEmpty(encoded), nullToEmpty(liveEncoded))) {
            return "<green>Matches what " + targetName + " carries now";
        }
        ItemStack live = ItemText.decode(liveEncoded);
        String description = live == null ? "empty" : describe(live);
        return "<red>Different now — " + targetName + " carries: " + description;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String describe(ItemStack item) {
        String name = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return item.getAmount() + "x " + name;
    }

    @Override
    protected List<String> helpLines() {
        if (liveSlots == null) {
            return List.of("Exactly what " + targetName + " was carrying",
                    "at this moment. Nothing here can be changed.");
        }
        return List.of("Every slot, compared to what " + targetName + " is",
                "carrying right now. Green lore means unchanged;",
                "red lore means different, and says what is there now.");
    }

    @Override
    public String describe() {
        return liveSlots == null ? "one snapshot's full inventory, with the real items, read-only"
                : "one snapshot's inventory compared slot by slot to the live one";
    }
}
