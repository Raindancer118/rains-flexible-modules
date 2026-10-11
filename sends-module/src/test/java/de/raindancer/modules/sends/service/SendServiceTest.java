package de.raindancer.modules.sends.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.sends.SendsSettings;
import de.raindancer.modules.sends.model.Send;
import de.raindancer.modules.sends.rules.SendableItemsRule;
import de.raindancer.modules.sends.store.SendBook;
import de.raindancer.modules.sends.store.SendStorage;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hand-over order and its failure paths. The plugin is "not enabled", which Core's Scheduling reads as
 * "run it where you are" — so everything happens synchronously and the order can be asserted.
 */
class SendServiceTest {

    private static final long MINUTE = 60_000;

    private static final class MemoryStorage implements SendStorage {
        final Map<UUID, Send> saved = new HashMap<>();
        boolean failing;

        @Override
        public List<Send> loadOpen() {
            return saved.values().stream().filter(send -> send.state().isOpen()).toList();
        }

        @Override
        public boolean save(Send send) {
            if (failing) {
                return false;
            }
            saved.put(send.id(), send);
            return true;
        }
    }

    private static final class Person {
        final UUID id = UUID.randomUUID();
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final List<ItemStack> received = new ArrayList<>();
        boolean roomLeft = true;

        Person(String name) {
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn(name);
            when(player.isOnline()).thenReturn(true);
            when(player.getInventory()).thenReturn(inventory);
            when(inventory.getStorageContents()).thenAnswer(call -> {
                ItemStack[] slots = new ItemStack[36];
                if (!roomLeft) {
                    java.util.Arrays.fill(slots, item("filler", 64));
                }
                return slots;
            });
            when(inventory.addItem(any(ItemStack[].class))).thenAnswer(call -> {
                for (Object given : call.getArguments()) {
                    received.add((ItemStack) given);
                }
                return new HashMap<Integer, ItemStack>();
            });
        }
    }

