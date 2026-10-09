package de.raindancer.modules.cosmetics.service;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.cosmetics.CosmeticsSettings;
import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.model.Preset;
import de.raindancer.modules.cosmetics.model.Unlock;
import de.raindancer.modules.cosmetics.store.UnlockBook;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Buying cosmetics. A cosmetic with a price is bought, and only the purchase (or {@code rainscosmetics.free})
 * unlocks it — its permission node no longer applies. One without a price is decided by its node, exactly as before.
 */
public final class UnlockService implements ICosmeticsService, Entitlements {

    public enum Outcome { DONE, NOT_FOR_SALE, ALREADY_YOURS, CANNOT_AFFORD, NO_ECONOMY, NOT_SAVED, REFUSED }

    /** @param charged what was actually taken, after the price index and levers */
    public record Result(Outcome outcome, Money charged) {

        public boolean done() {
            return outcome == Outcome.DONE;
        }
    }

    private final UnlockBook book;
    private final Supplier<Catalogue> catalogue;
    private final Messages messages;
    private volatile CosmeticsSettings settings;

    public UnlockService(UnlockBook book, Supplier<Catalogue> catalogue, Messages messages,
                         CosmeticsSettings settings) {
        this.book = book;
        this.catalogue = catalogue;
        this.messages = messages;
        this.settings = settings;
    }

    @Override
    public void settings(CosmeticsSettings fresh) {
        this.settings = fresh;
    }

    /** The price as written: a preset's own, else its category's. */
    private String written(String key) {
        CosmeticsSettings live = settings;
        if (key.startsWith("preset.")) {
            String id = key.substring("preset.".length());
            return catalogue.get().preset(id)
                    .map(preset -> preset.price().isBlank() ? live.pricePreset() : preset.price())
                    .orElse("0");
        }
        if (key.startsWith("decoration.")) {
            return live.priceNameDecoration();
        }
        return switch (key) {
            case Unlock.NAME_COLOUR -> live.priceNameColour();
            case Unlock.NAME_GRADIENT -> live.priceNameGradient();
            case Unlock.NAME_ANY_COLOUR -> live.priceNameAnyColour();
            case Unlock.NAME_ANIMATED -> live.priceNameAnimated();
            case Unlock.PARTICLES -> live.priceParticles();
            case Unlock.TELEPORT -> live.priceTeleportLooks();
            default -> "0";
        };
    }

    private Money price(String key) {
        return settings.sellCosmetics() ? Fees.amount(written(key)) : Money.ZERO;
    }

    @Override
    public boolean priced(String key) {
        return price(key).isPositive();
    }

    @Override
    public String priceText(String key) {
        Money price = price(key);
        return price.isPositive() ? Fees.format(Fees.quote(Unlock.sourceOf(key), price)) : "";
    }

    public boolean owns(UUID player, String key) {
        return player != null && book.has(player, key);
    }

    @Override
    public boolean allowed(Permissible who, String key, String node) {
        if (!priced(key)) {
            return who.hasPermission(node);
        }
        return who.hasPermission(PermissionNodes.FREE) || (who instanceof Player player && owns(player.getUniqueId(), key));
    }

    /** Whether anything at all is for sale, which is whether the Unlocks page is worth showing. */
    @Override
    public boolean anySold() {
        return !sellable().isEmpty();
    }

    /** The owner's switch: off, nothing is for sale and the permissions decide, whatever the prices say. */
    public boolean selling() {
        return settings.sellCosmetics();
    }

    /** Every key with a price, in the order the Unlocks page lists them. */
    public List<String> sellable() {
        return offered().stream().filter(this::priced).toList();
    }

    /** Every cosmetic that can be sold, priced or not: the Unlocks page greys the ones without a price. */
    public List<String> offered() {
        List<String> keys = new ArrayList<>(List.of(Unlock.NAME_COLOUR, Unlock.NAME_GRADIENT,
                Unlock.NAME_ANY_COLOUR, Unlock.NAME_ANIMATED));
        for (TextDecoration decoration : TextDecoration.values()) {
            keys.add(Unlock.decoration(decoration));
        }
        catalogue.get().presets().forEach(preset -> keys.add(Unlock.preset(preset.id())));
        keys.add(Unlock.PARTICLES);
        keys.add(Unlock.TELEPORT);
        return keys;
    }

    /** What a key is called to a player. */
    public String title(String key) {
        if (key.startsWith("preset.")) {
            return catalogue.get().preset(key.substring("preset.".length()))
                    .map(Preset::title).map(title -> title + " preset").orElse(key);
        }
        if (key.startsWith("decoration.")) {
            return capital(key.substring("decoration.".length())) + " names";
        }
        return switch (key) {
            case Unlock.NAME_COLOUR -> "A name colour";
            case Unlock.NAME_GRADIENT -> "Gradient names";
            case Unlock.NAME_ANY_COLOUR -> "Any colour for your name";
            case Unlock.NAME_ANIMATED -> "Flowing gradients";
            case Unlock.PARTICLES -> "Particles and wings";
            case Unlock.TELEPORT -> "Teleport effects";
            default -> key;
        };
    }

    private static String capital(String word) {
        return word.isEmpty() ? word : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
    }

    public Optional<String> keyOfPreset(Preset preset) {
        String key = Unlock.preset(preset.id());
        return priced(key) ? Optional.of(key) : Optional.empty();
    }

    public Result buy(UUID player, String key) {
        Money price = price(key);
        if (!price.isPositive()) {
            return new Result(Outcome.NOT_FOR_SALE, Money.ZERO);
        }
        if (owns(player, key)) {
            return new Result(Outcome.ALREADY_YOURS, Money.ZERO);
        }
        String source = Unlock.sourceOf(key);
        String reason = "Cosmetic: " + title(key);
        EconomyResult paid = Fees.charge(player, price, reason, source);
        if (!paid.succeeded()) {
            return new Result(switch (paid.outcome()) {
                case NOT_ENOUGH -> Outcome.CANNOT_AFFORD;
                case UNAVAILABLE -> Outcome.NO_ECONOMY;
                default -> Outcome.REFUSED;
            }, Money.ZERO);
        }
        if (!book.add(player, key)) {
            Fees.refund(player, paid.amount(), reason + " (not saved)", source);
            return new Result(Outcome.NOT_SAVED, Money.ZERO);
        }
        return new Result(Outcome.DONE, paid.amount());
    }

    /** Buys it for a player and tells them what came of it. @return whether it is theirs now */
    public boolean purchase(Player player, String key) {
        Result result = buy(player.getUniqueId(), key);
        String title = title(key);
        switch (result.outcome()) {
            case DONE -> messages.send(player, "cosmetics.unlock.bought", "what", title,
                    "amount", Fees.format(result.charged()));
            case ALREADY_YOURS -> messages.send(player, "cosmetics.unlock.already-yours", "what", title);
            case NOT_FOR_SALE -> messages.send(player, "cosmetics.unlock.not-for-sale", "what", title);
            case CANNOT_AFFORD -> messages.send(player, "cosmetics.unlock.cannot-afford", "what", title,
                    "amount", priceText(key));
            case NO_ECONOMY -> messages.send(player, "cosmetics.unlock.no-economy", "what", title,
                    "amount", priceText(key));
            case NOT_SAVED -> messages.send(player, "cosmetics.unlock.not-saved");
            case REFUSED -> messages.send(player, "cosmetics.unlock.not-charged", "what", title);
        }
        return result.done();
    }

    @Override
    public String describe() {
        return "buying cosmetics, and who has bought what";
    }
}
