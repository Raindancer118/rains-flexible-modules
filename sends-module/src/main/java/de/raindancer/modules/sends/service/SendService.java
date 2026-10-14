package de.raindancer.modules.sends.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Cooldowns;
import de.raindancer.core.platform.util.Inventories;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.sends.SendsSettings;
import de.raindancer.modules.sends.model.Send;
import de.raindancer.modules.sends.rules.RecipientRule;
import de.raindancer.modules.sends.rules.SendLimitRule;
import de.raindancer.modules.sends.rules.SendableItemsRule;
import de.raindancer.modules.sends.store.SendBook;
import de.raindancer.modules.sends.store.SendStorage;
import de.raindancer.modules.sends.util.ItemSummary;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Making, accepting, declining, cancelling and returning sends.
 *
 * <h2>The order of every hand-over</h2>
 * The book changes the send in memory (and that is the one decision — see {@link SendBook}), the change is
 * written to disk, and only when that has answered true are the items given to anybody. A crash in between
 * therefore leaves the send as it was on disk and the items still held, never given twice; a failed write is
 * undone and said out loud. If the receiving player has gone by the time the write lands, the change is undone
 * as well rather than giving items to somebody who is no longer there.
 *
 * <h2>On a network</h2>
 * The write is a conditional update in the database every server shares, so of two servers ending the same send —
 * a recipient accepting on one while the sender cancels on another — exactly one is told {@code DONE} and hands
 * anything over; the other reloads the send and says it is gone. Whoever the other party is, wherever they are,
 * is told through {@link Network}; a send returning to a sender on another server is collected by that server
 * when it hears of it.
 */
public final class SendService implements ISendsService {

    private static final int KINDS_IN_A_LINE = 3;

    private final Plugin plugin;
    private final Server server;
    private final LogChannel log;
    private final Messages messages;
    private final Effects effects;
    private final SendBook book;
    private final LongSupplier clock;
    private final RecipientRule recipientRule = new RecipientRule();
    private final SendLimitRule limitRule = new SendLimitRule();
    private final SendableItemsRule itemsRule;
    private final Cooldowns<UUID> cooldown;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile SendsSettings settings;
    private volatile Network network;

    /** What sending needs of the other servers of a network. */
    public interface Network {
        /** The name of somebody who is online on another server, by presence; empty when nobody is. */
        Optional<String> onlineElsewhere(UUID player);

        /** Says a message to somebody on whichever server they are on; false when they are nowhere. */
        boolean tell(UUID player, String key, String... values);

        /** Tells the other servers this send changed, once the change is committed. */
        void changed(UUID send);
    }

    /** Joins the network: recipients on other servers can be sent to, and answers are heard from them. */
    public void connectNetwork(Network network) {
        this.network = network;
    }

    /** On the live server: the wait between sends follows the player across the network. */
    public static SendService live(Plugin plugin, Server server, LogChannel log, Messages messages, Effects effects,
                                   SendBook book, SendableItemsRule itemsRule, SendsSettings settings) {
        return new SendService(plugin, server, log, messages, effects, book, itemsRule, System::currentTimeMillis,
                settings, Cooldowns.shared("sends:send"));
    }

    public SendService(Plugin plugin, Server server, LogChannel log, Messages messages, Effects effects,
                       SendBook book, SendableItemsRule itemsRule, LongSupplier clock, SendsSettings settings) {
        this(plugin, server, log, messages, effects, book, itemsRule, clock, settings, new Cooldowns<>(clock));
    }

    private SendService(Plugin plugin, Server server, LogChannel log, Messages messages, Effects effects,
                        SendBook book, SendableItemsRule itemsRule, LongSupplier clock, SendsSettings settings,
                        Cooldowns<UUID> cooldown) {
        this.plugin = plugin;
        this.server = server;
        this.log = log;
        this.messages = messages;
        this.effects = effects;
        this.book = book;
        this.itemsRule = itemsRule;
        this.clock = clock;
        this.cooldown = cooldown;
        settings(settings);
    }

