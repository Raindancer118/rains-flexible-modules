package de.raindancer.modules.cosmetics.service;

import org.bukkit.permissions.Permissible;

/** Who may use a cosmetic: its permission node, or — where the server sells it — having bought it. */
public interface Entitlements {

    /** As cosmetics have always been: the permission node decides, nothing is for sale. */
    Entitlements PERMISSIONS = new Entitlements() {
        @Override
        public boolean allowed(Permissible who, String key, String node) {
            return who.hasPermission(node);
        }

        @Override
        public boolean priced(String key) {
            return false;
        }

        @Override
        public String priceText(String key) {
            return "";
        }
    };

    /** @param node what decides when {@code key} is not for sale */
    boolean allowed(Permissible who, String key, String node);

    boolean priced(String key);

    /** Whether anything is for sale at all. */
    default boolean anySold() {
        return false;
    }

    /** The price as the server's currency writes it, empty when {@code key} is not for sale. */
    String priceText(String key);
}
