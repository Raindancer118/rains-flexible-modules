package de.raindancer.modules.claims.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.claims.Claim;
import de.raindancer.modules.claims.ClaimPantry;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.PotionStore;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Stocking one of the claim's two stores: the pantry, or the potions its effects drink.
 *
 * <h2>One screen, two stores</h2>
 * They are the same interaction — look at what is in there, put something in, take something out — with a
 * different acceptance rule and a different cap. Written twice, one of them ends up with the deposit-from-hand
 * behaviour and the other does not, which is exactly what had happened.
 */
public final class StoreMenu extends PaginatedMenu<ItemStack> {

    /** Which of the two stores this is. */
    public enum Kind {
        PANTRY, POTIONS
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
        return Component.text(kind == Kind.PANTRY ? "Pantry" : "Potions");
    }

    @Override
    protected List<String> helpLines() {
        return kind == Kind.PANTRY
                ? List.of("<gray>Food here is fed to hungry people inside the claim.",
                          "",
                          "<gray>Hold food and click the tile below to put it in.",
                          "<gray>Click a stack to take it back out.")
                : List.of("<gray>Potions here are drunk by the effects this claim grants,",
                          "<gray>when the server asks owners to supply them.",
                          "",
                          "<gray>Hold potions and click the tile below to put them in.");
    }

    @Override
    protected List<ItemStack> entries() {
        return kind == Kind.PANTRY
                ? new ArrayList<>(claim.pantry().items())
                : new ArrayList<>(claim.potionStore().potions());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(kind == Kind.PANTRY ? Material.BREAD : Material.GLASS_BOTTLE,
                "<gray>Empty",
                "<gray>Hold something and click the tile below.");
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
        List<ItemStack> held = kind == Kind.PANTRY ? claim.pantry().items() : claim.potionStore().potions();
        int index = held.indexOf(entry);
        if (index < 0) {
            refresh();
            return;
        }
        ItemStack taken = held.remove(index);
        var leftOver = viewer.getInventory().addItem(taken);
        // Straight back in if it did not fit, rather than on the floor.
        leftOver.values().forEach(spare -> {
            if (kind == Kind.PANTRY) {
                claim.pantry().deposit(spare);
            } else {
                claim.potionStore().deposit(spare);
            }
        });
        if (!leftOver.isEmpty()) {
            services.messages().send(viewer, "claim.bank-inventory-full");
        }
        claim.markDirty();
        services.claimService().saveAsync(claim);
        refresh();
    }

    @Override
    protected void decorate() {
        super.decorate();
        int cap = kind == Kind.PANTRY
                ? services.config().pantryMaxStacks()
                : services.config().potionStoreMaxStacks();
        int inThere = entries().size();

        toolbar(4, Icons.of(Material.HOPPER, "<green>Put in what you are holding",
                        "<gray>" + inThere + " of " + cap + " stack(s) used",
                        kind == Kind.PANTRY
                                ? "<dark_gray>food only"
                                : "<dark_gray>potions only"),
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
                    boolean acceptable = kind == Kind.PANTRY
                            ? ClaimPantry.isFood(holding)
                            : PotionStore.isPotion(holding);
                    if (!acceptable) {
                        services.messages().send(viewer, kind == Kind.PANTRY
                                ? "claim.pantry-food-only" : "claim.potions-only");
                        return;
                    }
                    if (inThere >= cap) {
                        services.messages().send(viewer, "claim.store-full",
                                "cap", String.valueOf(cap));
                        return;
                    }
                    int accepted = kind == Kind.PANTRY
                            ? claim.pantry().deposit(holding.clone())
                            : claim.potionStore().deposit(holding.clone());
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
    }

    private boolean mayManage() {
        return services.rights().canManage(claim, viewer,
                de.raindancer.modules.claims.ClaimAdminPermission.MANAGE_BANK);
    }
}