    @Override
    public void settings(SendsSettings settings) {
        this.settings = settings;
        cooldown.every(Duration.ofSeconds(Math.max(0, settings.cooldownSeconds())));
    }

    // ------------------------------------------------------------------------------ making

    /**
     * Makes a send out of what the sender put in the window.
     *
     * @return whether the items were taken on. False leaves them with the caller — in the window — and has told
     *         the sender why
     */
    public boolean create(Player sender, UUID recipientId, List<ItemStack> items) {
        Player recipient = server.getPlayer(recipientId);
        boolean here = recipient != null && recipient.isOnline();
        Optional<String> elsewhere = here || network == null ? Optional.empty() : network.onlineElsewhere(recipientId);
        String recipientName = here ? recipient.getName() : elsewhere.orElse("");
        Optional<String> refusal = recipientRule
                .refusal(sender.getUniqueId(), recipientId, here || elsewhere.isPresent())
                .or(() -> limitRule.refusal(book.outgoingCount(sender.getUniqueId()),
                        settings.mostWaitingClamped()))
                .or(() -> itemsRule.refusal(items));
        if (refusal.isPresent()) {
            messages.send(sender, refusal.get(), "player", recipientName,
                    "most", String.valueOf(settings.mostWaitingClamped()));
            effects.play(sender.getUniqueId(), Cues.NO);
            return false;
        }
        if (!cooldown.tryUse(sender.getUniqueId())) {
            long seconds = cooldown.remaining(sender.getUniqueId()).map(left -> left.toSeconds() + 1).orElse(1L);
            messages.send(sender, "sends.refuse.cooldown", "seconds", String.valueOf(seconds));
            effects.play(sender.getUniqueId(), Cues.NO);
            return false;
        }

        // Copies: what the window handed over can be live views of the game's own stacks, and a send must hold
        // items nothing else can change.
        List<ItemStack> held = items.stream().map(ItemStack::clone).toList();
        Send send = Send.create(sender.getUniqueId(), sender.getName(), recipientId, recipientName, held,
                clock.getAsLong(), settings.lifetimeMillis());
        book.add(send);
        inFlight.incrementAndGet();
        book.persist(send).thenAccept(saved -> {
            try {
                if (saved && book.publish(send.id())) {
                    changed(send.id());
                    announce(send);
                } else {
                    book.discard(send.id());
                    log.error("A send from {} to {} could not be saved; the items go back to the sender.",
                            send.senderName(), send.recipientName());
                    giveBackToSender(send, "sends.save-failed");
                }
            } finally {
                inFlight.decrementAndGet();
            }
        });
        return true;
    }

    private void announce(Send send) {
        String items = ItemSummary.describeItems(send.items(), KINDS_IN_A_LINE);
        String minutes = String.valueOf(minutes(send.millisLeft(clock.getAsLong())));
        if (tell(send.sender(), "sends.created", "player", send.recipientName(), "items", items,
                "minutes", minutes, "id", send.shortId())) {
            effects.play(send.sender(), Cues.OK);
        }
        if (tell(send.recipient(), "sends.incoming", "player", send.senderName(), "items", items,
                "minutes", minutes, "id", send.shortId())) {
            effects.play(send.recipient(), Cues.NOTIFY);
        }
    }

    /**
     * Says something to a player of this server, or — on a network — to wherever they are.
     *
     * @return whether they are on this server, which is where a sound can be played for them
     */
    private boolean tell(UUID who, String key, String... values) {
        Player here = server.getPlayer(who);
        if (here != null) {
            messages.send(here, key, (Object[]) values);
            return true;
        }
        Network net = network;
        if (net != null) {
            net.tell(who, key, values);
        }
        return false;
    }

    private void changed(UUID id) {
        Network net = network;
        if (net != null) {
            net.changed(id);
        }
    }

    /**
     * A send was lost to another server: this server's picture of it was wrong, so it reads the database's and
     * says nothing happened here.
     */
    private void lost(UUID id) {
        book.unlock(id);
        book.reload(id);
    }

    /**
     * Another server changed a send. A send that has come back to somebody on this server is theirs to take
     * now, whoever ended it.
     */
    public void changedElsewhere(UUID id) {
        book.reload(id).filter(send -> send.state() == Send.State.RETURNING).ifPresent(this::returnToSender);
    }

