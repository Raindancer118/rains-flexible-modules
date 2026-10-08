package de.raindancer.modules.veintoggle.service;

import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.veintoggle.model.BlockKey;
import de.raindancer.modules.veintoggle.model.BrokenBlock;
import de.raindancer.modules.veintoggle.model.Settlement;
import de.raindancer.modules.veintoggle.model.Settlement.Source;
import de.raindancer.modules.veintoggle.model.Settlement.Taking;
import de.raindancer.modules.veintoggle.model.VeinOperation;
import de.raindancer.modules.veintoggle.rules.UndoRule;
import de.raindancer.modules.veintoggle.store.RestoredBlocks;
import de.raindancer.modules.veintoggle.store.VeinHistory;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.SoundGroup;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Puts a vein back, and makes sure everything it dropped leaves the world again — otherwise undo hands
 * out a second set of diamonds. Who pays, in this order:
 *
 * <ol>
 *   <li>the vein's own items still lying around it — never anybody else's;</li>
 *   <li>the undoer's inventory;</li>
 *   <li>everybody who picked some of it up: out of their inventory while they are online;</li>
 *   <li>then, for what they no longer have (or while they are offline), out of their account at the
 *       shop's buy price — as far as it reaches;</li>
 *   <li>and whatever is still missing, the undoer — only once they agree to the bill.</li>
 * </ol>
 *
 * Then each block whose drops are all covered goes back, and anything taken that a block that stayed
 * mined would have needed is given back — newest taking first, so the undoer's own money before the
 * collectors'. Money taken is not paid to anybody: the ore is back in the ground.
 *
 * <p>The steps run on different threads — the vein's region, each collector's own — through
 * {@link Threads}, one after another, never two at once for the same undo.
 */
public final class VeinUndoService {

    /** How far around the vein dropped items are looked for — Veinminer drops at the block or at the source. */
    private static final double GROUND_MARGIN = 4.0;

    public record Outcome(int restored, int inTheWay, int unpaid) {
    }

    /** Where each step runs. Folia: the vein's region, each player's own thread. */
    public interface Threads {
        void region(World world, BlockKey at, Runnable task);

        /** Runs on {@code player}'s thread, or {@code gone} when they left before it could. */
        void player(Player player, Runnable task, Runnable gone);

        void later(long ticks, Runnable task);
    }

    /** What players are told. Amounts come already formatted. */
    public interface Notices {
        void outcome(Player undoer, Outcome outcome);

        void tooFar(Player undoer);

        void bill(Player undoer, String amount, int items, long seconds);

        void cannotPayAll(Player undoer, String paid, String wanted);

        void noBill(Player undoer);

        void busy(Player undoer);

        void tookBack(Player collector, String undoer, int items);

        void charged(Player collector, String undoer, String amount, int items);
    }

    private final Server server;
    private final UndoRule rule;
    private final VeinHistory history;
    private final RestoredBlocks restored;
    private final BiPredicate<Player, Location> mayBuild;
    private final Threads threads;
    private final Supplier<Optional<Economy>> economy;
    private final Function<ItemStack, Optional<Money>> price;
    private final Notices notices;
    private final long billSeconds;
    private final Map<UUID, Job> running = new ConcurrentHashMap<>();
    private final Map<UUID, Job> billed = new ConcurrentHashMap<>();

    /**
     * @param mayBuild    whether the player may place a block there now — Core's land answer
     * @param price       what one item is worth — Core's ItemValues
     * @param billSeconds how long the undoer has to agree to pay
     */
    public VeinUndoService(Server server, UndoRule rule, VeinHistory history, RestoredBlocks restored,
                           BiPredicate<Player, Location> mayBuild, Threads threads,
                           Supplier<Optional<Economy>> economy, Function<ItemStack, Optional<Money>> price,
                           Notices notices, long billSeconds) {
        this.server = server;
        this.rule = rule;
        this.history = history;
        this.restored = restored;
        this.mayBuild = mayBuild;
        this.threads = threads;
        this.economy = economy;
        this.price = price;
        this.notices = notices;
        this.billSeconds = billSeconds;
    }

    /** One undo, from the first item taken to the last given back. */
    private final class Job {
        final Player undoer;
        final VeinOperation vein;
        final World world;
        final List<ItemStack> kinds = new ArrayList<>();
        final Map<BrokenBlock, Map<Integer, Integer>> needs = new IdentityHashMap<>();
        final Map<Integer, Integer> remaining = new LinkedHashMap<>();
        final Map<Integer, Optional<Money>> prices = new HashMap<>();
        final Settlement<Integer> settlement = new Settlement<>();

