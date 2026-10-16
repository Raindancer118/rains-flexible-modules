package de.raindancer.modules.oneblock.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.oneblock.OneBlockSettings;
import de.raindancer.modules.oneblock.model.BlockSpot;
import de.raindancer.modules.oneblock.model.Content;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandBlock;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Upgrade;
import de.raindancer.modules.oneblock.rules.GeneratorRule;
import de.raindancer.modules.oneblock.rules.ShardRule;
import de.raindancer.modules.oneblock.store.IslandBook;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The one block itself: a break counts, may earn the island shards, and the block comes back as whatever the roll
 * says a tick later — a mob on top when a mob upgrade hits. Auto-smelt turns drops into what a furnace would make;
 * auto-break breaks the block by itself while somebody of the team is online. Server thread.
 */
public final class OneBlockService implements IOneBlockService {

    /** Where a break happened: the island, the dimension, the block. */
    public record Target(Island island, IslandDimension dimension, IslandBlock block) {
    }

    private static final String BAR_OWNER = "oneblock";
    private static final Duration BAR_FOR = Duration.ofSeconds(3);
    private static final double MOB_RADIUS = 16;
    /** What auto-break breaks with: the best of everything, so every block drops what it can. */
    private static final ItemStack BEST_TOOL = new ItemStack(Material.NETHERITE_PICKAXE);

    private final Plugin plugin;
    private final Server server;
    private final IslandBook book;
    private final Supplier<Content> content;
    private final Messages messages;
    private final ActionBars bars;
    private final GeneratorRule generator = new GeneratorRule();
    private final ShardRule shards = new ShardRule();
    private volatile Map<Material, ItemStack> furnace;

    public OneBlockService(Plugin plugin, Server server, IslandBook book, Supplier<Content> content, Messages messages,
                           ActionBars bars) {
        this.plugin = plugin;
        this.server = server;
        this.book = book;
        this.content = content;
        this.messages = messages;
        this.bars = bars;
    }

    /** Nothing about the block is in the settings: it is all {@code content.yml}. */
    @Override
    public void settings(OneBlockSettings settings) {
    }

    /** The island, dimension and block this block is the one block of; empty for every other block. */
    public Optional<Target> targetOf(Block block) {
        String world = block.getWorld().getName();
        Optional<Island> island = book.ofWorld(world);
        Optional<IslandDimension> dimension = Island.dimensionOf(world);
        if (island.isEmpty() || dimension.isEmpty()) {
            return Optional.empty();
        }
        return island.get().block(dimension.get())
                .filter(one -> one.spot().is(block.getX(), block.getY(), block.getZ()))
                .map(one -> new Target(island.get(), dimension.get(), one));
    }

    public boolean isOneBlock(Block block) {
        return targetOf(block).isPresent();
    }