    // ------------------------------------------------------------------------------ accepting

    public void accept(Player player, UUID id) {
        SendBook.Result result = book.claim(id, player.getUniqueId(), send -> Inventories.fits(player, send.items()));
        switch (result.outcome()) {
            case DONE -> handOver(player, result, send -> {
                if (tell(send.sender(), "sends.accepted", "player", send.recipientName())) {
                    effects.play(send.sender(), Cues.OK);
                }
                return "sends.received";
            });
            case NO_ROOM -> {
                messages.send(player, "sends.no-room", "left", left(result.send()));
                effects.play(player.getUniqueId(), Cues.NO);
            }
            case EXPIRED -> {
                sweep();
                messages.send(player, "sends.ran-out-just-now");
                effects.play(player.getUniqueId(), Cues.NO);
            }
            default -> {
                messages.send(player, "sends.gone");
                effects.play(player.getUniqueId(), Cues.NO);
            }
        }
    }

    /** Declining, as the recipient, or cancelling, as the sender: either way it goes back to the sender. */
    public void release(Player by, UUID id) {
        SendBook.Result result = book.release(id, by.getUniqueId());
        if (!result.done()) {
            messages.send(by, "sends.gone");
            effects.play(by.getUniqueId(), Cues.NO);
            return;
        }
        Send returning = result.send();
        inFlight.incrementAndGet();
        book.persistChange(result.before(), returning).thenAccept(written -> {
            try {
                if (written == SendStorage.Written.LOST) {
                    lost(returning.id());
                    messages.send(by, "sends.gone");
                    return;
                }
                if (written == SendStorage.Written.FAILED) {
                    book.revert(result.before());
                    messages.send(by, "sends.save-failed");
                    return;
                }
                book.unlock(returning.id());
                changed(returning.id());
                boolean cancelled = by.getUniqueId().equals(returning.sender());
                if (cancelled) {
                    messages.send(by, "sends.cancelled-by-you");
                    tell(returning.recipient(), "sends.cancelled", "player", returning.senderName());
                } else {
                    messages.send(by, "sends.declined-by-you", "player", returning.senderName());
                    tell(returning.sender(), "sends.declined", "player", returning.recipientName());
                }
                returnToSender(returning);
            } finally {
                inFlight.decrementAndGet();
            }
        });
    }

    // ------------------------------------------------------------------------------ returning

    /** Gives a player what has come back to them, as far as it fits. */
    public void collectAll(Player sender, boolean sayIfNoRoom) {
        for (Send send : book.waitingReturn(sender.getUniqueId())) {
            collect(sender, send.id(), sayIfNoRoom);
        }
    }

    public void collect(Player sender, UUID id, boolean sayIfNoRoom) {
        SendBook.Result result = book.collect(id, sender.getUniqueId(), send -> Inventories.fits(sender, send.items()));
        switch (result.outcome()) {
            case DONE -> handOver(sender, result, send -> "sends.returned");
            case NO_ROOM -> {
                if (sayIfNoRoom) {
                    messages.send(sender, "sends.returned-no-room", "player", result.send().recipientName());
                }
            }
            default -> {
                // Already collected, or not theirs: nothing to say.
            }
        }
    }

    private void returnToSender(Send returning) {
        Player sender = server.getPlayer(returning.sender());
        if (sender != null) {
            Scheduling.entity(plugin, sender, () -> collect(sender, returning.id(), true), () -> { });
        }
    }

