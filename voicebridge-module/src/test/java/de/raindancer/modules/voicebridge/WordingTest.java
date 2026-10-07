package de.raindancer.modules.voicebridge;

import de.raindancer.modules.api.WordingContract;

import java.nio.file.Path;

class WordingTest implements WordingContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/voicebridge");
    }

    @Override
    public Path messagesFile() {
        return Path.of("src/main/resources/de/raindancer/modules/voicebridge/messages.yml");
    }

    @Override
    public int fewestWordingLines() {
        return 10;
    }
}
