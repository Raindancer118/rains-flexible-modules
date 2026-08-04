package de.raindancer.modules.names.service;

import de.raindancer.modules.names.NamesSettings;
import de.raindancer.modules.names.model.NameStyle;
import de.raindancer.modules.names.store.Palette;
import de.raindancer.modules.names.store.StyleTags;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.function.Supplier;

/**
 * Washing a styled name tag clean in a water cauldron.
 *
 * <h2>Why a cauldron and not a recipe</h2>
 * Because it is where a player already looks. Undyeing leather armour and clearing a banner are both
 * done by putting the thing in a cauldron, so a dyed name tag going in there is a guess a player makes
 * rather than a rule they have to be taught. A crafting recipe for it would have needed an ingredient,
 * which is a fee for changing your mind.
 *
 * <p>The whole stack in hand is washed at once. Every tag in a stack necessarily carries the same style
 * — that is what let them stack — so washing one and leaving the rest would split the stack for no
 * reason a player could see.
 */
public final class WashService implements INamesService {

    private final Supplier<Palette> palette;
    private volatile NamesSettings settings;

    public WashService(Supplier<Palette> palette, NamesSettings settings) {
        this.palette = palette;
        this.settings = settings;
    }

    @Override
    public void settings(NamesSettings fresh) {
        this.settings = fresh;
    }

    /** Whether the server washes tags at all. */
    public boolean enabled() {
        return settings.washInCauldron();
    }

    /**
     * Whether this is a wash: the right block, the right item, and a style to take off it.
     *
     * <p>Separate from {@link #wash} so the listener can decide whether to cancel the interaction
     * before anything has happened — an ordinary name tag must still fill and empty cauldrons the way
     * it always has.
     */
    public boolean washes(Block block, ItemStack held) {
        return enabled()
                && block != null && block.getType() == Material.WATER_CAULDRON
                && held != null && held.getType() == Material.NAME_TAG
                && !StyleTags.read(held).isEmpty();
    }

    /**
     * Strips the stack back to plain name tags and takes a level of water for it.
     *
     * <p>The caller has already cancelled the interaction; this is only reached once {@link #washes}
     * has said yes.
     */
    public void wash(Player player, EquipmentSlot hand, Block cauldron, ItemStack held) {
        ItemStack washed = StyleTags.styled(held, NameStyle.NONE, palette.get());
        washed.setAmount(held.getAmount());
        player.getInventory().setItem(hand, washed);
        lower(cauldron);
        player.playSound(cauldron.getLocation(), Sound.ITEM_BUCKET_EMPTY, 0.6f, 1.4f);
    }

    /**
     * Takes one level of water out, the way dyeing and undyeing do.
     *
     * <p>A cauldron holding its last level becomes an empty cauldron rather than a water cauldron at
     * level zero — that state does not exist, and setting it produces an invisible block of water.
     */
    private static void lower(Block block) {
        if (!(block.getBlockData() instanceof Levelled water)) {
            return;
        }
        if (water.getLevel() <= 1) {
            block.setType(Material.CAULDRON);
            return;
        }
        water.setLevel(water.getLevel() - 1);
        block.setBlockData(water);
    }

    @Override
    public String describe() {
        return "washes a styled name tag clean in a water cauldron";
    }
}
