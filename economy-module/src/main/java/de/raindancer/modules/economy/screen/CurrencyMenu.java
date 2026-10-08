package de.raindancer.modules.economy.screen;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.ui.choose.StyleEditor;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.ui.prompt.Parsers;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.function.Function;

/**
 * Painting the money: its names, its symbol, and the colours of each — through the same editor players
 * paint their names with, gradients, decorations and flowing included.
 */
public final class CurrencyMenu extends Menu implements IEconomyScreen {

    private final EconomyServices services;

    public CurrencyMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Currency");
    }

    @Override
    public String breadcrumb() {
        return "Currency";
    }

    @Override
    protected void render() {
        EconomySettings live = services.config();
        Currency currency = live.currency();
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.GOLD_INGOT, Mini.of(currency.render(currency.ofMajor(1234))),
                "<gray>" + Mini.of(currency.render(currency.ofMajor(1))) + " is one " + Mini.of(currency.renderName(false)),
                "<gray>Shortened: " + Mini.of(currency.renderCompact(currency.ofMajor(2_500_000)))));

        text(MenuLayout.WHO, 2, Material.NAME_TAG, "Name, one", "currency.singular", live.currencySingular());
        text(MenuLayout.WHO, 4, Material.NAME_TAG, "Name, many", "currency.plural", live.currencyPlural());
        text(MenuLayout.WHO, 6, Material.GOLD_NUGGET, "Symbol", "currency.symbol", live.currencySymbol());

        paint(2, "Name colours", "currency.name-style", EconomySettings::nameStyle, currency.plural());
        paint(4, "Symbol colours", "currency.symbol-style", EconomySettings::symbolStyle,
                currency.symbol().isEmpty() ? "$" : currency.symbol());
        paint(6, "Number colours", "currency.amount-style", EconomySettings::amountStyle, "1,234");

        band(MenuLayout.LAND, 2, Icons.of(Material.COMPASS, "<white>Symbol goes: "
                + live.currencyPlacement().name().toLowerCase(), "<yellow>Click<gray> to switch"), click -> {
            write("currency.placement", null);
        });
        band(MenuLayout.LAND, 6, Icons.of(Material.COMPARATOR, "<white>Writing: " + currency.format(currency.ofMajor(1000)),
                "<yellow>Click<gray> to swap , and ."), click ->
                write("currency.group-separator", ",".equals(live.groupSeparator()) ? "." : ","));
    }

    private void text(int band, int column, Material icon, String what, String key, String now) {
        band(band, column, Icons.of(icon, "<white>" + what, "<gray>" + MiniMessage.miniMessage().escapeTags(now),
                "<yellow>Click<gray> to change"), click -> AnvilInput.open(viewer, what, now, Parsers.text(24),
                value -> {
                    write(key, value);
                    open();
                }, this::open));
    }

    private void paint(int column, String what, String key, Function<EconomySettings, String> current, String sample) {
        band(MenuLayout.RULES, column, Icons.of(Material.MAGENTA_DYE, "<white>" + what,
                Mini.of(de.raindancer.core.ui.text.Gradients.styled(sample,
                        NameStyle.parse(current.apply(services.config())))),
                "<yellow>Click<gray> to paint"), click -> StyleEditor.of(viewer, services.brand(), this)
                .heading(what)
                .sample(sample)
                .current(() -> NameStyle.parse(current.apply(services.config())))
                .onChange(style -> write(key, style.encode()))
                .open());
    }

    /** Writes one setting and saves; a null value cycles it (a flag or a choice). */
    private void write(String key, String value) {
        SettingsStore<EconomySettings> store = services.store();
        if (value == null) {
            store.cycle(key);
        } else {
            store.set(key, value);
        }
        store.trySave();
        refresh();
    }

    @Override
    public String describe() {
        return "painting the currency's names, symbol and colours";
    }
}
