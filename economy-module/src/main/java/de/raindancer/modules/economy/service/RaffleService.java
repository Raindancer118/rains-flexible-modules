package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Raffle;
import de.raindancer.modules.economy.model.RaffleBuy;
import de.raindancer.modules.economy.model.RaffleDraw;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.rules.RaffleRule;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.store.CashTags;
import de.raindancer.modules.economy.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Raffles: a player puts up an item, or staff put up money; tickets sell until the raffle ends; then one is
 * drawn, with a drumroll for everybody. Several can run at once. Announced when they start and when they are
 * drawn, nothing in between. The prize and every ticket's money are held by the ledger until the draw.
 */
public final class RaffleService implements IEconomyService {

    /** Ticks between "the draw for …" and who won. */
    private static final long DRUMROLL_TICKS = 60L;

    private final Plugin plugin;
    private final Server server;
    private final RainEconomy economy;
    private final AccountBook book;
    private final Messages messages;
    private final Effects effects;
    private final ChatButtons buttons;
    private final GameSounds sounds;
    private final AuctionService auctions;
    private final LongSupplier clock;
    private final RaffleRule rule = new RaffleRule();
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, ItemStack> items = new ConcurrentHashMap<>();
    private volatile EconomySettings settings;

    public RaffleService(Plugin plugin, Server server, RainEconomy economy, Messages messages, Effects effects,
                         ChatButtons buttons, GameSounds sounds, AuctionService auctions, LongSupplier clock,
                         EconomySettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.economy = economy;
        this.book = economy.book();
        this.messages = messages;
        this.effects = effects;
        this.buttons = buttons;
        this.sounds = sounds;
        this.auctions = auctions;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    public RaffleRule rule() {
        return rule;
    }

    public List<Raffle> raffles() {
        return book.raffles();
    }

    public Optional<Raffle> number(int number) {
        return book.raffleNumber(number);
    }

    /** What the raffle is for, as an item: the prize item, or a block of gold for money. */
    public ItemStack prize(Raffle raffle) {
        if (raffle.moneyPrize()) {
            return ItemStack.of(Material.GOLD_BLOCK);
        }
        return items.computeIfAbsent(raffle.id(), id -> {
            try {
                return ItemStack.deserializeBytes(raffle.item());
            } catch (RuntimeException unreadable) {
                return ItemStack.of(Material.BARRIER);
            }
        }).clone();
    }

    /** The prize in chat: the item with its tooltip on hover, or the money in the currency's colours. */
    public Component shown(Raffle raffle) {
        if (raffle.moneyPrize()) {
            return settings.currency().render(raffle.prize());
        }
        ItemStack item = prize(raffle);
        Component name = item.displayName();
        return item.getAmount() > 1 ? Component.text(item.getAmount() + " × ").append(name) : name;
    }

    public Duration left(Raffle raffle) {
        return Duration.ofMillis(Math.max(0, raffle.endsAt() - clock.getAsLong()));
    }

    public Money fee(Money pot) {
        return rule.fee(pot, settings.raffleFeePercent());
    }

    // ---------------------------------------------------------------------------- starting

    /**
     * Raffles off the item in the host's hand.
     *
     * @param minutes     zero for the server's default
     * @param mostTickets zero for no limit
     * @param perPlayer   zero for no limit
     */
    public boolean start(Player host, Money ticketPrice, int minutes, int mostTickets, int perPlayer) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!open(host)) {
            return false;
        }
        ItemStack hand = host.getInventory().getItemInMainHand();
        if (hand.getType().isAir() || hand.getAmount() <= 0) {
            refuse(host, "economy.raffle.empty-hand");
            return false;
        }
        if (CashTags.isCash(hand)) {
            refuse(host, "economy.raffle.money");
            return false;
        }
        if (!roomFor(host, ticketPrice)) {
            return false;
        }
        long hosting = book.raffles().stream().filter(raffle -> host.getUniqueId().equals(raffle.host())).count();
        if (hosting >= live.rafflesPerHost()) {
            refuse(host, "economy.raffle.hosting", "count", String.valueOf(live.rafflesPerHost()));
            return false;
        }
        ItemStack taken = hand.clone();
        String name = PlainTextComponentSerializer.plainText().serialize(taken.effectiveName());
        if (taken.getAmount() > 1) {
            name = taken.getAmount() + " × " + name;
        }
        long now = clock.getAsLong();
        int length = length(minutes);
        economy.open(host.getUniqueId(), host.getName());
        Raffle raffle = Raffle.item(UUID.randomUUID(), book.nextRaffleNumber(), host.getUniqueId(), host.getName(),
                taken.serializeAsBytes(), name, ticketPrice, Math.max(0, mostTickets), Math.max(0, perPlayer), now,
                now + length * 60_000L);
        Money fee = live.raffleListingFeeMoney();
        EconomyResult paid = book.startRaffle(raffle, fee, economy.most());
        if (!paid.succeeded()) {
            Outcomes.tell(messages, effects, host, paid, currency, "");
            return false;
        }
        // Started first, taken second, on the host's own thread: nothing can move the item in between.
        host.getInventory().setItemInMainHand(null);
        items.put(raffle.id(), taken);
        Scheduling.async(plugin, book::flush);
        if (fee.isPositive()) {
            economy.tell(host.getUniqueId(), fee.negate(), paid.balance(), TransactionKind.RAFFLE);
        }
        messages.send(host, "economy.raffle.started-host", "number", String.valueOf(raffle.number()),
                "item", shown(raffle), "price", currency.render(ticketPrice), "fee", currency.render(fee),
                "duration", Times.describe(Duration.ofMinutes(length)));
        announceStart(raffle);
        return true;
    }

    /** Raffles off money from the host's own account; it leaves the account now and reaches the winner at the draw. */
    public boolean startMoney(Player host, Money prize, Money ticketPrice, int minutes, int mostTickets, int perPlayer) {
        EconomySettings live = settings;
        Currency currency = live.currency();
        if (!open(host)) {
            return false;
        }
        if (!prize.isPositive()) {
            refuse(host, "economy.not-an-amount");
            return false;
        }
        if (!roomFor(host, ticketPrice)) {
            return false;
        }
        long hosting = book.raffles().stream().filter(raffle -> host.getUniqueId().equals(raffle.host())).count();
        if (hosting >= live.rafflesPerHost()) {
            refuse(host, "economy.raffle.hosting", "count", String.valueOf(live.rafflesPerHost()));
            return false;
        }
        long now = clock.getAsLong();
        int length = length(minutes);
        economy.open(host.getUniqueId(), host.getName());
        Raffle raffle = Raffle.money(UUID.randomUUID(), book.nextRaffleNumber(), host.getUniqueId(), host.getName(),
                prize, ticketPrice, Math.max(0, mostTickets), Math.max(0, perPlayer), now, now + length * 60_000L);
        Money fee = live.raffleListingFeeMoney();
        EconomyResult paid = book.startRaffle(raffle, fee, economy.most());
        if (!paid.succeeded()) {
            Outcomes.tell(messages, effects, host, paid, currency, "");
            return false;
        }
        Scheduling.async(plugin, book::flush);
        economy.tell(host.getUniqueId(), paid.amount().negate(), paid.balance(), TransactionKind.RAFFLE);
        messages.send(host, "economy.raffle.started-host", "number", String.valueOf(raffle.number()),
                "item", shown(raffle), "price", currency.render(ticketPrice), "fee", currency.render(fee),
                "duration", Times.describe(Duration.ofMinutes(length)));
        announceStart(raffle);
        return true;
    }

    /** Staff raffling off money the server puts up. Works from the console. */
    public Optional<Raffle> startServer(CommandSender staff, Money prize, Money ticketPrice, int minutes) {
        if (!settings.rafflesEnabled()) {
            messages.send(staff, "economy.raffle.off");
            return Optional.empty();
        }
        if (!prize.isPositive() || !roomFor(staff, ticketPrice)) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        int length = length(minutes);
        Raffle raffle = Raffle.server(UUID.randomUUID(), book.nextRaffleNumber(), prize, ticketPrice, 0, 0, now,
                now + length * 60_000L);
        if (!book.startRaffle(raffle, Money.ZERO, economy.most()).succeeded()) {
            messages.send(staff, "economy.unavailable");
            return Optional.empty();
        }
        Scheduling.async(plugin, book::flush);
        messages.send(staff, "economy.raffle.server-started", "number", String.valueOf(raffle.number()),
                "amount", settings.currency().render(prize));
        announceStart(raffle);
        return Optional.of(raffle);
    }

    private boolean roomFor(CommandSender who, Money ticketPrice) {
        EconomySettings live = settings;
        if (!ticketPrice.isAtLeast(live.raffleSmallestTicketMoney()) || !ticketPrice.isPositive()) {
            messages.send(who, "economy.raffle.ticket-too-low",
                    "amount", live.currency().render(live.raffleSmallestTicketMoney()));
            return false;
        }
        if (book.raffles().size() >= live.raffleMostRunning()) {
            messages.send(who, "economy.raffle.too-many-running", "count", String.valueOf(live.raffleMostRunning()));
            return false;
        }
        return true;
    }

    private int length(int minutes) {
        EconomySettings live = settings;
        int least = live.raffleMinMinutes();
        int most = Math.max(least, live.raffleMaxMinutes());
        int asked = minutes <= 0 ? live.raffleDefaultMinutes() : minutes;
        return Math.max(least, Math.min(most, asked));
    }

    private void announceStart(Raffle raffle) {
        Currency currency = settings.currency();
        Component row = buttons.row(
                buttons.label("<green>[Buy a ticket]").tooltip("<gray>" + currency.format(raffle.ticketPrice()))
                        .runs("/raffle buy " + raffle.number() + " 1"),
                buttons.label("<yellow>[All raffles]").runs("/raffle"));
        String key = raffle.serverRaffle() ? "economy.raffle.started-server" : "economy.raffle.started";
        String duration = Times.describe(left(raffle).plusSeconds(1));
        for (Player listener : audience()) {
            messages.send(listener, "economy.raffle.banner", "number", String.valueOf(raffle.number()));
            messages.send(listener, key, "player", raffle.hostName(), "item", shown(raffle),
                    "price", currency.render(raffle.ticketPrice()), "duration", duration,
                    "buttons", listener.getUniqueId().equals(raffle.host()) ? Component.empty() : row);
            sounds.play(listener.getUniqueId(), GameSounds.RAFFLE);
        }
    }

    // ---------------------------------------------------------------------------- tickets

    public boolean buy(Player player, int number, int count) {
        Currency currency = settings.currency();
        if (!open(player)) {
            return false;
        }
        Optional<Raffle> found = book.raffleNumber(number);
        if (found.isEmpty()) {
            refuse(player, "economy.raffle.none", "number", String.valueOf(number));
            return false;
        }
        economy.open(player.getUniqueId(), player.getName());
        RaffleBuy result = book.buyRaffleTickets(found.get().id(), player.getUniqueId(), player.getName(),
                Math.max(1, count), clock.getAsLong(), economy.most());
        String which = String.valueOf(number);
        switch (result.kind()) {
            case GONE -> refuse(player, "economy.raffle.gone", "number", which);
            case OWN -> refuse(player, "economy.raffle.own");
            case LIMIT -> refuse(player, "economy.raffle.limit", "number", which);
            case SOLD_OUT -> refuse(player, "economy.raffle.sold-out", "number", which);
            case REFUSED -> Outcomes.tell(messages, effects, player, result.payment(), currency, "");
            case BOUGHT -> {
                Raffle after = result.raffle();
                economy.tell(player.getUniqueId(), result.payment().amount().negate(), result.payment().balance(),
                        TransactionKind.RAFFLE);
                int mine = after.ticketsOf(player.getUniqueId());
                messages.send(player, "economy.raffle.bought", "count", String.valueOf(result.bought()),
                        "number", which, "item", shown(after), "mine", String.valueOf(mine),
                        "total", String.valueOf(after.sold()),
                        "chance", String.format("%.1f%%", rule.chance(mine, after.sold()) * 100));
                sounds.play(player.getUniqueId(), GameSounds.CHIPS);
            }
        }
        return result.kind() == RaffleBuy.Kind.BOUGHT;
    }

    // ---------------------------------------------------------------------------- calling off

    /** The host before any ticket is sold, or staff at any time: every ticket paid back, the item returned. */
    public boolean cancel(CommandSender who, int number, boolean staff) {
        Optional<Raffle> found = book.raffleNumber(number);
        String which = String.valueOf(number);
        if (found.isEmpty()) {
            messages.send(who, "economy.raffle.none", "number", which);
            return false;
        }
        Raffle raffle = found.get();
        if (!staff) {
            if (!(who instanceof Player player) || !player.getUniqueId().equals(raffle.host())) {
                messages.send(who, "economy.raffle.not-yours", "number", which);
                return false;
            }
            if (raffle.sold() > 0) {
                messages.send(who, "economy.raffle.cannot-cancel", "number", which);
                return false;
            }
        }
        return callOff(raffle.id());
    }

    private boolean callOff(UUID id) {
        Optional<RaffleDraw> off = book.cancelRaffle(id);
        off.ifPresent(draw -> {
            Raffle raffle = draw.raffle();
            broadcast("economy.raffle.called-off", "number", String.valueOf(raffle.number()), "item", shown(raffle));
            raffle.tickets().forEach((holder, count) -> economy.tell(holder, raffle.ticketPrice().times(count),
                    economy.balance(holder), TransactionKind.RAFFLE));
            deliverTo(raffle.host());
            items.remove(raffle.id());
            Scheduling.async(plugin, book::flush);
        });
        return off.isPresent();
    }

    // ---------------------------------------------------------------------------- the draw

    /** Once a second, on the global thread. */
    public void tick() {
        if (!book.isLoaded()) {
            return;
        }
        long now = clock.getAsLong();
        boolean on = settings.rafflesEnabled();
        for (Raffle raffle : book.raffles()) {
            if (!on) {
                callOff(raffle.id());
            } else if (raffle.over(now)) {
                draw(raffle, now);
            }
        }
    }

    /** Settled at once, then called out after a drumroll — a restart in between changes nobody's luck. */
    private void draw(Raffle raffle, long now) {
        double uniform = random.nextDouble();
        Optional<RaffleDraw> drawn = book.drawRaffle(raffle.id(), tickets -> rule.winner(tickets, uniform),
                this::fee, now);
        if (drawn.isEmpty()) {
            return;
        }
        RaffleDraw draw = drawn.get();
        Scheduling.async(plugin, book::flush);
        String which = String.valueOf(raffle.number());
        Component prize = shown(raffle);
        items.remove(raffle.id());
        if (draw.winner() == null) {
            broadcast("economy.raffle.nobody", "number", which, "item", prize, "player", raffle.hostName());
            deliverTo(raffle.host());
            return;
        }
        for (Player listener : audience()) {
            messages.send(listener, "economy.raffle.drawing", "number", which, "item", prize,
                    "total", String.valueOf(raffle.sold()));
            sounds.play(listener.getUniqueId(), GameSounds.DRUMROLL);
        }
        Currency currency = settings.currency();
        OfflinePlayer winner = server.getOfflinePlayer(draw.winner());
        String winnerName = winner.getName() == null ? "somebody" : winner.getName();
        Scheduling.globalLater(plugin, DRUMROLL_TICKS, () -> {
            for (Player listener : audience()) {
                messages.send(listener, "economy.raffle.won", "number", which, "player", winnerName, "item", prize,
                        "mine", String.valueOf(raffle.ticketsOf(draw.winner())), "total", String.valueOf(raffle.sold()));
            }
            if (raffle.moneyPrize()) {
                economy.tell(draw.winner(), raffle.prize(), economy.balance(draw.winner()), TransactionKind.RAFFLE);
            }
            Player online = winner.getPlayer();
            if (online != null) {
                online.showTitle(net.kyori.adventure.title.Title.title(messages.get("economy.raffle.title-won"),
                        messages.get("economy.raffle.subtitle-won", "item", prize)));
                sounds.play(online.getUniqueId(), GameSounds.JACKPOT);
                auctions.deliver(online);
            }
            if (raffle.host() != null) {
                economy.tell(raffle.host(), draw.paid(), economy.balance(raffle.host()), TransactionKind.RAFFLE);
                Player host = server.getPlayer(raffle.host());
                if (host != null) {
                    messages.send(host, "economy.raffle.host-paid", "number", which,
                            "total", String.valueOf(raffle.sold()), "paid", currency.render(draw.paid()),
                            "fee", currency.render(draw.fee()));
                    effects.play(host.getUniqueId(), GameSounds.CASH);
                }
            }
        });
    }

    private void deliverTo(UUID player) {
        if (player == null) {
            return;
        }
        Player online = server.getPlayer(player);
        if (online != null) {
            auctions.deliver(online);
        }
    }

    // ---------------------------------------------------------------------------- telling

    private boolean open(Player player) {
        if (!settings.rafflesEnabled()) {
            refuse(player, "economy.raffle.off");
            return false;
        }
        if (!player.hasPermission(PermissionNodes.AUCTION)) {
            refuse(player, "economy.raffle.not-allowed");
            return false;
        }
        if (!book.isLoaded()) {
            refuse(player, "economy.unavailable");
            return false;
        }
        return true;
    }

    /** Everybody online who has not muted auctions and raffles. */
    private List<Player> audience() {
        List<Player> listening = new ArrayList<>();
        for (Player player : server.getOnlinePlayers()) {
            if (AuctionService.NEWS.isOn(player)) {
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
