package de.raindancer.modules.invsnap.service;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.invsnap.InvSnapSettings;
import de.raindancer.modules.invsnap.model.ItemPolicy;
import de.raindancer.modules.invsnap.rules.ItemPremiumRule;
import de.raindancer.modules.invsnap.store.ItemPolicyStore;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The duplication-critical paths first: the death path and the spawn path. */
class ItemInsuranceServiceTest {

    @TempDir
    Path folder;

    private final UUID owner = UUID.randomUUID();
    private final UUID borrower = UUID.randomUUID();
    private final Map<ItemStack, String> marked = new IdentityHashMap<>();
    private final Map<String, ItemStack> disk = new HashMap<>();
    private final Map<ItemStack, String> encoded = new IdentityHashMap<>();
    private final Map<UUID, Player> online = new HashMap<>();
    private final List<String> charges = new ArrayList<>();
    private final List<String> refunds = new ArrayList<>();
    private final long[] now = {1_000_000L};
    private boolean encodingWorks = true;
    private EconomyResult outcome = EconomyResult.done(Money.of(500), Money.ZERO);

    private ItemPolicyStore store;
    private ItemInsuranceService service;

    @BeforeEach
    void setUp() {
        store = new ItemPolicyStore(folder);
        service = build(on());
    }

    private InvSnapSettings on() {
        return InvSnapSettings.DEFAULTS.withItemInsurance(true).withItemInsurancePrice(10, "0", "0")
                .withItemInsuranceTerms(168, 3, "0");
    }

    private ItemInsuranceService build(InvSnapSettings settings) {
        return new ItemInsuranceService(store, new ItemPremiumRule(), new ItemInsuranceService.Payments() {
            @Override
            public EconomyResult charge(UUID payer, Money written, String reason, String source) {
                charges.add(source + ":" + written.minor());
                return outcome;
            }

            @Override
            public void refund(UUID to, Money taken, String reason, String source) {
                refunds.add(source + ":" + taken.minor());
            }
        }, stack -> Optional.of(Money.of(5_000)), new ItemInsuranceService.Marks() {
            @Override
            public Optional<String> policy(ItemStack stack) {
                return Optional.ofNullable(marked.get(stack));
            }

            @Override
            public ItemStack mark(ItemStack stack, UUID who, String policy) {
                marked.put(stack, policy);
                return stack;
            }

            @Override
            public ItemStack strip(ItemStack stack) {
                marked.remove(stack);
                return stack;
            }
        }, new ItemInsuranceService.Codec() {
            @Override
            public String encode(ItemStack stack) {
                if (!encodingWorks) {
                    return null;
                }
                String text = "item-" + disk.size();
                disk.put(text, stack);
                encoded.put(stack, text);
                return text;
            }

            @Override
            public ItemStack decode(String text) {
                return disk.get(text);
            }
        }, describer(), online::get, () -> now[0], settings);
    }

    private static ItemInsuranceService.Describer describer() {
        return new ItemInsuranceService.Describer() {
            public String name(ItemStack stack) {
                return "Sword";
            }

            public String material(ItemStack stack) {
                return "DIAMOND_SWORD";
            }
        };
    }

