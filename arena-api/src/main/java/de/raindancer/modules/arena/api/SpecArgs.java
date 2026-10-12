package de.raindancer.modules.arena.api;


import java.util.Optional;

/**
 * Reading a fight from what somebody typed: {@code [mode] [kit|own] [stake] [best-of]}, in any order — a mode name is a
 * mode, a number is the stake (or {@code bo3} the rounds), anything else is a kit.
 */
public final class SpecArgs {

    private SpecArgs() {
    }

    public static MatchSpec parse(String[] args, int from, Mode fallback) {
        Mode mode = fallback;
        String kit = null;
        long stake = 0;
        int bestOf = 1;
        int teamSize = 2;
        WaveSpec waves = null;
        for (int index = from; index < args.length; index++) {
            String word = args[index].toLowerCase(java.util.Locale.ROOT);
            Optional<Mode> asMode = Mode.parse(word);
            if (word.matches("bo\\d")) {
                bestOf = Integer.parseInt(word.substring(2));
            } else if (word.matches("\\dv\\d")) {
                mode = Mode.TEAMS;
                teamSize = word.charAt(0) - '0';
            } else if (word.matches("\\d+")) {
                stake = Long.parseLong(word);
            } else if (asMode.isPresent()) {
                mode = asMode.get();
            } else if (WaveSpec.preset(word).isPresent()) {
                mode = Mode.WAVES;
                waves = WaveSpec.preset(word).get();
            } else {
                kit = word;
            }
        }
        return new MatchSpec(mode, kit, bestOf, stake, teamSize, waves);
    }
}
