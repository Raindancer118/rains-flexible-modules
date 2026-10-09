package de.raindancer.modules.invsnap.service;

import de.raindancer.core.content.items.InsuredItems;
import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.ItemValues;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.invsnap.InvSnapSettings;
import de.raindancer.modules.invsnap.model.ItemPolicy;
import de.raindancer.modules.invsnap.rules.ItemPremiumRule;
import de.raindancer.modules.invsnap.store.ItemPolicyStore;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Item insurance: one unstackable item per policy that never exists as a dropped item.
 *
 * <p>An insured item may be dropped and lent like any other; what the policy covers is its destruction, and
 * its owner's own death. When an insured item entity is destroyed, or an insured stack would drop at a
 * death, the stack goes to its owner instead. The one rule all paths keep: a stack is written to the
 * persistent "to collect" list (or placed in an inventory) <em>before</em> it is taken out of the drops or
 * its entity removed, and an item that cannot be secured is left alone — never both returned and
 * dropped, never simply gone.
 */
public final class ItemInsuranceService implements IInvSnapService {

    public static final String SOURCE = "invsnap.item-insurance";
    public static final String CLAIM_SOURCE = "invsnap.item-insurance-claim";

    /** How an item's mark is read and written; the live one is Core's {@link InsuredItems}. */
    public interface Marks {
        Optional<String> policy(ItemStack stack);

        ItemStack mark(ItemStack stack, UUID owner, String policy);

        ItemStack strip(ItemStack stack);
    }

    /** Money in and out; the live one is {@link Fees}. */
    public interface Payments {
        EconomyResult charge(UUID payer, Money written, String reason, String source);

        void refund(UUID to, Money taken, String reason, String source);
    }

    /** What an item is called, and which material draws its icon. */
    public interface Describer {
        String name(ItemStack stack);

        String material(ItemStack stack);
    }

    /** How a stack becomes text on disk and back. */
    public interface Codec {
        String encode(ItemStack stack);

        ItemStack decode(String text);
    }

    /** Whether an item can be insured now, and if not, the sentence that says why. */
    public record Quote(String refusal, Money value, Money premium, String description) {
        public boolean ok() {
            return refusal == null;
        }

        static Quote refused(String reason) {
            return new Quote(reason, Money.ZERO, Money.ZERO, "");
        }
    }

    /** What taking a policy came to. {@code refusal} is set when nothing was bought; {@code unpaid} when the premium failed. */
    public record Taken(String refusal, EconomyResult unpaid, Money paid, ItemPolicy policy) {
        public boolean done() {
            return policy != null;
        }
    }

    /** What a death did to the insured items in its drops. */
    public record Death(int ownReturned, Set<UUID> otherOwners, Money claimWritten, EconomyResult claim) {
        static final Death NOTHING = new Death(0, Set.of(), Money.ZERO, null);

        public boolean claimFailed() {
            return claim != null && !claim.succeeded();
        }
    }

    public enum Placed { INVENTORY, LIST, ALREADY, NOT_SECURED }

