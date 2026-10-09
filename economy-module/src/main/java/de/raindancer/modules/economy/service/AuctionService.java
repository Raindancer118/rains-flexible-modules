package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.bossbar.BarPriority;
import de.raindancer.core.ui.bossbar.BarStyle;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.profile.PlayerSwitch;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Auction;
import de.raindancer.modules.economy.model.AuctionBid;
import de.raindancer.modules.economy.model.AuctionClaim;
import de.raindancer.modules.economy.model.AuctionEnd;
import de.raindancer.modules.economy.model.ListingRefusal;
import de.raindancer.modules.economy.rules.AuctionRule;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.store.CashTags;
import de.raindancer.modules.economy.util.PermissionNodes;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The auction house: one auction at a time for the whole server, the rest in a queue. Each is announced in
 * chat with buttons to bid, counted down, shown on a boss bar, and ends with the hammer for everybody to
 * hear. Items and bids are held by the ledger the whole time, so nothing is lost to a restart.
 */
public final class AuctionService implements IEconomyService {

    /** A player's own "tell me about auctions". */
    public static final PlayerSwitch NEWS = new PlayerSwitch("rainseconomy", "auction-news", true);

    private static final String BAR_OWNER = "rainseconomy";
    private static final String BAR_ID = "auction";
    /** A live auction found after a restart gets at least this long, so nobody loses it to the downtime. */
    private static final long RESUMED_MILLIS = 30_000;

    private final Plugin plugin;
    private final Server server;
    private final RainEconomy economy;
    private final AccountBook book;
    private final Messages messages;
    private final Effects effects;
    private final ChatButtons buttons;
    private final BossBars bars;
    private final GameSounds sounds;
    private final LongSupplier clock;
    private final AuctionRule rule = new AuctionRule();
    private final Map<UUID, ItemStack> items = new ConcurrentHashMap<>();
    private volatile EconomySettings settings;

    private volatile SupplyService supply;

    /** The money supply's settings; the shipped ones, which change nothing, until wired. */
    public void supply(SupplyService service) {
        this.supply = service;
    }

    private de.raindancer.modules.economy.SupplySettings supplied() {
        return SupplyService.settingsOf(supply);
    }
    private volatile long nextStartAt;
    private volatile boolean barShown;