    private static ItemStack item(String kind, int amount) {
        ItemStack item = mock(ItemStack.class);
        when(item.getAmount()).thenReturn(amount);
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.getType()).thenReturn(Material.STONE);
        when(item.toString()).thenReturn(kind);
        when(item.isSimilar(any())).thenAnswer(call -> call.getArgument(0) != null
                && call.getArgument(0).toString().equals(kind));
        when(item.clone()).thenAnswer(call -> item(kind, amount));
        return item;
    }

    private MemoryStorage storage;
    private AtomicLong now;
    private SendBook book;
    private Messages messages;
    private Server server;
    private SendService service;
    private Person alice;
    private Person bob;

    @BeforeEach
    void setUp() {
        storage = new MemoryStorage();
        now = new AtomicLong(1_000_000);
        book = new SendBook(storage, Runnable::run, now::get);
        messages = mock(Messages.class);
        server = mock(Server.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(false);
        alice = new Person("Alice");
        bob = new Person("Bob");
        when(server.getPlayer(alice.id)).thenReturn(alice.player);
        when(server.getPlayer(bob.id)).thenReturn(bob.player);
        service = new SendService(plugin, server, Log.of("test"), messages, mock(Effects.class), book,
                new SendableItemsRule(stack -> false), now::get, SendsSettings.DEFAULTS.withCooldownSeconds(0));
    }

    @Test
    @DisplayName("a send is not acceptable until it is saved: a failing save cannot also have been delivered")
    void notAcceptableBeforeItIsSaved() {
        java.util.ArrayDeque<Runnable> held = new java.util.ArrayDeque<>();
        book = new SendBook(storage, held::add, now::get);
        service = new SendService(mock(Plugin.class), server, Log.of("test"), messages, mock(Effects.class), book,
                new SendableItemsRule(stack -> false), now::get, SendsSettings.DEFAULTS.withCooldownSeconds(0));
        when(server.getPlayer(alice.id)).thenReturn(alice.player);

        assertThat(service.create(alice.player, bob.id, List.of(item("diamond", 3)))).isTrue();
        assertThat(held).hasSize(1);
        assertThat(book.incoming(bob.id)).as("not listed while the write is on its way").isEmpty();
        service.accept(bob.player, UUID.randomUUID());
        assertThat(bob.received).isEmpty();

        storage.failing = true;
        held.poll().run();
        assertThat(bob.received).isEmpty();
        assertThat(alice.received).as("the sender has them back, and only the sender").hasSize(1);
        assertThat(book.incoming(bob.id)).isEmpty();
    }

    @Test
    @DisplayName("the limit counts sends that are still being saved")
    void limitCountsUnsaved() {
        java.util.ArrayDeque<Runnable> held = new java.util.ArrayDeque<>();
        book = new SendBook(storage, held::add, now::get);
        service = new SendService(mock(Plugin.class), server, Log.of("test"), messages, mock(Effects.class), book,
                new SendableItemsRule(stack -> false), now::get,
                SendsSettings.DEFAULTS.withCooldownSeconds(0).withMostWaiting(1));
        assertThat(service.create(alice.player, bob.id, List.of(item("a", 1)))).isTrue();
        assertThat(service.create(alice.player, bob.id, List.of(item("b", 1)))).isFalse();
    }

    private Send makeOne() {
        assertThat(service.create(alice.player, bob.id, List.of(item("diamond", 3)))).isTrue();
        return book.outgoing(alice.id).getFirst();
    }

    // ------------------------------------------------------------------------------ making

    @Test
    @DisplayName("an offline recipient is refused and the items stay with the caller")
    void offlineRecipient() {
        when(server.getPlayer(bob.id)).thenReturn(null);
        assertThat(service.create(alice.player, bob.id, List.of(item("diamond", 1)))).isFalse();
        verify(messages).send(eq(alice.player), eq("sends.refuse.offline"), any(Object[].class));
        assertThat(book.outgoing(alice.id)).isEmpty();
    }

    @Test
    @DisplayName("a made send is on disk, and both players are told")
    void created() {
        Send send = makeOne();
        assertThat(storage.saved.get(send.id()).state()).isEqualTo(Send.State.PENDING);
        assertThat(send.expiresAt()).isEqualTo(now.get() + 10 * MINUTE);
        verify(messages).send(eq(alice.player), eq("sends.created"), any(Object[].class));
        verify(messages).send(eq(bob.player), eq("sends.incoming"), any(Object[].class));
    }

    @Test
    @DisplayName("a send that cannot be saved never existed: the items go back to the sender")
    void saveFailsOnCreate() {
        storage.failing = true;
        assertThat(service.create(alice.player, bob.id, List.of(item("diamond", 3)))).isTrue();
        assertThat(book.outgoing(alice.id)).isEmpty();
        assertThat(alice.received).hasSize(1);
        verify(messages).send(eq(alice.player), eq("sends.save-failed"));
        verify(messages, never()).send(eq(bob.player), eq("sends.incoming"), any(Object[].class));
    }

    @Test
    @DisplayName("nobody may have more sends waiting than the limit")
    void limit() {
        SendsSettings tight = SendsSettings.DEFAULTS.withCooldownSeconds(0).withMostWaiting(2);
        service.settings(tight);
        makeOne();
        makeOne();
        assertThat(service.create(alice.player, bob.id, List.of(item("diamond", 1)))).isFalse();
        verify(messages).send(eq(alice.player), eq("sends.refuse.limit"), any(Object[].class));
    }

    @Test
    @DisplayName("sending again too soon is refused, and the wait is not restarted by being refused")
    void cooldown() {
        service.settings(SendsSettings.DEFAULTS.withCooldownSeconds(30));
        makeOne();
        assertThat(service.create(alice.player, bob.id, List.of(item("diamond", 1)))).isFalse();
        verify(messages).send(eq(alice.player), eq("sends.refuse.cooldown"), any(Object[].class));
        now.addAndGet(31_000);
        assertThat(service.create(alice.player, bob.id, List.of(item("diamond", 1)))).isTrue();
    }

    // ------------------------------------------------------------------------------ accepting

    @Test
    @DisplayName("accepting gives the items once, writes the end first, and tells the sender")
    void acceptGivesOnce() {
        Send send = makeOne();
        service.accept(bob.player, send.id());
        service.accept(bob.player, send.id());

        assertThat(bob.received).hasSize(1);
        assertThat(storage.saved.get(send.id()).state()).isEqualTo(Send.State.DELIVERED);
        verify(messages).send(eq(alice.player), eq("sends.accepted"), any(Object[].class));
        verify(messages).send(eq(bob.player), eq("sends.received"), any(Object[].class));
        verify(messages, atLeastOnce()).send(eq(bob.player), eq("sends.gone"));
    }

    @Test
    @DisplayName("with no room nothing is taken, and it can be accepted once there is")
    void noRoomThenRoom() {
        Send send = makeOne();
        bob.roomLeft = false;
        service.accept(bob.player, send.id());
        assertThat(bob.received).isEmpty();
        verify(messages).send(eq(bob.player), eq("sends.no-room"), any(Object[].class));
        assertThat(book.incoming(bob.id)).hasSize(1);

        bob.roomLeft = true;
        service.accept(bob.player, send.id());
        assertThat(bob.received).hasSize(1);
    }

    @Test
    @DisplayName("a write that fails on accept gives nothing and leaves the send pending")
    void saveFailsOnAccept() {
        Send send = makeOne();
        storage.failing = true;
        service.accept(bob.player, send.id());
        assertThat(bob.received).isEmpty();
        assertThat(book.incoming(bob.id)).hasSize(1);
        verify(messages).send(eq(bob.player), eq("sends.save-failed"));
    }

    @Test
    @DisplayName("a recipient who left before the write landed does not lose the send")
    void recipientLeftMeanwhile() {
        Send send = makeOne();
        when(bob.player.isOnline()).thenReturn(false);
        service.accept(bob.player, send.id());
        assertThat(bob.received).isEmpty();
        assertThat(book.incoming(bob.id)).extracting(Send::id).containsExactly(send.id());
        assertThat(storage.saved.get(send.id()).state()).isEqualTo(Send.State.PENDING);
    }

    @Test
    @DisplayName("somebody else cannot accept it")
    void strangerCannotAccept() {
        Send send = makeOne();
        Person carol = new Person("Carol");
        service.accept(carol.player, send.id());
        assertThat(carol.received).isEmpty();
        assertThat(book.incoming(bob.id)).hasSize(1);
    }

    @Test
    @DisplayName("past its expiry it cannot be accepted, even before the sweep ran")
    void expiredCannotBeAccepted() {
        Send send = makeOne();
        now.addAndGet(10 * MINUTE);
        service.accept(bob.player, send.id());
        assertThat(bob.received).isEmpty();
        verify(messages).send(eq(bob.player), eq("sends.ran-out-just-now"));
        assertThat(alice.received).hasSize(1);
    }

    // ------------------------------------------------------------------------------ returning

    @Test
    @DisplayName("cancelling gives the items back to the sender and the recipient can no longer accept")
    void cancel() {
        Send send = makeOne();
        service.release(alice.player, send.id());

        assertThat(alice.received).hasSize(1);
        assertThat(storage.saved.get(send.id()).state()).isEqualTo(Send.State.RETURNED);
        verify(messages).send(eq(bob.player), eq("sends.cancelled"), any(Object[].class));
        service.accept(bob.player, send.id());
        assertThat(bob.received).isEmpty();
    }

    @Test
    @DisplayName("a cancel whose write fails cannot be undone after the sender collected: nobody gets it twice")
    void failedCancelCannotDuplicate() {
        java.util.ArrayDeque<Runnable> held = new java.util.ArrayDeque<>();
        Send send = makeOne();
        book = new SendBook(storage, held::add, now::get);
        book.load();
        service = new SendService(mock(Plugin.class), server, Log.of("test"), messages, mock(Effects.class), book,
                new SendableItemsRule(stack -> false), now::get, SendsSettings.DEFAULTS.withCooldownSeconds(0));

        service.release(alice.player, send.id());
        service.collectAll(alice.player, true);
        assertThat(alice.received).as("nothing is given while the cancel is unwritten").isEmpty();
        storage.failing = true;
        held.poll().run();
        service.accept(bob.player, send.id());
        assertThat(bob.received.size() + alice.received.size()).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("declining sends it back and tells the sender")
    void decline() {
        Send send = makeOne();
        service.release(bob.player, send.id());
        assertThat(alice.received).hasSize(1);
        verify(messages).send(eq(alice.player), eq("sends.declined"), any(Object[].class));
    }

    @Test
    @DisplayName("a sender with no room waits for their items, and collects them when there is room")
    void returnWaitsForRoom() {
        Send send = makeOne();
        alice.roomLeft = false;
        service.release(alice.player, send.id());
        assertThat(alice.received).isEmpty();
        assertThat(book.waitingReturn(alice.id)).hasSize(1);
        verify(messages).send(eq(alice.player), eq("sends.returned-no-room"), any(Object[].class));

        alice.roomLeft = true;
        service.collectAll(alice.player, true);
        assertThat(alice.received).hasSize(1);
        assertThat(book.waitingReturn(alice.id)).isEmpty();
    }

    @Test
    @DisplayName("the sweep returns what ran out, once, and says so to both")
    void sweepReturns() {
        makeOne();
        service.sweep();
        assertThat(alice.received).isEmpty();

        now.addAndGet(10 * MINUTE);
        service.sweep();
        service.sweep();
        assertThat(alice.received).hasSize(1);
        verify(messages).send(eq(alice.player), eq("sends.ran-out"), any(Object[].class));
        verify(messages).send(eq(bob.player), eq("sends.ran-out-recipient"), any(Object[].class));
    }

    @Test
    @DisplayName("stopping waits for nothing when nothing is in flight")
    void idle() {
        assertThat(service.awaitIdle(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
}
