package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimPantry;
import de.raindancer.modules.claims.model.PotionStore;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Stocking one of the claim's three stores: the pantry, the potions its effects drink, or the equipment
 * it hands out.
 *
 * <h2>One screen, three stores</h2>
 * They are the same interaction — look at what is in there, put something in, take something out — with a
 * different acceptance rule and a different cap. Written separately, one of them ends up with the
 * deposit-from-hand behaviour and the others do not, which is exactly what had happened before {@code
 * EQUIPMENT} was added here rather than as a fourth near-identical class.
 */
public final class StoreMenu extends PaginatedMenu<ItemStack> implements IClaimScreen {

    /** Which of the three stores this is. */
    public enum Kind {
        PANTRY, POTIONS, EQUIPMENT
    }

    private final ClaimServices services;
    private final Claim claim;
    private final Kind kind;

    public StoreMenu(ClaimServices services, Player viewer, Claim claim, Menu parent, Kind kind) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.claim = claim;
        this.kind = kind;
    }

    @Override
    protected Component title() {
        return Component.text(switch (kind) {
            case PANTRY -> "Pantry";
            case POTIONS -> "Potions";
            case EQUIPMENT -> "Equipment stock";
        });
    }

    @Override
    protected List<ItemStack> entries() {
        return new ArrayList<>(switch (kind) {
            case PANTRY -> claim.pantry().items();
            case POTIONS -> claim.potionStore().potions();
            case EQUIPMENT -> claim.equipment().stock();
        });
    }

    @Override
    protected ItemStack emptyIcon() {
        Material material = switch (kind) {
            case PANTRY -> Material.BREAD;
            case POTIONS -> Material.GLASS_BOTTLE;
            case EQUIPMENT -> Material.CHEST;
        };
        return Icons.of(material, "<gray>Empty", "<gray>Hold something and click the tile below.");
    }

    @Override
    protected ItemStack icon(ItemStack entry) {
        return entry.clone();
    }

    @Override
    protected void onClick(ItemStack entry, InventoryClickEvent event) {
        if (!mayManage()) {
            services.messages().send(viewer, "error.no-claim-permission");
            return;
        }
        List<ItemStack> held = switch (kind) {
            case PANTRY -> claim.pantry().items();
            case POTIONS -> claim.potionStore().potions();
            case EQUIPMENT -> claim.equipment().stock();
        };
        int index = held.indexOf(entry);
        if (index < 0) {
            refresh();
            return;
        }
        // The lists above are read-only views — withdrawItem is the store's own way of taking a stack
        // back out, and it is what actually removes it rather than throwing on an unmodifiable list.
        ItemStack taken = switch (kind) {
            case PANTRY -> claim.pantry().withdrawItem(index);
            case POTIONS -> claim.potionStore().withdrawItem(index);
            case EQUIPMENT -> claim.equipment().withdrawItem(index);
        };
        if (taken == null) {
            refresh();
            return;
        }
        var leftOver = viewer.getInventory().addItem(taken);
        // Straight back in if it did not fit, rather than on the floor.
        leftOver.values().forEach(spare -> deposit(spare));
        if (!leftOver.isEmpty()) {
            services.messages().send(viewer, "claim.bank-inventory-full");
        }
        claim.markDirty();
        services.claimService().saveAsync(claim);
        refresh();
    }

    private int deposit(ItemStack spare) {
        return switch (kind) {
            case PANTRY -> claim.pantry().deposit(spare);
            case POTIONS -> claim.potionStore().deposit(spare);
            case EQUIPMENT -> claim.equipment().deposit(spare);
        };
    }

    @Override
    protected void decorate() {
        super.decorate();
        int cap = switch (kind) {
            case PANTRY -> services.config().pantryMaxStacks();
            case POTIONS -> services.config().potionStoreMaxStacks();
            case EQUIPMENT -> services.config().equipmentMaxStacks();
        };
        int inThere = entries().size();

        toolbar(4, Icons.of(Material.HOPPER, "<green>Put in what you are holding",
                        "<gray>" + inThere + " of " + cap + " stack(s) used",
                        switch (kind) {
                            case PANTRY -> "<dark_gray>food only";
                            case POTIONS -> "<dark_gray>potions only";
                            case EQUIPMENT -> "<dark_gray>anything the rules should hand out";
                        }),
                click -> {
                    if (!mayManage()) {
                        services.messages().send(viewer, "error.no-claim-permission");
                        return;
                    }
                    ItemStack holding = viewer.getInventory().getItemInMainHand();
                    if (holding.getType().isAir()) {
                        services.messages().send(viewer, "claim.hold-an-item");
                        return;
                    }
                    boolean acceptable = switch (kind) {
                        case PANTRY -> ClaimPantry.isFood(holding);
                        case POTIONS -> PotionStore.isPotion(holding);
                        case EQUIPMENT -> true;
                    };
                    if (!acceptable) {
                        services.messages().send(viewer, kind == Kind.PANTRY
                                ? "claim.pantry-food-only" : "claim.potions-only");
                        return;
                    }
                    if (inThere >= cap) {
                        services.messages().send(viewer, "claim.store-full", "cap", String.valueOf(cap));
                        return;
                    }
                    int accepted = deposit(holding.clone());
                    if (accepted <= 0) {
                        services.messages().send(viewer, "claim.store-full", "cap", String.valueOf(cap));
                        return;
                    }
                    // Only what was actually taken, so a partial deposit does not vanish the rest.
                    holding.setAmount(Math.max(0, holding.getAmount() - accepted));
                    claim.markDirty();
                    services.claimService().saveAsync(claim);
                    refresh();
                });

        if (kind == Kind.PANTRY) {
            toolbar(6, Icons.of(Material.COOKED_BEEF, "<white>Feed at hunger: <yellow>"
                            + claim.pantry().threshold() + "/20",
                            "<dark_gray>a hungrier threshold means fewer, bigger meals",
                            "",
                            "<dark_gray>Click to choose a number."),
                    click -> {
                        if (!mayManage()) {
                            services.messages().send(viewer, "error.no-claim-permission");
                            return;
                        }
                        // Core's picker rather than ±1 nudges. Twenty is not many clicks, but it is
                        // the same screen as every other amount on the server, which is the point.
                        new de.raindancer.core.ui.choose.AmountChooser(viewer, services.brand(), this,
                                "Feed at hunger", claim.pantry().threshold(), 0, 20,
                                chosen -> {
                                    claim.pantry().threshold(chosen);
                                    claim.markDirty();
                                    services.claimService().saveAsync(claim);
                                }).open();
                    });
        }
    }

    private boolean mayManage() {
        return services.rights().canManage(claim, viewer,
                de.raindancer.modules.claims.model.ClaimAdminPermission.MANAGE_BANK);
    }
}