    public AuctionService(Plugin plugin, Server server, RainEconomy economy, Messages messages, Effects effects,
                          ChatButtons buttons, BossBars bars, GameSounds sounds, LongSupplier clock,
                          EconomySettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.economy = economy;
        this.book = economy.book();
        this.messages = messages;
        this.effects = effects;
        this.buttons = buttons;
        this.bars = bars;
        this.sounds = sounds;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    public AuctionRule rule() {
        return rule;
    }

    public List<Auction> auctions() {
        return book.auctions();
    }

    /**
     * Moves the player's waiting auction to the front of the queue for the owner's price, which leaves the
     * economy. Without {@code confirmed}, says the price with a button first.
     */
    public void jump(Player player, boolean confirmed) {
        Currency currency = settings.currency();
        Money price = de.raindancer.core.social.economy.EconomyLevers.sink(de.raindancer.modules.economy.model.Sources.AUCTION_JUMP,
                de.raindancer.modules.economy.SupplySettings.money(supplied().auctionJumpPrice(), currency));
        if (!price.isPositive()) {
            messages.send(player, "economy.auction.jump-off");
            return;
        }
        List<Auction> waiting = book.auctions().stream().filter(auction -> !auction.live()).toList();
        Optional<Auction> mine = waiting.stream().skip(1)
                .filter(auction -> auction.seller().equals(player.getUniqueId())).findFirst();
        if (mine.isEmpty()) {
            messages.send(player, "economy.auction.jump-none");
            return;
        }
        if (!confirmed) {
            messages.send(player, "economy.auction.jump-price", "amount", currency.render(price),
                    "button", buttons.label("<green>[Go next]</green>").tooltip("<gray>Pay and go next")
                            .forOnly(player.getUniqueId()).expiringIn(java.time.Duration.ofSeconds(30))
                            .does(clicker -> de.raindancer.core.platform.util.Scheduling.entity(plugin, player,
                                    () -> jump(player, true))).render());
            return;
        }
        de.raindancer.core.social.economy.EconomyResult paid = book.jumpQueue(mine.get().id(), player.getUniqueId(),
                price, economy.most());
        if (!paid.succeeded()) {
            Outcomes.tell(messages, effects, player, paid, currency, "");
            return;
        }
        economy.tell(player.getUniqueId(), price.negate(), paid.balance(), de.raindancer.modules.economy.model.TransactionKind.FEE);
        messages.send(player, "economy.auction.jumped", "item", mine.get().itemName(), "amount", currency.render(price));
    }

    public Optional<Auction> live() {
        return book.liveAuction();
    }

    public List<AuctionClaim> claimsOf(UUID player) {
        return book.claimsOf(player);
    }

    public Money nextMinimum(Auction auction) {
        return rule.nextMinimum(auction, settings.auctionStepMoney(), settings.auctionStepPercent());
    }

    public Money fee(Money price) {
        return rule.fee(price, settings.auctionFee());
    }

    /** The item an auction is for, as an item again — what menus show and chat hovers over. */
    public ItemStack item(Auction auction) {
        return items.computeIfAbsent(auction.id(), id -> {
            try {
                return ItemStack.deserializeBytes(auction.item());
            } catch (RuntimeException unreadable) {
                return ItemStack.of(org.bukkit.Material.BARRIER);
            }
        }).clone();
    }

    /** The item in chat: its name with the full tooltip on hover, and the count in front of a stack. */
    public Component shown(Auction auction) {
        ItemStack item = item(auction);
        Component name = item.displayName();
        return item.getAmount() > 1 ? Component.text(item.getAmount() + " × ").append(name) : name;
    }

    public long secondsLeft(Auction auction) {
        return (auction.millisLeft(clock.getAsLong()) + 999) / 1000;
    }

    /** After the module starts: a live auction the restart cut short gets some time back. */
    public void resume() {
        long now = clock.getAsLong();
        book.liveAuction().ifPresent(live -> {
            if (live.endsAt() - now < RESUMED_MILLIS) {
                book.extendAuction(live.id(), now + RESUMED_MILLIS);
            }
        });
        nextStartAt = now + 10_000;
    }

    // ---------------------------------------------------------------------------- listing

    /**
     * Puts the item in the seller's main hand up for auction.
     *
     * @param buyout zero for none
     * @param seconds zero for the server's default
     */
    public boolean list(Player seller, Money start, Money buyout, int seconds) {
        return list(seller, seller.getInventory().getHeldItemSlot(), null, start, buyout, seconds);
    }

    /**
     * Puts the item in one slot of the seller's inventory up for auction.
     *
     * @param picked what was in that slot when the seller chose it; null to take whatever is there now. If the
     *               slot holds something else by the time they confirm, nothing is listed — a price typed for
     *               one stack must not sell another.
     */
    public boolean list(Player seller, int slot, ItemStack picked, Money start, Money buyout, int seconds) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!open(seller)) {
            return false;
        }
        ItemStack hand = slot < 0 || slot >= seller.getInventory().getSize() ? null : seller.getInventory().getItem(slot);
        if (hand == null || hand.getType().isAir() || hand.getAmount() <= 0) {
            refuse(seller, picked == null ? "economy.auction.empty-hand" : "economy.auction.item-moved");
            return false;
        }
        if (picked != null && (!hand.isSimilar(picked) || hand.getAmount() != picked.getAmount())) {
            refuse(seller, "economy.auction.item-moved");
            return false;
        }
        if (CashTags.isCash(hand)) {
            refuse(seller, "economy.auction.money");
            return false;
        }
        List<Auction> all = book.auctions();
        long mine = all.stream().filter(auction -> auction.seller().equals(seller.getUniqueId())).count();
        Optional<ListingRefusal> refusal = rule.refusal(start, buyout, live.auctionSmallestStartMoney(),
                (int) all.stream().filter(auction -> !auction.live()).count(), live.auctionQueueSize(), (int) mine,
                live.auctionsPerPlayer());
        if (refusal.isPresent()) {
            switch (refusal.get()) {
                case START_TOO_LOW -> refuse(seller, "economy.auction.start-too-low",
                        "amount", currency.render(live.auctionSmallestStartMoney()));
                case BUYOUT_BELOW_START -> refuse(seller, "economy.auction.buyout-below");
                case QUEUE_FULL -> refuse(seller, "economy.auction.queue-full",
                        "count", String.valueOf(live.auctionQueueSize()));
                case TOO_MANY -> refuse(seller, "economy.auction.too-many",
                        "count", String.valueOf(live.auctionsPerPlayer()));
            }
            return false;
        }
        int length = rule.seconds(seconds, live.auctionMinSeconds(), Math.max(live.auctionMinSeconds(),
                live.auctionMaxSeconds()), live.auctionDefaultSeconds());
        ItemStack taken = hand.clone();
        String name = PlainTextComponentSerializer.plainText().serialize(taken.effectiveName());
        if (taken.getAmount() > 1) {
            name = taken.getAmount() + " × " + name;
        }
        economy.open(seller.getUniqueId(), seller.getName());
        Auction auction = Auction.listed(UUID.randomUUID(), seller.getUniqueId(), seller.getName(),
                taken.serializeAsBytes(), name, start, buyout, length, clock.getAsLong());
        Money listingFee = live.auctionListingFeeMoney();
        EconomyResult paid = book.listAuction(auction, listingFee, economy.most(), live.auctionQueueSize(),
                live.auctionsPerPlayer());
        if (!paid.succeeded()) {
            Outcomes.tell(messages, effects, seller, paid, currency, "");
            return false;
        }
        // Listed first, taken second, on the seller's own thread: nothing can move the item in between.
        seller.getInventory().setItem(slot, null);
        items.put(auction.id(), taken);
        Scheduling.async(plugin, book::flush);
        if (listingFee.isPositive()) {
            economy.tell(seller.getUniqueId(), listingFee.negate(), paid.balance(),
                    de.raindancer.modules.economy.model.TransactionKind.AUCTION);
        }
        int place = (int) book.auctions().stream().filter(waiting -> !waiting.live()).count();
        boolean running = book.liveAuction().isPresent();
        messages.send(seller, running || place > 1 ? "economy.auction.queued" : "economy.auction.next",
                "item", shown(auction), "place", String.valueOf(place),
                "fee", currency.render(listingFee), "duration", Times.describe(Duration.ofSeconds(length)));
        effects.play(seller.getUniqueId(), Cues.OK);
        return true;
    }

    // ---------------------------------------------------------------------------- bidding

    /**
     * A bid on the live auction; without an amount, the smallest one accepted.
     *
     * @param meant the start of the id of the auction the bidder was looking at — a chat button or a window
     *              from before must not bid on the auction that came after; null for whatever is running
     */
    public boolean bid(Player bidder, Money wanted, String meant) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!open(bidder)) {
            return false;
        }
        Optional<Auction> running = book.liveAuction();
        if (running.isEmpty()) {
            refuse(bidder, "economy.auction.none");
            return false;
        }
        Auction auction = running.get();
        if (meant != null && !auction.id().toString().startsWith(meant)) {
            refuse(bidder, "economy.auction.over");
            return false;
        }
        Money amount = rule.capped(auction, wanted == null ? nextMinimum(auction) : wanted);
        boolean buyout = auction.hasBuyout() && amount.isAtLeast(auction.buyout());
        long now = clock.getAsLong();
        economy.open(bidder.getUniqueId(), bidder.getName());
        AuctionBid result = book.bid(auction.id(), bidder.getUniqueId(), bidder.getName(), amount,
                this::nextMinimum, end -> buyout ? now : rule.endAfterBid(end, now, live.auctionSnipeSeconds()),
                now, economy.most());
        switch (result.kind()) {
            case GONE -> refuse(bidder, "economy.auction.none");
            case OWN -> refuse(bidder, "economy.auction.own");
            case TOP -> refuse(bidder, "economy.auction.top");
            case TOO_LOW -> refuse(bidder, "economy.auction.too-low",
                    "amount", currency.render(nextMinimum(result.auction())));
            case REFUSED -> Outcomes.tell(messages, effects, bidder, result.payment(), currency, "");
            case PLACED -> placed(bidder, auction, result, buyout);
        }
        return result.placed();
    }

    private void placed(Player bidder, Auction before, AuctionBid result, boolean buyout) {
        Currency currency = settings.currency();
        Auction after = result.auction();
        economy.tell(bidder.getUniqueId(), after.bid().negate(), result.payment().balance(),
                de.raindancer.modules.economy.model.TransactionKind.AUCTION);
        if (result.outbid() != null) {
            economy.tell(result.outbid(), result.refunded(), economy.balance(result.outbid()),
                    de.raindancer.modules.economy.model.TransactionKind.AUCTION);
            Player outbid = server.getPlayer(result.outbid());
            if (outbid != null) {
                Money next = nextMinimum(after);
                messages.send(outbid, "economy.auction.outbid", "item", shown(after),
                        "player", bidder.getName(), "amount", currency.render(after.bid()),
                        "refunded", currency.render(result.refunded()),
                        "buttons", buttons.row(bidButton(after, next)));
                sounds.play(outbid.getUniqueId(), GameSounds.OUTBID);
            }
        }
        if (buyout) {
            return;
        }
        if (settings.auctionAnnounceBids()) {
            Component row = buttons.row(bidButton(after, nextMinimum(after)));
            for (Player listener : audience()) {
                messages.send(listener, "economy.auction.bid", "player", bidder.getName(), "item", shown(after),
                        "amount", currency.render(after.bid()), "buttons", listener.equals(bidder) ? Component.empty() : row);
                sounds.play(listener.getUniqueId(), GameSounds.BID);
            }
        } else {
            messages.send(bidder, "economy.auction.you-bid", "item", shown(after), "amount", currency.render(after.bid()));
            sounds.play(bidder.getUniqueId(), GameSounds.BID);
        }
    }

    /** The short id chat buttons carry, so a click lands on the auction it was shown for. */
    public static String shortId(Auction auction) {
        return auction.id().toString().substring(0, 8);
    }

    private de.raindancer.core.ui.chat.ChatButton bidButton(Auction auction, Money amount) {
        return buttons.label("<green>[Bid " + de.raindancer.modules.economy.util.Mini.of(settings.currency()
                        .render(amount)) + "<green>]")
                .tooltip("<gray>Bid the smallest amount accepted now")
                .runs("/auction bid " + amount.minor() + " " + shortId(auction));
    }

    // ---------------------------------------------------------------------------- calling off

    /** The seller takes back an auction of theirs: any queued one, the live one only before its first bid. */
    public boolean withdraw(Player seller, UUID id) {
        Optional<Auction> found = book.auction(id).filter(auction -> auction.seller().equals(seller.getUniqueId()));
        if (found.isEmpty()) {
            refuse(seller, "economy.auction.not-yours");
            return false;
        }
        if (found.get().live() && found.get().hasBid()) {
            refuse(seller, "economy.auction.has-bids");
            return false;
        }
        return callOff(id);
    }

    /** Staff calling an auction off, bids or not. */
    public boolean callOff(UUID id) {
        Optional<AuctionEnd> ended = book.cancelAuction(id);
        ended.ifPresent(end -> {
            if (end.auction().live()) {
                broadcast("economy.auction.called-off", "item", shown(end.auction()));
                nextStartAt = clock.getAsLong() + settings.auctionGapSeconds() * 1000L;
                clearBar();
            }
            if (end.auction().hasBid()) {
                UUID bidder = end.auction().bidder();
                economy.tell(bidder, end.auction().bid(), economy.balance(bidder),
                        de.raindancer.modules.economy.model.TransactionKind.AUCTION);
            }
            Player seller = server.getPlayer(end.auction().seller());
            if (seller != null) {
                messages.send(seller, "economy.auction.withdrawn", "item", shown(end.auction()));
                deliver(seller);
            }
            items.remove(end.auction().id());
            Scheduling.async(plugin, book::flush);
        });
        return ended.isPresent();
    }

    /** Everything off: the live auction and the whole queue go back to their sellers. */
    public int callOffAll() {
        int count = 0;
        for (Auction auction : book.auctions()) {
            if (callOff(auction.id())) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------------------- the clock

    /** Once a second, on the global thread. */
    public void tick() {
        if (!book.isLoaded()) {
            return;
        }
        EconomySettings live = settings;
        long now = clock.getAsLong();
        Optional<Auction> running = book.liveAuction();
        if (!live.auctionsEnabled()) {
            if (running.isPresent()) {
                callOff(running.get().id());
            }
            clearBar();
            return;
        }
        if (running.isEmpty()) {
            clearBar();
            if (now >= nextStartAt) {
                book.startNextAuction(now).ifPresent(this::announceStart);
            }
            return;
        }
        Auction auction = running.get();
        if (auction.millisLeft(now) <= 0 || rule.boughtOut(auction)) {
            finish(auction);
            return;
        }
        showBar(auction, now);
    }

    private void announceStart(Auction auction) {
        Currency currency = settings.currency();
        Component row = buttons.row(bidButton(auction, auction.start()),
                buttons.label("<yellow>[Look at it]").tooltip("<gray>Open the auction").runs("/auction"));
        String terms = auction.hasBuyout() ? "economy.auction.terms-buyout" : "economy.auction.terms";
        for (Player listener : audience()) {
            messages.send(listener, "economy.auction.banner");
            messages.send(listener, "economy.auction.started", "player", auction.sellerName(), "item", shown(auction));
            messages.send(listener, terms, "start", currency.render(auction.start()),
                    "buyout", currency.render(auction.buyout()),
                    "duration", Times.describe(Duration.ofSeconds(auction.seconds())),
                    "buttons", listener.getUniqueId().equals(auction.seller()) ? Component.empty() : row);
            sounds.play(listener.getUniqueId(), GameSounds.AUCTION_START);
        }
    }

    private void finish(Auction auction) {
        Currency currency = settings.currency();
        long now = clock.getAsLong();
        Optional<AuctionEnd> ended = book.endAuction(auction.id(), this::fee, now);
        if (ended.isEmpty()) {
            // A last bid moved the end after this tick looked; the next tick sees it.
            return;
        }
        clearBar();
        nextStartAt = now + settings.auctionGapSeconds() * 1000L;
        AuctionEnd end = ended.get();
        Scheduling.async(plugin, book::flush);
        if (!end.sold()) {
            broadcast("economy.auction.unsold", "item", shown(auction), "player", auction.sellerName());
            Player seller = server.getPlayer(auction.seller());
            if (seller != null) {
                deliver(seller);
            }
            items.remove(auction.id());
            return;
        }
        economy.tell(auction.seller(), end.paid(), economy.balance(auction.seller()),
                de.raindancer.modules.economy.model.TransactionKind.AUCTION);
        for (Player listener : audience()) {
            messages.send(listener, "economy.auction.sold", "player", auction.bidderName(), "item", shown(auction),
                    "amount", currency.render(auction.bid()), "seller", auction.sellerName());
            sounds.play(listener.getUniqueId(), GameSounds.SOLD);
        }
        Player seller = server.getPlayer(auction.seller());
        if (seller != null) {
            messages.send(seller, "economy.auction.sold-seller", "item", shown(auction),
                    "amount", currency.render(auction.bid()), "paid", currency.render(end.paid()),
                    "fee", currency.render(end.fee()));
            seller.showTitle(net.kyori.adventure.title.Title.title(messages.get("economy.auction.title-sold"),
                    messages.get("economy.auction.subtitle-sold", "amount", currency.render(end.paid()))));
            effects.play(seller.getUniqueId(), GameSounds.CASH);
        }
        Player winner = server.getPlayer(auction.bidder());
        if (winner != null) {
            winner.showTitle(net.kyori.adventure.title.Title.title(messages.get("economy.auction.title-won"),
                    messages.get("economy.auction.subtitle-won", "item", shown(auction))));
            sounds.play(winner.getUniqueId(), GameSounds.JACKPOT);
            deliver(winner);
        }
        items.remove(auction.id());
    }

    // ---------------------------------------------------------------------------- the boss bar

    private void showBar(Auction auction, long now) {
        if (!settings.auctionBossBar()) {
            clearBar();
            return;
        }
        Currency currency = settings.currency();
        long left = secondsLeft(auction);
        Component text = auction.hasBid()
                ? messages.get("economy.auction.bar", "item", shown(auction), "amount", currency.render(auction.bid()),
                "player", auction.bidderName(), "time", Times.describe(Duration.ofSeconds(left)))
                : messages.get("economy.auction.bar-no-bid", "item", shown(auction),
                "amount", currency.render(auction.start()), "time", Times.describe(Duration.ofSeconds(left)));
        float total = Math.max(1, auction.seconds()) * 1000f;
        float progress = Math.min(1f, auction.millisLeft(now) / total);
        BossBar.Color colour = left <= 10 ? BossBar.Color.RED : auction.hasBid() ? BossBar.Color.YELLOW
                : BossBar.Color.WHITE;
        List<UUID> watching = audience().stream().map(Player::getUniqueId).toList();
        bars.showShared(BAR_OWNER, BAR_ID, watching, new BarStyle(text, progress, colour, BossBar.Overlay.NOTCHED_10),
                BarPriority.LOW);
        barShown = true;
    }

    private void clearBar() {
        if (barShown) {
            bars.clearShared(BAR_OWNER, BAR_ID);
            barShown = false;
        }
    }

    // ---------------------------------------------------------------------------- items owed

    /**
     * Hands over what the auction house holds for this player, as far as their inventory has room. Each
     * item is only marked as handed over on the player's own thread, right where it goes into the inventory.
     */
    public void deliver(Player player) {
        List<AuctionClaim> owed = book.claimsOf(player.getUniqueId());
        if (owed.isEmpty()) {
            return;
        }
        Scheduling.entity(plugin, player, () -> {
            int given = 0;
            for (AuctionClaim claim : owed) {
                if (player.getInventory().firstEmpty() < 0) {
                    messages.send(player, "economy.auction.no-room", "count", String.valueOf(owed.size() - given));
                    break;
                }
                ItemStack item;
                try {
                    item = ItemStack.deserializeBytes(claim.item());
                } catch (RuntimeException unreadable) {
                    continue;
                }
                if (!book.takeClaim(claim.id())) {
                    continue;
                }
                player.getInventory().addItem(item).values()
                        .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
                messages.send(player, "economy.auction.delivered", "item", item.displayName());
                given++;
            }
            if (given > 0) {
                effects.play(player.getUniqueId(), Cues.OK);
                Scheduling.async(plugin, book::flush);
            }
        });
    }

    /** Somebody joined: what is waiting for them, and the auction running right now. */
    public void joined(Player player) {
        int owed = book.claimsOf(player.getUniqueId()).size();
        if (owed > 0) {
            messages.send(player, "economy.auction.waiting", "count", String.valueOf(owed),
                    "buttons", buttons.row(buttons.label("<yellow>[Pick up]").tooltip("<gray>Into your inventory")
                            .runs("/auction claim")));
        }
        book.liveAuction().ifPresent(auction -> {
            if (settings.auctionsEnabled() && NEWS.isOn(player)) {
                messages.send(player, "economy.auction.running", "item", shown(auction),
                        "seconds", String.valueOf(secondsLeft(auction)),
                        "buttons", buttons.row(buttons.label("<yellow>[Look at it]").runs("/auction")));
            }
        });
    }

    public boolean toggleNews(Player player) {
        return NEWS.toggle(player);
    }

    // ---------------------------------------------------------------------------- telling

    private boolean open(Player player) {
        if (!settings.auctionsEnabled()) {
            refuse(player, "economy.auction.off");
            return false;
        }
        if (!player.hasPermission(PermissionNodes.AUCTION)) {
            refuse(player, "economy.auction.not-allowed");
            return false;
        }
        if (!book.isLoaded()) {
            refuse(player, "economy.unavailable");
            return false;
        }
        return true;
    }

    /** Everybody online who has not muted auctions. */
    private List<Player> audience() {
        List<Player> listening = new ArrayList<>();
        for (Player player : server.getOnlinePlayers()) {
            if (NEWS.isOn(player)) {
                listening.add(player);
            }
        }
        return listening;
    }

    private void broadcast(String key, Object... values) {
        audience().forEach(player -> messages.send(player, key, values));
    }

    private void refuse(Player player, String key, Object... values) {
        messages.send(player, key, values);
        effects.play(player.getUniqueId(), Cues.NO);
    }
}