    /**
     * The block was broken — by {@code breaker}, or by itself when null. Counts it, pays the island and brings the
     * block back next tick.
     */
    public void broken(Target target, Player breaker, Location at) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Content now = content.get();
        GeneratorRule.Outcome outcome = generator.next(now, target.dimension(), target.block(),
                random.nextDouble(100.0), random.nextDouble());
        long[] earned = {0};
        IslandBook.Changed changed = book.change(target.island().id(), island -> {
            Island counted = island.broken(target.dimension());
            earned[0] = shards.earnedAt(now, counted.totalBroken());
            return Optional.of(counted.withShardsAdded(earned[0]));
        });
        Location block = at.clone();
        Scheduling.regionLater(plugin, block, 1L, () -> regrow(block, outcome, now.mobCap()));
        if (earned[0] > 0 && breaker != null) {
            long total = changed.island().map(Island::shards).orElse(0L);
            bars.show(breaker.getUniqueId(), BAR_OWNER, messages.get("oneblock.shards.earned",
                    "amount", String.valueOf(earned[0]), "shards", String.format("%,d", total)), BAR_FOR,
                    ActionBarPriority.NORMAL);
        }
    }

    private void regrow(Location at, GeneratorRule.Outcome outcome, int mobCap) {
        Block block = at.getBlock();
        // No physics: sand or gravel at the spot would otherwise fall into the void on the next neighbour update.
        block.setType(outcome.material(), false);
        if (outcome.mob() == null) {
            return;
        }
        World world = at.getWorld();
        long near = world.getNearbyEntities(at, MOB_RADIUS, MOB_RADIUS, MOB_RADIUS,
                entity -> entity.getType() == outcome.mob()).size();
        // A passive mob is never cleaned up by anything, so an island with a cow upgrade would fill with cows until the
        // server crawls; past the cap the roll is simply spent.
        if (near < mobCap) {
            world.spawnEntity(at.clone().add(0.5, 1.0, 0.5), outcome.mob());
        }
    }

    /** Auto-smelt: each drop turns into what a furnace makes of it, at the island's chance. */
    public void smelt(Target target, Collection<ItemStack> drops) {
        double chance = generator.specialChance(content.get(), target.dimension(), target.block(), Upgrade.Kind.SMELT);
        if (chance <= 0 || drops.isEmpty()) {
            return;
        }
        Map<Material, ItemStack> recipes = furnace();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<ItemStack> changed = new ArrayList<>();
        Iterator<ItemStack> each = drops.iterator();
        while (each.hasNext()) {
            ItemStack drop = each.next();
            ItemStack result = recipes.get(drop.getType());
            if (result != null && random.nextDouble(100.0) < chance) {
                each.remove();
                ItemStack smelted = result.clone();
                smelted.setAmount(Math.min(smelted.getMaxStackSize(), smelted.getAmount() * drop.getAmount()));
                changed.add(smelted);
            }
        }
        drops.addAll(changed);
    }

    /** Furnace recipes, input to result. Built the first time somebody smelts, after every plugin added its own. */
    private Map<Material, ItemStack> furnace() {
        Map<Material, ItemStack> known = furnace;
        if (known != null) {
            return known;
        }
        Map<Material, ItemStack> built = new HashMap<>();
        Iterator<Recipe> recipes = server.recipeIterator();
        while (recipes.hasNext()) {
            if (recipes.next() instanceof org.bukkit.inventory.FurnaceRecipe cooking) {
                add(built, cooking);
            }
        }
        furnace = Map.copyOf(built);
        return furnace;
    }

    private static void add(Map<Material, ItemStack> into, CookingRecipe<?> recipe) {
        ItemStack input = recipe.getInput();
        if (input.getType() != Material.AIR) {
            into.putIfAbsent(input.getType(), recipe.getResult());
        }
    }

    /**
     * Auto-break, once a second: every loaded block with the upgrade breaks itself at its chance, while somebody of its
     * team is online on this server.
     */
    public void autoBreak() {
        Content now = content.get();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Island island : book.all()) {
            if (!teamOnline(island)) {
                continue;
            }
            for (Map.Entry<IslandDimension, IslandBlock> entry : island.blocks().entrySet()) {
                double chance = generator.specialChance(now, entry.getKey(), entry.getValue(), Upgrade.Kind.AUTO_BREAK);
                World world = server.getWorld(island.worldName(entry.getKey()));
                if (chance <= 0 || world == null || random.nextDouble(100.0) >= chance) {
                    continue;
                }
                BlockSpot spot = entry.getValue().spot();
                Block block = world.getBlockAt(spot.x(), spot.y(), spot.z());
                if (block.getType().isAir() || !world.isChunkLoaded(spot.x() >> 4, spot.z() >> 4)) {
                    continue;
                }
                Target target = new Target(island, entry.getKey(), entry.getValue());
                List<ItemStack> drops = new ArrayList<>(block.getDrops(BEST_TOOL));
                smelt(target, drops);
                block.setType(Material.AIR, false);
                Location top = block.getLocation().add(0.5, 1.05, 0.5);
                drops.forEach(drop -> world.dropItem(top, drop, item -> item.setVelocity(item.getVelocity().zero())));
                broken(target, null, block.getLocation());
            }
        }
    }

    /** Whether the island's block may be working: somebody of its team is online here. */
    public boolean working(Island island) {
        Content now = content.get();
        return teamOnline(island) && island.blocks().entrySet().stream().anyMatch(entry ->
                generator.specialChance(now, entry.getKey(), entry.getValue(), Upgrade.Kind.AUTO_BREAK) > 0);
    }

    private boolean teamOnline(Island island) {
        for (UUID member : island.everyone()) {
            if (server.getPlayer(member) != null) {
                return true;
            }
        }
        return false;
    }

}
