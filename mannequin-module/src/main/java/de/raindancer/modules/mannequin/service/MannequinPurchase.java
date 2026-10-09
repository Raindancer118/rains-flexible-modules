package de.raindancer.modules.mannequin.service;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.mannequin.MannequinSettings;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.model.MannequinKind;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * What creating and removing a mannequin costs, and gives back.
 *
 * <p>The price is taken before anything is placed and returned if placing fails, so a player is never
 * out of pocket for a mannequin that does not exist. With the price at its default of zero nobody is
 * asked for anything, and no economy plugin is needed.
 */
public final class MannequinPurchase implements IMannequinService {

    public static final String SOURCE = "mannequin.buy";

    /** Places a mannequin; the live one is {@link MannequinService#create}. */
    @FunctionalInterface
    public interface Placer {
        Mannequin place(UUID owner, MannequinKind kind, Location where);
    }

    /** Takes it away again; the live one is {@link MannequinService#remove}. */
    @FunctionalInterface
    public interface Remover {
        void remove(String id);
    }

    /** Money moving; the live one is {@link Fees}. */
    public interface FeeBank {
        Money quote(Money written);

        EconomyResult charge(UUID payer, Money written, String reason);

        void refund(UUID to, Money taken, String reason);
    }

    public static final FeeBank LIVE = new FeeBank() {
        @Override
        public Money quote(Money written) {
            return Fees.quote(SOURCE, written);
        }

        @Override
        public EconomyResult charge(UUID payer, Money written, String reason) {
            return Fees.charge(payer, written, reason, SOURCE);
        }

        @Override
        public void refund(UUID to, Money taken, String reason) {
            Fees.refund(to, taken, reason, SOURCE);
        }
    };

    /** What each mannequin was bought for, so a refund is a share of what was paid and never of today's price. */
    public interface Receipts {
        void paid(String mannequin, Money amount);

        /** What was paid for it, forgotten as it is read; zero when nothing was. */
        Money takePaid(String mannequin);

        /** Kept in memory only — for tests, and a server that never charges. */
        static Receipts inMemory() {
            java.util.Map<String, Money> paid = new java.util.concurrent.ConcurrentHashMap<>();
            return new Receipts() {
                @Override
                public void paid(String mannequin, Money amount) {
                    paid.put(mannequin, amount);
                }

                @Override
                public Money takePaid(String mannequin) {
                    Money was = paid.remove(mannequin);
                    return was == null ? Money.ZERO : was;
                }
            };
        }

        /** Kept in a file in the module's folder, so a refund survives a restart. */
        static Receipts inFile(java.nio.file.Path file) {
            de.raindancer.core.data.store.YamlStore store = new de.raindancer.core.data.store.YamlStore(file);
            return new Receipts() {
                @Override
                public void paid(String mannequin, Money amount) {
                    store.update(yaml -> yaml.set(de.raindancer.core.data.store.YamlStore.asPathPart(mannequin),
                            amount.minor()));
                }

                @Override
                public synchronized Money takePaid(String mannequin) {
                    String key = de.raindancer.core.data.store.YamlStore.asPathPart(mannequin);
                    long minor = store.read().getLong(key, 0L);
                    if (minor > 0) {
                        store.update(yaml -> yaml.set(key, null));
                    }
                    return Money.of(Math.max(0, minor));
                }
            };
        }
    }

    private final Messages messages;
    private final Placer placer;
    private final Remover remover;
    private final FeeBank bank;
    private final Receipts receipts;
    private volatile MannequinSettings settings;

    public MannequinPurchase(Messages messages, Placer placer, Remover remover, FeeBank bank,
                             MannequinSettings settings) {
        this(messages, placer, remover, bank, Receipts.inMemory(), settings);
    }

    public MannequinPurchase(Messages messages, Placer placer, Remover remover, FeeBank bank, Receipts receipts,
                             MannequinSettings settings) {
        this.receipts = receipts;
        this.messages = messages;
        this.placer = placer;
        this.remover = remover;
        this.bank = bank;
        this.settings = settings;
    }

    @Override
    public void settings(MannequinSettings settings) {
        this.settings = settings;
    }

    /** What creating one costs right now, after the price index; zero when it is free. */
    public Money priceNow() {
        return bank.quote(Fees.amount(settings.price()));
    }

    /**
     * Charges, then places. Empty means nothing was placed and the player has been told why; any
     * price taken has been given back.
     */
    public Optional<Mannequin> buy(Player player, MannequinKind kind) {
        UUID id = player.getUniqueId();
        EconomyResult paid = bank.charge(id, Fees.amount(settings.price()), "Mannequin");
        if (!paid.succeeded()) {
            messages.send(player, paid.outcome() == EconomyResult.Outcome.NOT_ENOUGH
                            ? "mannequin.price.not-enough" : "mannequin.price.refused",
                    "price", Fees.format(paid.amount()));
            return Optional.empty();
        }
        Mannequin placed;
        try {
            placed = placer.place(id, kind, player.getLocation());
        } catch (RuntimeException failed) {
            bank.refund(id, paid.amount(), "Mannequin could not be placed");
            messages.send(player, "mannequin.price.placement-failed");
            return Optional.empty();
        }
        if (paid.amount().isPositive()) {
            receipts.paid(placed.id(), paid.amount());
            messages.send(player, "mannequin.price.paid", "price", Fees.format(paid.amount()));
        }
        return Optional.of(placed);
    }

    /** Gives the owner their configured share of what they paid for it back, then removes the mannequin. */
    public void retire(Mannequin mannequin) {
        Money back = receipts.takePaid(mannequin.id())
                .share(Math.max(0, Math.min(100, settings.refundPercentOnRemove())) / 100.0);
        if (back.isPositive()) {
            bank.refund(mannequin.owner(), back, "Mannequin removed");
        }
        remover.remove(mannequin.id());
    }
}
