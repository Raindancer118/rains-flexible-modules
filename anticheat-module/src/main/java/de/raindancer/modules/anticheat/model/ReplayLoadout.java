package de.raindancer.modules.anticheat.model;

import java.util.ArrayList;
import java.util.List;

/**
 * What a player wore and carried at the moment a check alerted: helmet to boots, the off hand, the nine
 * hotbar slots. Each item as Paper writes it ({@code ItemStack#serializeAsBytes}, in Base64), null for an
 * empty slot — so a replay can dress its figure exactly as they were.
 */
public record ReplayLoadout(List<String> armour, String offHand, List<String> hotbar) {

    public ReplayLoadout {
        armour = sized(armour, 4);
        hotbar = sized(hotbar, 9);
    }

    private static List<String> sized(List<String> given, int size) {
        List<String> fixed = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            fixed.add(given != null && i < given.size() ? given.get(i) : null);
        }
        return java.util.Collections.unmodifiableList(fixed);
    }
}
