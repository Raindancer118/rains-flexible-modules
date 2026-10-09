package de.raindancer.modules.cosmetics.service;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.CosmeticsSettings;
import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.model.Preset;
import de.raindancer.modules.cosmetics.model.Unlock;
import de.raindancer.modules.cosmetics.store.UnlockBook;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnlockServiceTest {

    @TempDir
    Path folder;

    private final Bank bank = new Bank(Money.of(100_000));
    private final UUID tom = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final Preset sunset = new Preset("sunset", "Sunset", NameStyle.NONE.withColour(NamedTextColor.RED), false, "75");
    private final Preset plain = new Preset("plain", "Plain", NameStyle.NONE.withColour(NamedTextColor.BLUE), false);
    private UnlockBook book;

    @BeforeEach
    void start() {
        when(player.getUniqueId()).thenReturn(tom);
        Economies.provide(mock(Plugin.class), bank);
        book = new UnlockBook(new YamlStore(folder.resolve("unlocks.yml")));
        book.load();
    }

    @AfterEach
    void reset() {
        Economies.clear();
    }

    private UnlockService service(CosmeticsSettings settings) {
        return service(settings, List.of(sunset, plain));
    }

    private UnlockService service(CosmeticsSettings settings, List<Preset> presets) {
        return new UnlockService(book, () -> new Catalogue(List.of(), presets), mock(Messages.class), settings);
    }

    private static CosmeticsSettings priced(String gradient, String presets, String particles) {
        CosmeticsSettings d = CosmeticsSettings.DEFAULTS;
        return new CosmeticsSettings(d.maxStops(), d.dropWithoutPermission(), d.nameAboveHead(), d.particlesEnabled(),
                d.particleEveryTicks(), d.particleCount(), d.particleMaxCount(), d.blockedParticles(), d.teleportLooks(),
                d.teleportSounds(), "0", gradient, "0", "0", "0", presets, particles, "0");
    }

    @Test
    @DisplayName("with default settings nothing is for sale and the permission decides, as before")
    void defaults() {
        UnlockService unlocks = service(CosmeticsSettings.DEFAULTS, List.of(plain));
        Permissible who = mock(Permissible.class);
        when(who.hasPermission(PermissionNodes.NAME_GRADIENT)).thenReturn(true);
        when(who.hasPermission(PermissionNodes.NAME_COLOUR)).thenReturn(false);

        assertThat(unlocks.sellable()).isEmpty();
        assertThat(unlocks.anySold()).isFalse();
        assertThat(unlocks.allowed(who, Unlock.NAME_GRADIENT, PermissionNodes.NAME_GRADIENT)).isTrue();
        assertThat(unlocks.allowed(who, Unlock.NAME_COLOUR, PermissionNodes.NAME_COLOUR)).isFalse();
        assertThat(unlocks.buy(tom, Unlock.NAME_GRADIENT).outcome()).isEqualTo(UnlockService.Outcome.NOT_FOR_SALE);
        assertThat(bank.calls).isEmpty();
    }

    @Test
    @DisplayName("a priced cosmetic ignores the permission node and needs a purchase, or rainscosmetics.free")
    void pricedNeedsPurchase() {
        UnlockService unlocks = service(priced("40", "0", "0"));
        when(player.hasPermission(PermissionNodes.NAME_GRADIENT)).thenReturn(true);

        assertThat(unlocks.allowed(player, Unlock.NAME_GRADIENT, PermissionNodes.NAME_GRADIENT)).isFalse();

        when(player.hasPermission(PermissionNodes.FREE)).thenReturn(true);
        assertThat(unlocks.allowed(player, Unlock.NAME_GRADIENT, PermissionNodes.NAME_GRADIENT)).isTrue();
    }

    @Test
    @DisplayName("buying charges the price with the names source and unlocks it for good")
    void buy() {
        UnlockService unlocks = service(priced("40", "0", "0"));

        UnlockService.Result result = unlocks.buy(tom, Unlock.NAME_GRADIENT);

        assertThat(result.outcome()).isEqualTo(UnlockService.Outcome.DONE);
        assertThat(bank.calls).containsExactly("withdraw 4000 names.style");
        assertThat(unlocks.allowed(player, Unlock.NAME_GRADIENT, PermissionNodes.NAME_GRADIENT)).isTrue();
        assertThat(unlocks.buy(tom, Unlock.NAME_GRADIENT).outcome()).isEqualTo(UnlockService.Outcome.ALREADY_YOURS);
        assertThat(bank.calls).hasSize(1);
    }

    @Test
    @DisplayName("particles are booked under cosmetics.buy")
    void particlesSource() {
        service(priced("0", "0", "60")).buy(tom, Unlock.PARTICLES);

        assertThat(bank.calls).containsExactly("withdraw 6000 cosmetics.buy");
    }

    @Test
    @DisplayName("a preset costs its own price, else the preset price, else nothing")
    void presetPrices() {
        UnlockService unlocks = service(priced("0", "20", "0"));

        assertThat(unlocks.priceText(Unlock.preset("sunset"))).contains("75");
        assertThat(unlocks.priceText(Unlock.preset("plain"))).contains("20");
        assertThat(service(CosmeticsSettings.DEFAULTS).priced(Unlock.preset("plain"))).isFalse();
        assertThat(service(CosmeticsSettings.DEFAULTS).sellable()).as("only the preset that names its own price")
                .containsExactly("preset.sunset");
        assertThat(unlocks.sellable()).contains("preset.sunset", "preset.plain");
    }

    @Test
    @DisplayName("somebody who cannot pay gets nothing and is not charged")
    void cannotAfford() {
        bank.money = Money.of(10);

        assertThat(service(priced("40", "0", "0")).buy(tom, Unlock.NAME_GRADIENT).outcome())
                .isEqualTo(UnlockService.Outcome.CANNOT_AFFORD);
        assertThat(book.has(tom, Unlock.NAME_GRADIENT)).isFalse();
        assertThat(bank.calls).isEmpty();
    }

    @Test
    @DisplayName("a price without any economy is never waved through")
    void noEconomy() {
        Economies.clear();

        assertThat(service(priced("40", "0", "0")).buy(tom, Unlock.NAME_GRADIENT).outcome())
                .isEqualTo(UnlockService.Outcome.NO_ECONOMY);
    }

    @Test
    @DisplayName("when the purchase cannot be saved the money is given back")
    void refund() throws Exception {
        Files.writeString(folder.resolve("unlocks.yml"), "unlocks: [ this: is: not yaml");
        book.load();

        assertThat(service(priced("40", "0", "0")).buy(tom, Unlock.NAME_GRADIENT).outcome())
                .isEqualTo(UnlockService.Outcome.NOT_SAVED);
        assertThat(bank.calls).containsExactly("withdraw 4000 names.style", "deposit 4000 names.style");
    }

    @Test
    @DisplayName("each decoration is sold on its own at the decoration price")
    void decorations() {
        CosmeticsSettings d = CosmeticsSettings.DEFAULTS;
        UnlockService unlocks = service(new CosmeticsSettings(d.maxStops(), d.dropWithoutPermission(),
                d.nameAboveHead(), d.particlesEnabled(), d.particleEveryTicks(), d.particleCount(),
                d.particleMaxCount(), d.blockedParticles(), d.teleportLooks(), d.teleportSounds(),
                "0", "0", "0", "0", "15", "0", "0", "0"));

        assertThat(unlocks.sellable()).contains(Unlock.decoration(TextDecoration.BOLD),
                Unlock.decoration(TextDecoration.ITALIC));
        assertThat(unlocks.buy(tom, Unlock.decoration(TextDecoration.BOLD)).done()).isTrue();
        assertThat(unlocks.owns(tom, Unlock.decoration(TextDecoration.BOLD))).isTrue();
        assertThat(unlocks.owns(tom, Unlock.decoration(TextDecoration.ITALIC))).isFalse();
    }

    @Test
    @DisplayName("every shipped price is zero")
    void shippedPricesAreZero() {
        CosmeticsSettings d = CosmeticsSettings.DEFAULTS;
        assertThat(List.of(d.priceNameColour(), d.priceNameGradient(), d.priceNameAnyColour(), d.priceNameAnimated(),
                d.priceNameDecoration(), d.pricePreset(), d.priceParticles(), d.priceTeleportLooks())).containsOnly("0");
    }

    private static final class Bank implements Economy {
        final List<String> calls = new ArrayList<>();
        Money money;

        Bank(Money money) {
            this.money = money;
        }

        @Override
        public String name() {
            return "test";
        }

        @Override
        public Currency currency() {
            return Currency.DEFAULT;
        }

        @Override
        public boolean hasAccount(UUID player) {
            return true;
        }

        @Override
        public Money balance(UUID player) {
            return money;
        }

        @Override
        public EconomyResult deposit(UUID player, Money amount, String reason) {
            return EconomyResult.done(amount, money);
        }

        @Override
        public EconomyResult withdraw(UUID player, Money amount, String reason) {
            return EconomyResult.done(amount, money);
        }

        @Override
        public EconomyResult deposit(UUID player, Money amount, String reason, String source) {
            calls.add("deposit " + amount.minor() + " " + source);
            return EconomyResult.done(amount, money);
        }

        @Override
        public EconomyResult withdraw(UUID player, Money amount, String reason, String source) {
            if (!money.isAtLeast(amount)) {
                return EconomyResult.failed(EconomyResult.Outcome.NOT_ENOUGH, amount, money);
            }
            calls.add("withdraw " + amount.minor() + " " + source);
            return EconomyResult.done(amount, money);
        }

        @Override
        public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
            return EconomyResult.done(amount, money);
        }
    }
}