    /** Runs when the clock may have moved a send past its expiry. */
    public void sweep() {
        for (Send ran : book.expire()) {
            inFlight.incrementAndGet();
            Send before = ran.withState(Send.State.PENDING, 0);
            book.persistChange(before, ran).thenAccept(written -> {
                try {
                    if (written == SendStorage.Written.LOST) {
                        // Another server ran it out first, and has told the two of them.
                        lost(ran.id());
                        return;
                    }
                    if (written == SendStorage.Written.FAILED) {
                        log.error("A send that ran out could not be saved as such; it will run out again at the "
                                + "next sweep.");
                        book.revert(before);
                        return;
                    }
                    book.unlock(ran.id());
                    changed(ran.id());
                    tell(ran.sender(), "sends.ran-out", "player", ran.recipientName());
                    tell(ran.recipient(), "sends.ran-out-recipient", "player", ran.senderName());
                    returnToSender(ran);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    // ------------------------------------------------------------------------------ handing over

    /**
     * Gives the items of a change the book has made, once the change is on disk.
     *
     * @param afterwards runs on the player's thread just before the items are given, returns the key of the line
     *                   that tells the player what they got
     */
    private void handOver(Player to, SendBook.Result result,
                          java.util.function.Function<Send, String> afterwards) {
        Send done = result.send();
        inFlight.incrementAndGet();
        book.persistChange(result.before(), done).thenAccept(written -> {
            if (written == SendStorage.Written.LOST) {
                // Another server ended it first — a cancel, a decline, the clock — and it is theirs to hand over.
                lost(done.id());
                inFlight.decrementAndGet();
                messages.send(to, "sends.gone");
                return;
            }
            if (written == SendStorage.Written.FAILED) {
                book.revert(result.before());
                inFlight.decrementAndGet();
                messages.send(to, "sends.save-failed");
                return;
            }
            changed(done.id());
            // Exactly one of the two ends runs, whatever the scheduler does: giving twice or undoing twice
            // would each be a bug, and the counter must come down once.
            AtomicBoolean ended = new AtomicBoolean();
            Scheduling.entity(plugin, to, () -> {
                if (!ended.compareAndSet(false, true)) {
                    return;
                }
                try {
                    if (!to.isOnline()) {
                        undo(result.before(), done);
                        return;
                    }
                    String key = afterwards.apply(done);
                    int dropped = Inventories.giveOrDrop(to, done.items());
                    messages.send(to, key, "player", to.getUniqueId().equals(done.sender())
                                    ? done.recipientName() : done.senderName(),
                            "items", ItemSummary.describeItems(done.items(), KINDS_IN_A_LINE));
                    if (dropped > 0) {
                        messages.send(to, "sends.dropped", "count", String.valueOf(dropped));
                    }
                    effects.play(to.getUniqueId(), Cues.OK);
                } finally {
                    inFlight.decrementAndGet();
                }
            }, () -> {
                if (!ended.compareAndSet(false, true)) {
                    return;
                }
                try {
                    undo(result.before(), done);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        });
    }

    /** The receiver was gone before the items could be given: the send is as it was, on disk too. */
    private void undo(Send before, Send done) {
        book.revert(before);
        book.persistChange(done, before).thenAccept(written -> {
            if (written == SendStorage.Written.DONE) {
                changed(before.id());
            }
        });
    }

    private void giveBackToSender(Send send, String why) {
        Player sender = server.getPlayer(send.sender());
        if (sender == null) {
            log.error("The items of an unsaved send from {} could not be returned because they left: {}",
                    send.senderName(), ItemSummary.describeItems(send.items(), 50));
            return;
        }
        Scheduling.entity(plugin, sender, () -> {
            Inventories.giveOrDrop(sender, send.items());
            messages.send(sender, why);
        }, () -> log.error("The items of an unsaved send from {} could not be returned because they left: {}",
                send.senderName(), ItemSummary.describeItems(send.items(), 50)));
    }

    // ------------------------------------------------------------------------------ asking

    public SendBook book() {
        return book;
    }

    /** Waits for the writes and hand-overs still on their way; for stopping the module. */
    public boolean awaitIdle(long timeout, TimeUnit unit) {
        long until = System.nanoTime() + unit.toNanos(timeout);
        while (inFlight.get() > 0 && System.nanoTime() < until) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return inFlight.get() == 0;
    }

    private static long minutes(long millis) {
        return Math.max(1, (millis + 59_999) / 60_000);
    }

    /** How long is left, as a player would say it: "7m 12s". */
    public String left(Send send) {
        long seconds = (send.millisLeft(clock.getAsLong()) + 999) / 1000;
        return seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
    }
}
