package it.gabrieledev.nautica.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LangFilesTest {

    // a key missing from a translation silently falls back to english, so catch it here
    @Test
    void translationsHaveTheSameKeysAsEnglish() throws Exception {
        Set<String> en = keys("en");
        assertEquals(en, keys("it"));
    }

    private static Set<String> keys(String lang) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        try (Reader r = new InputStreamReader(LangFilesTest.class.getResourceAsStream("/lang/" + lang + ".yml"),
                StandardCharsets.UTF_8)) {
            y.load(r);
        }
        Set<String> out = new TreeSet<>();
        for (String k : y.getKeys(true)) {
            if (!y.isConfigurationSection(k)) {
                out.add(k);
            }
        }
        return out;
    }
}