        Job(Player undoer, VeinOperation vein, World world) {
            this.undoer = undoer;
            this.vein = vein;
            this.world = world;
        }

        int kindOf(ItemStack stack) {
            for (int kind = 0; kind < kinds.size(); kind++) {
                if (kinds.get(kind).isSimilar(stack)) {
                    return kind;
                }
            }
            ItemStack one = stack.clone();
            one.setAmount(1);
            kinds.add(one);
            return kinds.size() - 1;
        }

        /** Finds a kind without adding one. */
        Optional<Integer> knownKind(ItemStack stack) {
            for (int kind = 0; kind < kinds.size(); kind++) {
                if (kinds.get(kind).isSimilar(stack)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }

        Optional<Money> priceOf(int kind) {
            return prices.computeIfAbsent(kind, k -> price.apply(kinds.get(k)));
        }

        synchronized void took(Source source, UUID from, int kind, int units, Money unitPrice) {
            if (units <= 0) {
                return;
            }
            settlement.add(new Taking<>(source, from, kind, units, unitPrice));
            remaining.merge(kind, -units, Integer::sum);
            remaining.values().removeIf(left -> left <= 0);
        }

        synchronized int stillNeeded(int kind) {
            return remaining.getOrDefault(kind, 0);
        }

        synchronized Map<Integer, Integer> stillNeeded() {
            return new LinkedHashMap<>(remaining);
        }
    }

    /** Starts undoing {@code vein} for {@code undoer}, whose vein it is. */
    public void start(Player undoer, VeinOperation vein) {
        World world = server.getWorld(vein.source().world());
        if (world == null) {
            notices.tooFar(undoer);
            return;
        }
        Job job = new Job(undoer, vein, world);
        if (billed.containsKey(undoer.getUniqueId()) || running.putIfAbsent(undoer.getUniqueId(), job) != null) {
            notices.busy(undoer);
            return;
        }
        threads.region(world, vein.source(), guarded(job, () -> gather(job)));
    }

    /** A step that fails lets go of the undo, so the undoer is not told "busy" for ever after. */
    private Runnable guarded(Job job, Runnable step) {
        return () -> {
            try {
                step.run();
            } catch (RuntimeException failed) {
                running.remove(job.undoer.getUniqueId(), job);
                billed.remove(job.undoer.getUniqueId(), job);
                throw failed;
            }
        };
    }

    /** The undoer agrees to pay what nobody else could. @return whether there was a bill */
    public boolean pay(Player undoer) {
        Job job = billed.remove(undoer.getUniqueId());
        if (job == null) {
            notices.noBill(undoer);
            return false;
        }
        Optional<Economy> money = economy.get();
        Map<Integer, Integer> wanted = job.stillNeeded();
        if (money.isPresent()) {
            Money full = rule.cost(wanted, job::priceOf);
            Map<Integer, Integer> units = rule.affordable(wanted, job::priceOf, money.get().balance(undoer.getUniqueId()));
            Money paid = rule.cost(units, job::priceOf);
            if (paid.isPositive() && money.get().withdraw(undoer.getUniqueId(), paid, "Undid a vein").succeeded()) {
                units.forEach((kind, count) -> job.took(Source.UNDOER_MONEY, undoer.getUniqueId(), kind, count,
                        job.priceOf(kind).orElseThrow()));
            } else {
                paid = Money.ZERO;
            }
            if (paid.compareTo(full) < 0) {
                notices.cannotPayAll(undoer, money.get().format(paid), money.get().format(full));
            }
        }
        threads.region(job.world, job.vein.source(), guarded(job, () -> settle(job)));
        return true;
    }

    /** The undoer declines the bill: only what is covered goes back. @return whether there was a bill */
    public boolean decline(Player undoer) {
        Job job = billed.remove(undoer.getUniqueId());
        if (job == null) {
            notices.noBill(undoer);
            return false;
        }
        threads.region(job.world, job.vein.source(), guarded(job, () -> settle(job)));
        return true;
    }

    /** Step one, on the vein's region: the ground and the undoer's own inventory. */
    private void gather(Job job) {
        Player undoer = job.undoer;
        if (!undoer.isOnline() || !server.isOwnedByCurrentRegion(undoer)) {
            running.remove(undoer.getUniqueId(), job);
            notices.tooFar(undoer);
            return;
        }
        List<BrokenBlock> blocks = job.vein.blocks();
        for (BrokenBlock block : blocks) {
            Map<Integer, Integer> need = new HashMap<>();
            for (ItemStack drop : block.drops()) {
                need.merge(job.kindOf(drop), drop.getAmount(), Integer::sum);
            }
            job.needs.put(block, need);
            if (free(undoer, job.world, block.at())) {
                need.forEach((kind, amount) -> job.remaining.merge(kind, amount, Integer::sum));
            }
        }

        for (Item item : groundAround(job.world, job.vein, blocks)) {
            ItemStack lying = item.getItemStack();
            Optional<Integer> kind = job.knownKind(lying);
            if (kind.isEmpty()) {
                continue;
            }
            int ours = blocks.stream().mapToInt(block -> block.lyingIn(item.getUniqueId())).sum();
            int taking = Math.min(Math.min(ours, lying.getAmount()), job.stillNeeded(kind.get()));
            if (taking <= 0) {
                continue;
            }
            int left = taking;
            for (BrokenBlock block : blocks) {
                left -= block.takeFrom(item.getUniqueId(), left);
            }
            if (taking == lying.getAmount()) {
                item.remove();
            } else {
                lying.setAmount(lying.getAmount() - taking);
                item.setItemStack(lying);
            }
            job.took(Source.GROUND, null, kind.get(), taking, Money.ZERO);
        }

        takeFromInventory(job, undoer, Source.UNDOER_ITEMS, job.stillNeeded());

        Iterator<Map.Entry<UUID, List<ItemStack>>> collectors = job.vein.collected().entrySet().stream()
                .filter(entry -> !entry.getKey().equals(undoer.getUniqueId()))
                .toList().iterator();
        fromCollectors(job, collectors);
    }

    /** Step two, one collector after another, each on their own thread. */
    private void fromCollectors(Job job, Iterator<Map.Entry<UUID, List<ItemStack>>> collectors) {
        if (!collectors.hasNext() || job.stillNeeded().isEmpty()) {
            fromCollectorsAccounts(job);
            return;
        }
        Map.Entry<UUID, List<ItemStack>> next = collectors.next();
        Map<Integer, Integer> owed = owedBy(job, next.getValue());
        Player collector = server.getPlayer(next.getKey());
        if (owed.isEmpty() || collector == null) {
            fromCollectors(job, collectors);
            return;
        }
        threads.player(collector, guarded(job, () -> {
            takeFromInventory(job, collector, Source.COLLECTOR_ITEMS, owed);
            fromCollectors(job, collectors);
        }), guarded(job, () -> fromCollectors(job, collectors)));
    }

    /** What a collector still owes of each kind this undo needs, capped by what it still needs. */
    private Map<Integer, Integer> owedBy(Job job, List<ItemStack> picked) {
        Map<Integer, Integer> owed = new LinkedHashMap<>();
        for (ItemStack stack : picked) {
            job.knownKind(stack).ifPresent(kind -> {
                int amount = Math.min(stack.getAmount(), job.stillNeeded(kind));
                if (amount > 0) {
                    owed.merge(kind, amount, Integer::sum);
                }
            });
        }
        return owed;
    }

    /** Step three: what collectors no longer have, from their accounts. */
    private void fromCollectorsAccounts(Job job) {
        Optional<Economy> money = economy.get();
        if (money.isPresent()) {
            for (Map.Entry<UUID, List<ItemStack>> collector : job.vein.collected().entrySet()) {
                UUID who = collector.getKey();
                if (who.equals(job.undoer.getUniqueId())) {
                    continue;
                }
                // What they picked up, less what they already gave back in items, as far as still needed.
                Map<Integer, Integer> owed = new LinkedHashMap<>();
                for (ItemStack stack : collector.getValue()) {
                    job.knownKind(stack).ifPresent(kind -> owed.merge(kind, stack.getAmount(), Integer::sum));
                }
                for (Taking<Integer> given : job.settlement.takings()) {
                    if (given.source() == Source.COLLECTOR_ITEMS && who.equals(given.from())) {
                        owed.computeIfPresent(given.kind(), (kind, amount) -> amount - given.units());
                    }
                }
                owed.replaceAll((kind, amount) -> Math.min(amount, job.stillNeeded(kind)));
                owed.values().removeIf(amount -> amount <= 0);
                if (owed.isEmpty()) {
                    continue;
                }
                Map<Integer, Integer> units = rule.affordable(owed, job::priceOf, money.get().balance(who));
                Money total = rule.cost(units, job::priceOf);
                if (!total.isPositive()) {
                    continue;
                }
                EconomyResult result = money.get().withdraw(who, total, "Gave back items from a vein " + job.undoer.getName() + " undid");
                if (result.succeeded()) {
                    units.forEach((kind, count) -> job.took(Source.COLLECTOR_MONEY, who, kind, count,
                            job.priceOf(kind).orElseThrow()));
                }
            }
        }

        Map<Integer, Integer> missing = job.stillNeeded();
        Money bill = rule.cost(missing, job::priceOf);
        if (money.isPresent() && bill.isPositive()) {
            running.remove(job.undoer.getUniqueId(), job);
            billed.put(job.undoer.getUniqueId(), job);
            int items = missing.entrySet().stream().filter(entry -> job.priceOf(entry.getKey()).isPresent())
                    .mapToInt(Map.Entry::getValue).sum();
            notices.bill(job.undoer, money.get().format(bill), items, billSeconds);
            threads.later(billSeconds * 20L, () -> {
                if (billed.remove(job.undoer.getUniqueId(), job)) {
                    threads.region(job.world, job.vein.source(), guarded(job, () -> settle(job)));
                }
            });
            return;
        }
        threads.region(job.world, job.vein.source(), guarded(job, () -> settle(job)));
    }

    /** The last step, on the vein's region: put back what is paid for, give back what is not needed. */
    private void settle(Job job) {
        running.remove(job.undoer.getUniqueId(), job);
        List<BrokenBlock> blocks = job.vein.blocks();
        Map<Integer, Integer> supply = job.settlement.supply();
        UndoRule.Plan<BrokenBlock, Integer> plan = rule.plan(blocks,
                block -> free(job.undoer, job.world, block.at()),
                block -> job.needs.getOrDefault(block, Map.of()), supply);

        for (BrokenBlock block : plan.restore()) {
            BlockKey at = block.at();
            // No physics: a vein of gravel put back must stay where it was, not pour into the tunnel.
            job.world.getBlockAt(at.x(), at.y(), at.z()).setBlockData(block.data(), false);
            restored.mark(at, block.data(), block.drops());
        }
        if (!plan.restore().isEmpty()) {
            BrokenBlock first = plan.restore().getFirst();
            SoundGroup sounds = first.data().getSoundGroup();
            if (sounds != null) {
                job.world.playSound(first.at().centre(job.world), sounds.getPlaceSound(), 1.0f, 1.0f);
            }
        }

        List<Taking<Integer>> refunds = job.settlement.refunds(plan.toTake());
        refunds.forEach(refund -> giveBack(job, refund));
        tellCollectors(job, refunds);

        job.vein.removeAll(plan.restore());
        if (job.vein.isEmpty()) {
            history.remove(job.undoer.getUniqueId(), job.vein);
        }
        if (job.undoer.isOnline()) {
            notices.outcome(job.undoer, new Outcome(plan.restore().size(), plan.inTheWay().size(), plan.unpaid().size()));
        }
    }

    private void giveBack(Job job, Taking<Integer> refund) {
        ItemStack kind = job.kinds.get(refund.kind());
        switch (refund.source()) {
            case UNDOER_MONEY, COLLECTOR_MONEY -> economy.get().ifPresent(money ->
                    money.deposit(refund.from(), refund.total(), "Refund: a vein undo did not need it"));
            case GROUND -> drop(job.world, job.vein.source().centre(job.world), kind, refund.units());
            case UNDOER_ITEMS, COLLECTOR_ITEMS -> {
                Player owner = server.getPlayer(refund.from());
                Runnable elsewhere = () -> drop(job.world, job.vein.source().centre(job.world), kind, refund.units());
                if (owner == null) {
                    elsewhere.run();
                    return;
                }
                threads.player(owner, () -> {
                    ItemStack back = kind.clone();
                    back.setAmount(refund.units());
                    owner.getInventory().addItem(back).values()
                            .forEach(over -> owner.getWorld().dropItemNaturally(owner.getLocation(), over));
                }, elsewhere);
            }
        }
    }

    /** Settles each collector's debt by what was really kept from them, and tells them. */
    private void tellCollectors(Job job, List<Taking<Integer>> refunds) {
        Map<UUID, Map<Integer, Integer>> items = new LinkedHashMap<>();
        Map<UUID, Map<Integer, Integer>> paidFor = new LinkedHashMap<>();
        Map<UUID, Money> charged = new LinkedHashMap<>();
        for (Taking<Integer> taking : job.settlement.takings()) {
            if (taking.source() == Source.COLLECTOR_ITEMS) {
                items.computeIfAbsent(taking.from(), nobody -> new HashMap<>()).merge(taking.kind(), taking.units(), Integer::sum);
            } else if (taking.source() == Source.COLLECTOR_MONEY) {
                paidFor.computeIfAbsent(taking.from(), nobody -> new HashMap<>()).merge(taking.kind(), taking.units(), Integer::sum);
                charged.merge(taking.from(), taking.total(), Money::plus);
            }
        }
        for (Taking<Integer> refund : refunds) {
            if (refund.source() == Source.COLLECTOR_ITEMS) {
                items.get(refund.from()).merge(refund.kind(), -refund.units(), Integer::sum);
            } else if (refund.source() == Source.COLLECTOR_MONEY) {
                paidFor.get(refund.from()).merge(refund.kind(), -refund.units(), Integer::sum);
                charged.merge(refund.from(), refund.total().negate(), Money::plus);
            }
        }
        for (Map<UUID, Map<Integer, Integer>> kept : List.of(items, paidFor)) {
            kept.forEach((who, byKind) -> byKind.forEach((kind, units) -> {
                if (units > 0) {
                    job.vein.settled(who, job.kinds.get(kind), units);
                }
            }));
        }
        items.forEach((who, byKind) -> {
            int count = byKind.values().stream().mapToInt(Integer::intValue).sum();
            Player collector = server.getPlayer(who);
            if (count > 0 && collector != null) {
                notices.tookBack(collector, job.undoer.getName(), count);
            }
        });
        charged.forEach((who, amount) -> {
            int count = paidFor.get(who).values().stream().mapToInt(Integer::intValue).sum();
            Player collector = server.getPlayer(who);
            if (amount.isPositive() && collector != null) {
                economy.get().ifPresent(money -> notices.charged(collector, job.undoer.getName(), money.format(amount), count));
            }
        });
    }

    private void takeFromInventory(Job job, Player from, Source source, Map<Integer, Integer> wanted) {
        if (wanted.isEmpty()) {
            return;
        }
        ItemStack[] storage = from.getInventory().getStorageContents();
        boolean changed = false;
        for (Map.Entry<Integer, Integer> want : wanted.entrySet()) {
            int amount = Math.min(want.getValue(), job.stillNeeded(want.getKey()));
            ItemStack kind = job.kinds.get(want.getKey());
            int taken = 0;
            for (int slot = 0; slot < storage.length && taken < amount; slot++) {
                ItemStack held = storage[slot];
                if (held == null || !kind.isSimilar(held)) {
                    continue;
                }
                int now = Math.min(amount - taken, held.getAmount());
                taken += now;
                if (now == held.getAmount()) {
                    storage[slot] = null;
                } else {
                    held.setAmount(held.getAmount() - now);
                }
            }
            if (taken > 0) {
                changed = true;
                job.took(source, from.getUniqueId(), want.getKey(), taken, Money.ZERO);
            }
        }
        if (changed) {
            from.getInventory().setStorageContents(storage);
        }
    }

    private static void drop(World world, Location at, ItemStack kind, int units) {
        int max = Math.max(1, kind.getMaxStackSize());
        for (int left = units; left > 0; left -= max) {
            ItemStack stack = kind.clone();
            stack.setAmount(Math.min(left, max));
            world.dropItemNaturally(at, stack);
        }
    }

    private boolean free(Player player, World world, BlockKey at) {
        int chunkX = at.x() >> 4;
        int chunkZ = at.z() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ) || !server.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            return false;
        }
        Block now = world.getBlockAt(at.x(), at.y(), at.z());
        if (!now.isReplaceable()) {
            return false;
        }
        BoundingBox space = new BoundingBox(at.x(), at.y(), at.z(), at.x() + 1, at.y() + 1, at.z() + 1);
        if (!world.getNearbyEntities(space, entity -> entity instanceof LivingEntity).isEmpty()) {
            return false;
        }
        return mayBuild.test(player, at.centre(world));
    }

    /** This vein's own items still lying around it. Similar items that somebody else dropped are not touched. */
    private List<Item> groundAround(World world, VeinOperation vein, List<BrokenBlock> blocks) {
        double reach = 0;
        Set<UUID> ours = new HashSet<>();
        for (BrokenBlock block : blocks) {
            reach = Math.max(reach, block.at().distance(vein.source()));
            ours.addAll(block.dropEntities().keySet());
        }
        if (ours.isEmpty()) {
            return List.of();
        }
        return world.getNearbyEntitiesByType(Item.class, vein.source().centre(world), reach + GROUND_MARGIN)
                .stream()
                .filter(item -> ours.contains(item.getUniqueId()))
                .filter(Item::isValid)
                .filter(server::isOwnedByCurrentRegion)
                .toList();
    }
}
