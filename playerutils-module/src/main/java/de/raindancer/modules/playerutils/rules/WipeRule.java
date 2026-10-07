package de.raindancer.modules.playerutils.rules;

import de.raindancer.core.moderation.players.PlayerBody;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Which parts of somebody a wipe takes, from the words typed after it. */
public final class WipeRule implements IPlayerUtilsRule {

    private static final Map<String, PlayerBody.Wipe> WORDS = Map.of(
            "inventory", PlayerBody.Wipe.INVENTORY,
            "enderchest", PlayerBody.Wipe.ENDER_CHEST,
            "advancements", PlayerBody.Wipe.ADVANCEMENTS,
            "xp", PlayerBody.Wipe.EXPERIENCE,
            "effects", PlayerBody.Wipe.EFFECTS);

    public Set<PlayerBody.Wipe> parts(List<String> switches) {
        if (switches.contains("all")) {
            return EnumSet.allOf(PlayerBody.Wipe.class);
        }
        Set<PlayerBody.Wipe> named = EnumSet.noneOf(PlayerBody.Wipe.class);
        for (String word : switches) {
            PlayerBody.Wipe part = WORDS.get(word);
            if (part != null) {
                named.add(part);
            }
        }
        return named.isEmpty() ? PlayerBody.Wipe.STANDARD : named;
    }

    public String describe(Set<PlayerBody.Wipe> parts) {
        List<String> words = new ArrayList<>();
        for (PlayerBody.Wipe part : PlayerBody.Wipe.values()) {
            if (parts.contains(part)) {
                words.add(switch (part) {
                    case INVENTORY -> "inventory";
                    case ENDER_CHEST -> "ender chest";
                    case ADVANCEMENTS -> "advancements";
                    case EXPERIENCE -> "experience";
                    case EFFECTS -> "effects";
                });
            }
        }
        if (words.size() <= 1) {
            return words.isEmpty() ? "nothing" : words.getFirst();
        }
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.getLast();
    }

    @Override
    public String describe() {
        return "which parts of somebody a wipe takes";
    }
}
