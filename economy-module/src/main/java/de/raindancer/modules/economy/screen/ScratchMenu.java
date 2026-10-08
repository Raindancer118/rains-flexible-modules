package de.raindancer.modules.economy.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.rules.ScratchRule;
import de.raindancer.modules.economy.service.GameSounds;
import de.raindancer.modules.economy.service.ScratchService;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** A scratch card in front of you: nine silver fields; scratch them, three alike win. Already paid. */
public final class ScratchMenu extends Menu implements IEconomyScreen {

    private final EconomyServices services;
    private final ScratchService.Scratched card;
    private final boolean[] scratched = new boolean[9];
    private boolean told;

    public ScratchMenu(EconomyServices services, Player viewer, ScratchService.Scratched card) {
        super(viewer, services.brand(), null);
        this.services = services;
        this.card = card;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Scratch card");
    }

    @Override
    public String breadcrumb() {
        return "Scratch card";
    }

    private static Material icon(ScratchRule.Prize prize) {
        return switch (prize) {
            case CLOVER -> Material.FERN;
            case CHERRY -> Material.SWEET_BERRIES;
            case BELL -> Material.BELL;
            case STAR -> Material.NETHER_STAR;
            case CROWN -> Material.GOLDEN_HELMET;
            case DIAMOND -> Material.DIAMOND;
        };
    }

    @Override
    protected void render() {
        boolean all = true;
        for (int field = 0; field < 9; field++) {
            int index = field;
            int slot = (1 + field / 3) * 9 + 3 + field % 3;
            if (scratched[field]) {
                ScratchRule.Prize prize = card.fields().get(field);
                set(slot, Icons.of(icon(prize), "<white>" + prize.name().charAt(0) + prize.name().substring(1).toLowerCase()));
            } else {
                all = false;
                set(slot, Icons.of(Material.LIGHT_GRAY_CONCRETE_POWDER, "<gray>Scratch me", "<yellow>Click<gray> to scratch"),
                        click -> {
                            scratched[index] = true;
                            services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.SCRATCH);
                            refresh();
                        });
            }
        }
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.MAP, "<gold>Scratch card",
                "<gray>Three alike win. Prizes, in tickets:", "<gray>Clover 1×, Cherry 2×, Bell 5×,",
                "<gray>Star 20×, Crown 100×, Diamond 1000×", "<dark_gray>(less the house edge)"));
        if (!all) {
            toolbar(4, Icons.of(Material.BRUSH, "<yellow>Scratch everything"), click -> {
                java.util.Arrays.fill(scratched, true);
                services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.SCRATCH);
                refresh();
            });
        } else {
            toolbar(4, Icons.of(card.payout().isPositive() ? Material.EMERALD : Material.BARRIER,
                    card.payout().isPositive() ? "<green>Won " + Mini.of(services.currency().render(card.payout()))
                            : "<red>No luck this time"), click -> { });
            if (!told) {
                told = true;
                services.scratch().reveal(viewer, card);
            }
        }
    }

    @Override
    public void handleClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        super.handleClose(event);
        if (!told) {
            told = true;
            services.scratch().reveal(viewer, card);
        }
    }

    @Override
    public String describe() {
        return "scratching a scratch card";
    }
}