    private ItemStack sword(String policy) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getMaxStackSize()).thenReturn(1);
        if (policy != null) {
            marked.put(stack, policy);
        }
        return stack;
    }

    private ItemPolicy policyFor(UUID who, String id) {
        ItemPolicy policy = new ItemPolicy(id, who, "Sword", "DIAMOND_SWORD", 5_000, 500, now[0] + 1_000, "", false);
        store.put(policy);
        return policy;
    }

    private Player player(UUID id, int freeSlots) {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.isDead()).thenReturn(false);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack[] storage = new ItemStack[36];
        for (int slot = freeSlots; slot < storage.length; slot++) {
            storage[slot] = sword(null);
        }
        when(inventory.getStorageContents()).thenReturn(storage);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
        online.put(id, player);
        return player;
    }

    private PlayerDeathEvent death(Player dier, boolean keep, ItemStack... drops) {
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getPlayer()).thenReturn(dier);
        when(event.getKeepInventory()).thenReturn(keep);
        when(event.getDrops()).thenReturn(new ArrayList<>(List.of(drops)));
        return event;
    }

    @Nested
    @DisplayName("the death path")
    class Death {

        @Test
        @DisplayName("the owner's insured item leaves the drops and is on disk before the death ends")
        void ownItemIsStoredAndNotDropped() {
            policyFor(owner, "p1");
            ItemStack insured = sword("p1");
            ItemStack junk = sword(null);
            Player dier = player(owner, 5);
            PlayerDeathEvent event = death(dier, false, insured, junk);

            ItemInsuranceService.Death result = service.onDeath(event);

            assertThat(event.getDrops()).containsExactly(junk);
            assertThat(result.ownReturned()).isEqualTo(1);
            assertThat(new ItemPolicyStore(folder).returnsOf(owner)).hasSize(1);
        }

        @Test
        @DisplayName("the owner's own death: the item still in the dying inventory is the item itself, and is stored")
        void ownDeathWithItemStillInInventory() {
            policyFor(owner, "p1");
            Player dier = player(owner, 3);
            ItemStack item = sword("p1");
            when(dier.getInventory().getContents()).thenReturn(new ItemStack[]{item});
            PlayerDeathEvent event = death(dier, false, item);

            service.onDeath(event);

            assertThat(event.getDrops()).isEmpty();
            assertThat(store.returnsOf(owner)).as("not lost").hasSize(1);
        }

        @Test
        @DisplayName("one policy, one item: a copy in the drops while the item already waits is removed, not stored again")
        void copyOnDeathIsNotStoredTwice() {
            policyFor(owner, "p1");
            Player dier = player(owner, 3);
            service.onDestroyed(UUID.randomUUID(), sword("p1"));
            PlayerDeathEvent event = death(dier, false, sword("p1"));

            service.onDeath(event);

            assertThat(event.getDrops()).isEmpty();
            assertThat(store.returnsOf(owner)).hasSize(1);
        }

        @Test
        @DisplayName("an item is never both stored and dropped")
        void neverBoth() {
            policyFor(owner, "p1");
            ItemStack insured = sword("p1");
            PlayerDeathEvent event = death(player(owner, 5), false, insured);

            service.onDeath(event);

            assertThat(event.getDrops()).doesNotContain(insured);
            assertThat(store.returnsOf(owner)).hasSize(1);
            // and delivering it once empties the list, so a second delivery cannot make a copy
            assertThat(service.deliver(online.get(owner))).isEqualTo(1);
            assertThat(service.deliver(online.get(owner))).isZero();
            assertThat(store.returnsOf(owner)).isEmpty();
        }

        @Test
        @DisplayName("with the inventory kept the item is only removed from the drops, not stored a second time")
        void keptInventory() {
            policyFor(owner, "p1");
            ItemStack insured = sword("p1");
            PlayerDeathEvent event = death(player(owner, 5), true, insured);

            ItemInsuranceService.Death result = service.onDeath(event);

            assertThat(event.getDrops()).isEmpty();
            assertThat(store.returnsOf(owner)).isEmpty();
            assertThat(result.ownReturned()).isZero();
        }

        @Test
        @DisplayName("a lent item goes to its owner's list, not the dier's, and costs the dier nothing")
        void lentItemGoesHome() {
            policyFor(owner, "p1");
            ItemStack insured = sword("p1");
            PlayerDeathEvent event = death(player(borrower, 5), false, insured);

            ItemInsuranceService.Death result = service.onDeath(event);

            assertThat(event.getDrops()).isEmpty();
            assertThat(store.returnsOf(owner)).hasSize(1);
            assertThat(store.returnsOf(borrower)).isEmpty();
            assertThat(result.otherOwners()).containsExactly(owner);
            assertThat(charges).isEmpty();
        }

        @Test
        @DisplayName("the deductible is charged for the owner's own returned items, once per item")
        void deductible() {
            service.settings(on().withItemInsuranceTerms(168, 3, "20"));
            policyFor(owner, "p1");
            policyFor(owner, "p2");
            PlayerDeathEvent event = death(player(owner, 5), false, sword("p1"), sword("p2"));

            ItemInsuranceService.Death result = service.onDeath(event);

            assertThat(charges).containsExactly("invsnap.item-insurance-claim:4000");
            assertThat(result.claim().succeeded()).isTrue();
        }

        @Test
        @DisplayName("a deductible that cannot be paid never holds the item back")
        void unpaidDeductibleStillReturns() {
            service.settings(on().withItemInsuranceTerms(168, 3, "20"));
            policyFor(owner, "p1");
            outcome = EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, Money.of(2_000), Money.ZERO);
            PlayerDeathEvent event = death(player(owner, 5), false, sword("p1"));

            ItemInsuranceService.Death result = service.onDeath(event);

            assertThat(result.claimFailed()).isTrue();
            assertThat(event.getDrops()).isEmpty();
            assertThat(store.returnsOf(owner)).hasSize(1);
        }

        @Test
        @DisplayName("an item that cannot be written down is left to drop rather than lost")
        void unwritableStaysInDrops() {
            policyFor(owner, "p1");
            encodingWorks = false;
            ItemStack insured = sword("p1");
            PlayerDeathEvent event = death(player(owner, 5), false, insured);

            service.onDeath(event);

            assertThat(event.getDrops()).containsExactly(insured);
            assertThat(store.returnsOf(owner)).isEmpty();
        }

        @Test
        @DisplayName("switched off, or lapsed, or never insured: the drops are untouched")
        void notInForce() {
            policyFor(owner, "p1");
            ItemStack insured = sword("p1");
            PlayerDeathEvent off = death(player(owner, 5), false, insured);
            service.settings(InvSnapSettings.DEFAULTS);
            service.onDeath(off);
            assertThat(off.getDrops()).containsExactly(insured);

            service.settings(on());
            store.put(store.get("p1").endedBy(ItemPolicy.UNPAID));
            PlayerDeathEvent lapsed = death(player(owner, 5), false, insured);
            service.onDeath(lapsed);
            assertThat(lapsed.getDrops()).containsExactly(insured);
            assertThat(store.returnsOf(owner)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the destruction path")
    class Destruction {

        @Test
        @DisplayName("an owner who is online with room gets it straight into the inventory, nothing on disk")
        void straightToInventory() {
            policyFor(owner, "p1");
            Player ownerPlayer = player(owner, 3);

            assertThat(service.onDestroyed(UUID.randomUUID(), sword("p1")))
                    .isEqualTo(ItemInsuranceService.Placed.INVENTORY);

            verify(ownerPlayer.getInventory()).addItem(any(ItemStack.class));
            assertThat(store.returnsOf(owner)).isEmpty();
        }

        @Test
        @DisplayName("an owner with a full inventory gets it on the persistent list")
        void fullInventoryGoesToList() {
            policyFor(owner, "p1");
            Player ownerPlayer = player(owner, 0);

            assertThat(service.onDestroyed(UUID.randomUUID(), sword("p1")))
                    .isEqualTo(ItemInsuranceService.Placed.LIST);

            verify(ownerPlayer.getInventory(), never()).addItem(any(ItemStack.class));
            assertThat(new ItemPolicyStore(folder).returnsOf(owner)).hasSize(1);
        }

        @Test
        @DisplayName("an owner who is offline gets it on the list")
        void offlineGoesToList() {
            policyFor(owner, "p1");

            assertThat(service.onDestroyed(UUID.randomUUID(), sword("p1")))
                    .isEqualTo(ItemInsuranceService.Placed.LIST);
            assertThat(store.returnsOf(owner)).hasSize(1);
        }

        @Test
        @DisplayName("one policy, one item: a second copy destroyed while the first waits is not returned again")
        void secondCopyIsNotReturned() {
            policyFor(owner, "p1");

            assertThat(service.onDestroyed(UUID.randomUUID(), sword("p1"))).isEqualTo(ItemInsuranceService.Placed.LIST);
            assertThat(service.onDestroyed(UUID.randomUUID(), sword("p1")))
                    .isEqualTo(ItemInsuranceService.Placed.ALREADY);

            assertThat(store.returnsOf(owner)).hasSize(1);
        }

        @Test
        @DisplayName("one policy, one item: a copy destroyed while the owner holds the item is not returned")
        void copyWhileOwnerHoldsIt() {
            policyFor(owner, "p1");
            Player ownerPlayer = player(owner, 3);
            ItemStack held = sword("p1");
            when(ownerPlayer.getInventory().getContents()).thenReturn(new ItemStack[]{held});

            assertThat(service.onDestroyed(UUID.randomUUID(), sword("p1")))
                    .isEqualTo(ItemInsuranceService.Placed.ALREADY);
            verify(ownerPlayer.getInventory(), never()).addItem(any(ItemStack.class));
            assertThat(store.returnsOf(owner)).isEmpty();
        }

        @Test
        @DisplayName("the same entity reported twice (damage, then removal) is returned exactly once")
        void exactlyOnce() {
            policyFor(owner, "p1");
            UUID entity = UUID.randomUUID();
            ItemStack stack = sword("p1");

            assertThat(service.onDestroyed(entity, stack)).isEqualTo(ItemInsuranceService.Placed.LIST);
            assertThat(service.onDestroyed(entity, stack)).isEqualTo(ItemInsuranceService.Placed.ALREADY);
            assertThat(service.onDestroyed(entity, stack)).isEqualTo(ItemInsuranceService.Placed.ALREADY);

            assertThat(store.returnsOf(owner)).hasSize(1);
        }

        @Test
        @DisplayName("what is not insured, or whose policy has lapsed, is left to be destroyed")
        void notInsured() {
            assertThat(service.onDestroyed(UUID.randomUUID(), sword(null)))
                    .isEqualTo(ItemInsuranceService.Placed.NOT_SECURED);
            policyFor(owner, "p1");
            store.put(store.get("p1").endedBy(ItemPolicy.UNPAID));
            assertThat(service.onDestroyed(UUID.randomUUID(), sword("p1")))
                    .isEqualTo(ItemInsuranceService.Placed.NOT_SECURED);
            assertThat(store.returnsOf(owner)).isEmpty();
        }

        @Test
        @DisplayName("an item that cannot be written down is not claimed, so it is not lost twice over")
        void unwritable() {
            policyFor(owner, "p1");
            encodingWorks = false;
            UUID entity = UUID.randomUUID();

            assertThat(service.onDestroyed(entity, sword("p1"))).isEqualTo(ItemInsuranceService.Placed.NOT_SECURED);

            encodingWorks = true;
            assertThat(service.onDestroyed(entity, sword("p1"))).isEqualTo(ItemInsuranceService.Placed.LIST);
        }
    }

    @Nested
    @DisplayName("the to-collect list")
    class Collecting {

        @Test
        @DisplayName("only as many come back as there are free slots; the rest stay on disk")
        void partialRoom() {
            policyFor(owner, "p1");
            store.addReturn(owner, encode(sword("p1")));
            store.addReturn(owner, encode(sword("p1")));
            store.addReturn(owner, encode(sword("p1")));
            Player ownerPlayer = player(owner, 2);

            assertThat(service.deliver(ownerPlayer)).isEqualTo(2);

            assertThat(new ItemPolicyStore(folder).returnsOf(owner)).hasSize(1);
        }

        @Test
        @DisplayName("a dead player is handed nothing")
        void deadWaits() {
            policyFor(owner, "p1");
            store.addReturn(owner, encode(sword("p1")));
            Player ownerPlayer = player(owner, 5);
            when(ownerPlayer.isDead()).thenReturn(true);

            assertThat(service.deliver(ownerPlayer)).isZero();
            assertThat(store.returnsOf(owner)).hasSize(1);
        }

        @Test
        @DisplayName("an item of a policy that has since ended comes back without its mark")
        void endedMarkIsStripped() {
            ItemStack stack = sword("gone");
            store.addReturn(owner, encode(stack));

            service.deliver(player(owner, 5));

            assertThat(marked).doesNotContainKey(stack);
        }

        @Test
        @DisplayName("one item can be collected by its place in the list")
        void collectOne() {
            policyFor(owner, "p1");
            store.addReturn(owner, encode(sword("p1")));
            store.addReturn(owner, encode(sword("p1")));

            assertThat(service.collect(player(owner, 5), 1)).isTrue();
            assertThat(store.returnsOf(owner)).hasSize(1);
        }

        private String encode(ItemStack stack) {
            return service(stack);
        }

        private String service(ItemStack stack) {
            String text = "stored-" + disk.size();
            disk.put(text, stack);
            return text;
        }
    }

    @Nested
    @DisplayName("taking and ending a policy")
    class Policies {

        private Player holder(ItemStack held) {
            Player player = player(owner, 5);
            when(player.getInventory().getItemInMainHand()).thenReturn(held);
            return player;
        }

        @Test
        @DisplayName("the premium is charged before the item is marked, and the policy is stored")
        void insureCharges() {
            ItemStack held = sword(null);
            Player player = holder(held);

            ItemInsuranceService.Taken taken = service.insure(player);

            assertThat(taken.done()).isTrue();
            assertThat(charges).containsExactly("invsnap.item-insurance:500");
            assertThat(marked.get(held)).isEqualTo(taken.policy().id());
            assertThat(new ItemPolicyStore(folder).get(taken.policy().id()).owner()).isEqualTo(owner);
            assertThat(taken.policy().nextDue()).isEqualTo(now[0] + 168L * 3_600_000L);
        }

        @Test
        @DisplayName("an unpaid premium buys nothing and marks nothing")
        void unpaid() {
            outcome = EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, Money.of(500), Money.ZERO);
            ItemStack held = sword(null);

            ItemInsuranceService.Taken taken = service.insure(holder(held));

            assertThat(taken.done()).isFalse();
            assertThat(marked).doesNotContainKey(held);
            assertThat(store.all()).isEmpty();
        }

        @Test
        @DisplayName("refused: switch off, no price, stackable, already insured, at the limit")
        void refusals() {
            ItemStack held = sword(null);
            assertThat(build(InvSnapSettings.DEFAULTS).quote(owner, held).refusal()).contains("not offered");
            assertThat(build(on().withItemInsurancePrice(0, "0", "0")).quote(owner, held).refusal())
                    .contains("No price");
            ItemStack stackable = sword(null);
            when(stackable.getMaxStackSize()).thenReturn(64);
            assertThat(service.quote(owner, stackable).refusal()).contains("do not stack");
            policyFor(owner, "p1");
            assertThat(service.quote(owner, sword("p1")).refusal()).contains("already");
            policyFor(owner, "p2");
            policyFor(owner, "p3");
            assertThat(service.quote(owner, held).refusal()).contains("most allowed");
            assertThat(service.quote(UUID.randomUUID(), held).ok()).isTrue();
        }

        @Test
        @DisplayName("an item with no value and no flat price or floor is refused, not insured for free")
        void noValue() {
            ItemInsuranceService free = new ItemInsuranceService(store, new ItemPremiumRule(),
                    new ItemInsuranceService.Payments() {
                        public EconomyResult charge(UUID p, Money w, String r, String s) {
                            return outcome;
                        }

                        public void refund(UUID t, Money k, String r, String s) {
                        }
                    }, stack -> Optional.empty(), new ItemInsuranceService.Marks() {
                        public Optional<String> policy(ItemStack s) {
                            return Optional.empty();
                        }

                        public ItemStack mark(ItemStack s, UUID o, String p) {
                            return s;
                        }

                        public ItemStack strip(ItemStack s) {
                            return s;
                        }
                    }, null, describer(), online::get, () -> now[0], on());

            assertThat(free.quote(owner, sword(null)).refusal()).contains("no price");
        }

        @Test
        @DisplayName("an item that cannot take a mark refunds the premium and stores no policy")
        void markFailureRefunds() {
            ItemStack held = sword(null);
            ItemInsuranceService broken = new ItemInsuranceService(store, new ItemPremiumRule(),
                    new ItemInsuranceService.Payments() {
                        public EconomyResult charge(UUID p, Money w, String r, String s) {
                            return outcome;
                        }

                        public void refund(UUID t, Money k, String r, String s) {
                            refunds.add(s + ":" + k.minor());
                        }
                    }, stack -> Optional.of(Money.of(5_000)), new ItemInsuranceService.Marks() {
                        public Optional<String> policy(ItemStack s) {
                            return Optional.empty();
                        }

                        public ItemStack mark(ItemStack s, UUID o, String p) {
                            return s;
                        }

                        public ItemStack strip(ItemStack s) {
                            return s;
                        }
                    }, null, describer(), online::get, () -> now[0], on());

            assertThat(broken.insure(holder(held)).done()).isFalse();
            assertThat(refunds).containsExactly("invsnap.item-insurance:500");
            assertThat(store.all()).isEmpty();
        }

        @Test
        @DisplayName("cancelling strips the mark the owner holds and ends the policy, without a refund")
        void cancel() {
            policyFor(owner, "p1");
            ItemStack held = sword("p1");
            Player player = holder(held);
            when(player.getInventory().getContents()).thenReturn(new ItemStack[]{held});

            assertThat(service.cancel(player, "p1")).isTrue();

            assertThat(marked).doesNotContainKey(held);
            assertThat(store.get("p1")).isNull();
            assertThat(refunds).isEmpty();
        }

        @Test
        @DisplayName("only the owner can cancel a policy")
        void cancelIsTheOwnersOnly() {
            policyFor(owner, "p1");

            assertThat(service.cancel(player(borrower, 5), "p1")).isFalse();
            assertThat(store.get("p1")).isNotNull();
        }

        @Test
        @DisplayName("wearing out ends the policy and the owner is told once")
        void wear() {
            policyFor(owner, "p1");

            assertThat(service.onBreak(sword("p1"))).isPresent();

            assertThat(service.isInsured(sword("p1"))).isFalse();
            assertThat(service.takeNotices(owner)).hasSize(1);
            assertThat(service.takeNotices(owner)).isEmpty();
        }
    }

    @Nested
    @DisplayName("renewals")
    class Renewals {

        @Test
        @DisplayName("a premium that is not due yet is not charged")
        void notDue() {
            policyFor(owner, "p1");

            assertThat(service.renewDue()).isEmpty();
            assertThat(charges).isEmpty();
        }

        @Test
        @DisplayName("a due premium is charged from the stored value and the policy runs on a period")
        void due() {
            policyFor(owner, "p1");
            now[0] += 2_000;

            assertThat(service.renewDue()).isEmpty();

            assertThat(charges).containsExactly("invsnap.item-insurance:500");
            assertThat(store.get("p1").nextDue()).isEqualTo(now[0] - 1_000 + 168L * 3_600_000L);
            assertThat(service.renewDue()).isEmpty();
            assertThat(charges).hasSize(1);
        }

        @Test
        @DisplayName("a premium that cannot be paid lapses the policy, and its mark stops counting")
        void lapses() {
            policyFor(owner, "p1");
            now[0] += 2_000;
            outcome = EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, Money.of(500), Money.ZERO);

            List<ItemPolicy> lapsed = service.renewDue();

            assertThat(lapsed).hasSize(1);
            assertThat(service.isInsured(sword("p1"))).isFalse();
            assertThat(service.takeNotices(owner)).hasSize(1);
        }

        @Test
        @DisplayName("with no economy to ask nothing lapses; the next call tries again")
        void noEconomyWaits() {
            policyFor(owner, "p1");
            now[0] += 2_000;
            outcome = EconomyResult.failed(EconomyResult.Outcome.UNAVAILABLE, Money.of(500), Money.ZERO);

            assertThat(service.renewDue()).isEmpty();
            assertThat(store.get("p1").inForce()).isTrue();
        }

        @Test
        @DisplayName("switched off, nothing is charged")
        void switchedOff() {
            policyFor(owner, "p1");
            now[0] += 2_000;
            service.settings(InvSnapSettings.DEFAULTS);

            assertThat(service.renewDue()).isEmpty();
            assertThat(charges).isEmpty();
        }
    }
}