    private final ItemPolicyStore store;
    private final ItemPremiumRule rule;
    private final Payments payments;
    private final Function<ItemStack, Optional<Money>> valuer;
    private final Marks marks;
    private final Codec codec;
    private final Describer describer;
    private final Function<UUID, Player> online;
    private final LongSupplier clock;
    /** Entities already returned, so a damage event and the removal that follows it count once. */
    private final Map<UUID, Boolean> returnedEntities = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
            return size() > 2048;
        }
    };
    private volatile InvSnapSettings settings;

    public ItemInsuranceService(ItemPolicyStore store, ItemPremiumRule rule, Payments payments,
                                Function<ItemStack, Optional<Money>> valuer, Marks marks, Codec codec,
                                Describer describer, Function<UUID, Player> online,
                                LongSupplier clock, InvSnapSettings settings) {
        this.store = store;
        this.rule = rule;
        this.payments = payments;
        this.valuer = valuer;
        this.marks = marks;
        this.codec = codec;
        this.describer = describer;
        this.online = online;
        this.clock = clock;
        this.settings = settings;
    }

    public static ItemInsuranceService live(ItemPolicyStore store, Function<UUID, Player> online,
                                            InvSnapSettings settings) {
        return new ItemInsuranceService(store, new ItemPremiumRule(), new Payments() {
            @Override
            public EconomyResult charge(UUID payer, Money written, String reason, String source) {
                return Fees.charge(payer, written, reason, source);
            }

            @Override
            public void refund(UUID to, Money taken, String reason, String source) {
                Fees.refund(to, taken, reason, source);
            }
        }, stack -> ItemValues.valueOf(stack.asOne()), new Marks() {
            @Override
            public Optional<String> policy(ItemStack stack) {
                return InsuredItems.policy(stack);
            }

            @Override
            public ItemStack mark(ItemStack stack, UUID owner, String policy) {
                return InsuredItems.mark(stack, owner, policy);
            }

            @Override
            public ItemStack strip(ItemStack stack) {
                return InsuredItems.strip(stack);
            }
        }, new Codec() {
            @Override
            public String encode(ItemStack stack) {
                return ItemText.encode(stack);
            }

            @Override
            public ItemStack decode(String text) {
                return ItemText.decode(text);
            }
        }, new Describer() {
            @Override
            public String name(ItemStack stack) {
                return describeLive(stack);
            }

            @Override
            public String material(ItemStack stack) {
                return stack.getType().name();
            }
        }, online, System::currentTimeMillis, settings);
    }

    @Override
    public void settings(InvSnapSettings settings) {
        this.settings = settings;
    }

    public InvSnapSettings config() {
        return settings;
    }

    public boolean enabled() {
        return settings.itemInsuranceEnabled();
    }

    // ------------------------------------------------------------------ is it insured?

    /** The policy as the keeper of policies answers {@link InsuredItems#inForce}: switched on and not ended. */
    public boolean inForce(String policyId) {
        return settings.itemInsuranceEnabled() && alive(policyId);
    }

    /** Whether the policy exists and has not ended — the switch aside. Marks are stripped only when this is false. */
    private boolean alive(String policyId) {
        ItemPolicy policy = store.get(policyId);
        return policy != null && policy.inForce();
    }

    /** The policy an insured stack is under, or empty when it carries no mark or the mark is dead. */
    public Optional<ItemPolicy> policyOf(ItemStack stack) {
        if (stack == null) {
            return Optional.empty();
        }
        return marks.policy(stack).filter(this::inForce).map(store::get);
    }

    public boolean isInsured(ItemStack stack) {
        return policyOf(stack).isPresent();
    }

    public List<ItemPolicy> policiesOf(UUID owner) {
        return store.inForceOf(owner);
    }

    // ------------------------------------------------------------------ price

    public Money premiumFor(Money itemValue) {
        InvSnapSettings now = settings;
        return rule.premium(itemValue, now.itemInsurancePricePercent(), Fees.amount(now.itemInsurancePriceFlat()),
                Fees.amount(now.itemInsuranceLeast()));
    }

    /** What the next renewal of this policy costs, before any price index. */
    public Money premiumOf(ItemPolicy policy) {
        return premiumFor(Money.of(policy.value()));
    }

    public Money claimFee() {
        return Fees.amount(settings.itemInsuranceClaimFee());
    }

    /** Whether this stack could be insured by {@code owner} right now. */
    public Quote quote(UUID owner, ItemStack held) {
        InvSnapSettings now = settings;
        if (!now.itemInsuranceEnabled()) {
            return Quote.refused("Item insurance is not offered on this server");
        }
        if (!now.itemInsurancePriced()) {
            return Quote.refused("No price is set for item insurance");
        }
        if (held == null || held.isEmpty()) {
            return Quote.refused("Hold the item you want to insure in your main hand");
        }
        if (held.getMaxStackSize() != 1) {
            return Quote.refused("Only items that do not stack can be insured");
        }
        if (isInsured(held)) {
            return Quote.refused("This item is already insured");
        }
        if (store.inForceOf(owner).size() >= now.itemInsuranceMostItemsClamped()) {
            return Quote.refused("You already have " + now.itemInsuranceMostItemsClamped() + " item(s) insured, "
                    + "the most allowed");
        }
        Money value = valuer.apply(held).orElse(Money.ZERO);
        Money premium = premiumFor(value);
        if (!premium.isPositive()) {
            return Quote.refused("This item has no price to insure it by");
        }
        return new Quote(null, value, premium, describer.name(held));
    }

    // ------------------------------------------------------------------ taking and ending a policy

    /** Insures the item in the player's main hand: charges the premium first, then marks the item. */
    public Taken insure(Player owner) {
        PlayerInventory inventory = owner.getInventory();
        ItemStack held = inventory.getItemInMainHand();
        UUID id = owner.getUniqueId();
        Quote quote = quote(id, held);
        if (!quote.ok()) {
            return new Taken(quote.refusal(), null, Money.ZERO, null);
        }
        EconomyResult paid = payments.charge(id, quote.premium(), "Item insurance: " + quote.description(), SOURCE);
        if (!paid.succeeded()) {
            return new Taken(null, paid, Money.ZERO, null);
        }
        long now = clock.getAsLong();
        ItemPolicy policy = new ItemPolicy(UUID.randomUUID().toString(), id, quote.description(),
                describer.material(held), quote.value().minor(), quote.premium().minor(),
                now + settings.itemInsuranceEvery().toMillis(), "", false);
        if (!store.put(policy)) {
            payments.refund(id, paid.amount(), "Item insurance could not be saved", SOURCE);
            return new Taken("The policy could not be saved, so you were not charged", null, Money.ZERO, null);
        }
        ItemStack marked = marks.mark(held, id, policy.id());
        if (marked == null || marks.policy(marked).filter(policy.id()::equals).isEmpty()) {
            store.remove(policy.id());
            payments.refund(id, paid.amount(), "Item could not be marked", SOURCE);
            return new Taken("That item cannot carry a policy, so you were not charged", null, Money.ZERO, null);
        }
        inventory.setItemInMainHand(marked);
        return new Taken(null, null, paid.amount(), policy);
    }

    /** Ends a policy at its owner's request. No refund. Returns whether there was one to end. */
    public boolean cancel(Player owner, String policyId) {
        ItemPolicy policy = store.get(policyId);
        if (policy == null || !policy.owner().equals(owner.getUniqueId())) {
            return false;
        }
        store.remove(policyId);
        PlayerInventory inventory = owner.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack each = contents[slot];
            if (each != null && marks.policy(each).filter(policyId::equals).isPresent()) {
                inventory.setItem(slot, marks.strip(each));
            }
        }
        return true;
    }

    /** The in-force policy on the item in the player's main hand, if they own it. */
    public Optional<ItemPolicy> heldPolicyOf(Player owner) {
        return policyOf(owner.getInventory().getItemInMainHand())
                .filter(policy -> policy.owner().equals(owner.getUniqueId()));
    }

    // ------------------------------------------------------------------ renewals, wear, notices

    /**
     * Takes every premium that has fallen due. One that cannot be paid ends its policy; with no economy to ask,
     * nothing is decided and the next call tries again.
     *
     * @return the policies that lapsed
     */
    public List<ItemPolicy> renewDue() {
        if (!settings.itemInsuranceEnabled()) {
            return List.of();
        }
        long now = clock.getAsLong();
        long period = settings.itemInsuranceEvery().toMillis();
        List<ItemPolicy> lapsed = new ArrayList<>();
        for (ItemPolicy policy : store.all()) {
            if (!policy.inForce() || policy.nextDue() > now) {
                continue;
            }
            EconomyResult result = payments.charge(policy.owner(), premiumOf(policy),
                    "Item insurance renewal: " + policy.description(), SOURCE);
            if (result.succeeded()) {
                long due = policy.nextDue() + period > now ? policy.nextDue() + period : now + period;
                store.put(policy.renewed(result.amount().minor(), due));
            } else if (result.outcome() != EconomyResult.Outcome.UNAVAILABLE) {
                ItemPolicy ended = policy.endedBy(ItemPolicy.UNPAID);
                store.put(ended);
                lapsed.add(ended);
            }
        }
        return lapsed;
    }

    /** Wear ends a policy: insurance covers loss, not wear. */
    public Optional<ItemPolicy> onBreak(ItemStack broken) {
        Optional<ItemPolicy> policy = policyOf(broken);
        policy.ifPresent(each -> store.put(each.endedBy(ItemPolicy.WORN)));
        return policy.map(each -> each.endedBy(ItemPolicy.WORN));
    }

    /** Policies that ended while their owner was not told; handing them over forgets them. */
    public List<ItemPolicy> takeNotices(UUID owner) {
        List<ItemPolicy> ended = store.endedUntoldOf(owner);
        ended.forEach(each -> store.remove(each.id()));
        return ended;
    }

    // ------------------------------------------------------------------ death and spawn: the item goes home

    /**
     * Judges one death: every insured stack leaves the drops. The owner's own go on their "to collect" list
     * (unless the inventory is being kept, where they simply stay), a lent one goes to its owner's list, and
     * the deductible is charged for the dier's own.
     */
    public Death onDeath(PlayerDeathEvent event) {
        if (!settings.itemInsuranceEnabled()) {
            return Death.NOTHING;
        }
        Player dier = event.getPlayer();
        boolean kept = event.getKeepInventory();
        int own = 0;
        Set<UUID> others = new LinkedHashSet<>();
        Iterator<ItemStack> drops = event.getDrops().iterator();
        while (drops.hasNext()) {
            ItemStack stack = drops.next();
            Optional<ItemPolicy> policy = policyOf(stack);
            if (policy.isEmpty()) {
                continue;
            }
            if (kept) {
                // The stack is still in the kept inventory; dropping it too would be a copy.
                drops.remove();
                continue;
            }
            UUID owner = policy.get().owner();
            if (alreadyHome(owner, policy.get().id())) {
                // One policy, one item: a second copy is not insured, and is not dropped either.
                drops.remove();
                continue;
            }
            String text = codec.encode(stack);
            if (text == null || !store.addReturn(owner, text)) {
                continue;
            }
            drops.remove();
            if (owner.equals(dier.getUniqueId())) {
                own++;
            } else {
                others.add(owner);
            }
        }
        if (own == 0 && others.isEmpty()) {
            return Death.NOTHING;
        }
        Money fee = claimFee();
        EconomyResult claim = null;
        if (own > 0 && fee.isPositive()) {
            claim = payments.charge(dier.getUniqueId(), fee.times(own), "Item insurance deductible", CLAIM_SOURCE);
        }
        return new Death(own, others, fee, claim);
    }

    /**
     * An insured item entity is being destroyed (despawn, fire, lava, cactus, explosion, the void...): sends the
     * stack to its owner, once. The same entity reported again — a damage event, then its removal — is ignored.
     *
     * @return where it went; {@link Placed#NOT_SECURED} means nothing was done and the entity may go
     */
    public Placed onDestroyed(UUID entity, ItemStack stack) {
        Optional<ItemPolicy> policy = policyOf(stack);
        if (policy.isEmpty()) {
            return Placed.NOT_SECURED;
        }
        synchronized (returnedEntities) {
            if (returnedEntities.containsKey(entity)) {
                return Placed.ALREADY;
            }
        }
        UUID owner = policy.get().owner();
        if (alreadyHome(owner, policy.get().id())) {
            // One policy, one item: the owner has it, or it waits for them — this is a copy, and goes.
            synchronized (returnedEntities) {
                returnedEntities.put(entity, Boolean.TRUE);
            }
            return Placed.ALREADY;
        }
        Placed placed = Placed.NOT_SECURED;
        Player player = online.apply(owner);
        if (player != null && !player.isDead() && hasRoom(player) && player.getInventory().addItem(stack).isEmpty()) {
            placed = Placed.INVENTORY;
        } else {
            String text = codec.encode(stack);
            if (text != null && store.addReturn(owner, text)) {
                placed = Placed.LIST;
            }
        }
        if (placed != Placed.NOT_SECURED) {
            synchronized (returnedEntities) {
                returnedEntities.put(entity, Boolean.TRUE);
            }
        }
        return placed;
    }

    /**
     * Whether the item of this policy is already with its owner — in their inventory, or waiting on their list.
     * One policy covers one item: anything returned beyond that would be a copy made out of thin air.
     */
    private boolean alreadyHome(UUID owner, String policyId) {
        Player player = online.apply(owner);
        if (player != null) {
            ItemStack[] contents = player.getInventory().getContents();
            if (contents != null) {
                for (ItemStack each : contents) {
                    if (each != null && marks.policy(each).filter(policyId::equals).isPresent()) {
                        return true;
                    }
                }
            }
        }
        for (String text : store.returnsOf(owner)) {
            ItemStack waiting = codec.decode(text);
            if (waiting != null && marks.policy(waiting).filter(policyId::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the "to collect" list

    /** What is waiting for this owner, decoded; an entry that cannot be read is left out here and kept on disk. */
    public List<ItemStack> pending(UUID owner) {
        List<ItemStack> found = new ArrayList<>();
        for (String text : store.returnsOf(owner)) {
            ItemStack stack = codec.decode(text);
            if (stack != null) {
                found.add(stack);
            }
        }
        return found;
    }

    public int pendingCount(UUID owner) {
        return store.returnsOf(owner).size();
    }

    /** Hands over as many waiting items as there is room for; the list is written before the inventory is touched. */
    public int deliver(Player owner) {
        return handOver(owner, Integer.MAX_VALUE, -1);
    }

    /** Hands over the one item at {@code index} of the list, if there is room. */
    public boolean collect(Player owner, int index) {
        return handOver(owner, 1, index) == 1;
    }

    private int handOver(Player owner, int most, int onlyIndex) {
        if (owner.isDead()) {
            return 0;
        }
        UUID id = owner.getUniqueId();
        List<String> waiting = store.returnsOf(id);
        int room = freeSlots(owner);
        List<String> keep = new ArrayList<>();
        List<ItemStack> give = new ArrayList<>();
        for (int index = 0; index < waiting.size(); index++) {
            String text = waiting.get(index);
            ItemStack stack = (onlyIndex < 0 || index == onlyIndex) && give.size() < Math.min(most, room)
                    ? codec.decode(text) : null;
            if (stack == null) {
                keep.add(text);
            } else {
                give.add(marks.policy(stack).filter(this::alive).isPresent() ? stack : marks.strip(stack));
            }
        }
        if (give.isEmpty() || !store.setReturns(id, keep)) {
            return 0;
        }
        int handed = 0;
        for (ItemStack stack : give) {
            if (owner.getInventory().addItem(stack).isEmpty()) {
                handed++;
            } else {
                String text = codec.encode(stack);
                if (text != null) {
                    store.addReturn(id, text);
                }
            }
        }
        return handed;
    }

    private boolean hasRoom(Player player) {
        return freeSlots(player) > 0;
    }

    private static int freeSlots(Player player) {
        int free = 0;
        for (ItemStack each : player.getInventory().getStorageContents()) {
            if (each == null || each.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /** Strips the marks of policies that no longer exist from what this player carries. */
    public int sweep(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        int stripped = 0;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack each = contents[slot];
            if (each != null && marks.policy(each).filter(policy -> !alive(policy)).isPresent()) {
                inventory.setItem(slot, marks.strip(each));
                stripped++;
            }
        }
        return stripped;
    }

    // ------------------------------------------------------------------ naming

    public String describe(ItemStack stack) {
        return describer.name(stack);
    }

    static String describeLive(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        StringBuilder words = new StringBuilder();
        for (String word : stack.getType().name().toLowerCase(Locale.ROOT).split("_")) {
            if (!word.isEmpty()) {
                words.append(words.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1));
            }
        }
        return words.toString();
    }
}
