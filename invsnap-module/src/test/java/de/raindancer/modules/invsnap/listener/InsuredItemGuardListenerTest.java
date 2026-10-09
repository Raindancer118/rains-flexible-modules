package de.raindancer.modules.invsnap.listener;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.invsnap.InvSnapSettings;
import de.raindancer.modules.invsnap.model.ItemPolicy;
import de.raindancer.modules.invsnap.rules.ItemPremiumRule;
import de.raindancer.modules.invsnap.service.ItemInsuranceService;
import de.raindancer.modules.invsnap.store.ItemPolicyStore;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An insured item entity that would be destroyed is removed and its stack goes home, once. */
class InsuredItemGuardListenerTest {

    @TempDir
    Path folder;

    private final UUID owner = UUID.randomUUID();
    private final Map<ItemStack, String> marked = new IdentityHashMap<>();
    private final Map<String, ItemStack> disk = new java.util.HashMap<>();
    private ItemPolicyStore store;
    private InsuredItemGuardListener listener;
    private final Messages messages = mock(Messages.class);

    @BeforeEach
    void setUp() {
        store = new ItemPolicyStore(folder);
        store.put(new ItemPolicy("p1", owner, "Sword", "DIAMOND_SWORD", 5_000, 500, Long.MAX_VALUE, "", false));
        ItemInsuranceService service = new ItemInsuranceService(store, new ItemPremiumRule(),
                new ItemInsuranceService.Payments() {
                    public EconomyResult charge(UUID p, Money w, String r, String s) {
                        return EconomyResult.done(w, Money.ZERO);
                    }

                    public void refund(UUID t, Money k, String r, String s) {
                    }
                }, stack -> Optional.of(Money.of(100)), new ItemInsuranceService.Marks() {
            public Optional<String> policy(ItemStack s) {
                return Optional.ofNullable(marked.get(s));
            }

            public ItemStack mark(ItemStack s, UUID o, String p) {
                marked.put(s, p);
                return s;
            }

            public ItemStack strip(ItemStack s) {
                marked.remove(s);
                return s;
            }
        }, new ItemInsuranceService.Codec() {
            public String encode(ItemStack s) {
                String text = "t" + disk.size();
                disk.put(text, s);
                return text;
            }

            public ItemStack decode(String t) {
                return disk.get(t);
            }
        }, new ItemInsuranceService.Describer() {
            public String name(ItemStack s) {
                return "Sword";
            }

            public String material(ItemStack s) {
                return "DIAMOND_SWORD";
            }
        }, id -> null, System::currentTimeMillis,
                InvSnapSettings.DEFAULTS.withItemInsurance(true).withItemInsurancePrice(10, "0", "0"));
        listener = new InsuredItemGuardListener(service, messages, id -> null, null);
    }

    private ItemStack stack(boolean insured) {
        ItemStack stack = mock(ItemStack.class);
        if (insured) {
            marked.put(stack, "p1");
        }
        return stack;
    }

    private Item entity(ItemStack stack) {
        Item entity = mock(Item.class);
        when(entity.getItemStack()).thenReturn(stack);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        return entity;
    }

    @Test
    @DisplayName("an insured item that despawns goes to its owner and the entity is removed")
    void despawnIsRescued() {
        ItemDespawnEvent event = mock(ItemDespawnEvent.class);
        Item entity = entity(stack(true));
        when(event.getEntity()).thenReturn(entity);

        listener.onDespawn(event);

        verify(event).setCancelled(true);
        verify(entity).remove();
        assertThat(store.returnsOf(owner)).hasSize(1);
    }

    @Test
    @DisplayName("damage to an insured item (lava, fire...) returns it, then the removal does not return it again")
    void damageThenRemovalIsOnce() {
        EntityDamageEvent damage = mock(EntityDamageEvent.class);
        Item entity = entity(stack(true));
        when(damage.getEntity()).thenReturn(entity);
        EntityRemoveEvent removal = mock(EntityRemoveEvent.class);
        when(removal.getEntity()).thenReturn(entity);
        when(removal.getCause()).thenReturn(EntityRemoveEvent.Cause.DEATH);

        listener.onDamage(damage);
        listener.onRemove(removal);

        verify(damage).setCancelled(true);
        assertThat(store.returnsOf(owner)).hasSize(1);
    }

    @Test
    @DisplayName("falling out of the world is rescued too")
    void voidIsRescued() {
        Item entity = entity(stack(true));
        EntityRemoveEvent removal = mock(EntityRemoveEvent.class);
        when(removal.getEntity()).thenReturn(entity);
        when(removal.getCause()).thenReturn(EntityRemoveEvent.Cause.OUT_OF_WORLD);

        listener.onRemove(removal);

        assertThat(store.returnsOf(owner)).hasSize(1);
    }

    @Test
    @DisplayName("being picked up is not destruction")
    void pickupIsLeftAlone() {
        Item entity = entity(stack(true));
        EntityRemoveEvent removal = mock(EntityRemoveEvent.class);
        when(removal.getEntity()).thenReturn(entity);
        when(removal.getCause()).thenReturn(EntityRemoveEvent.Cause.PICKUP);

        listener.onRemove(removal);

        assertThat(store.returnsOf(owner)).isEmpty();
    }

    @Test
    @DisplayName("an ordinary item that despawns is left alone")
    void ordinaryDespawnIsLeftAlone() {
        ItemDespawnEvent event = mock(ItemDespawnEvent.class);
        Item entity = entity(stack(false));
        when(event.getEntity()).thenReturn(entity);

        listener.onDespawn(event);

        verify(event, never()).setCancelled(true);
        verify(entity, never()).remove();
        assertThat(store.returnsOf(owner)).isEmpty();
    }
}
