package it.gabrieledev.nautica.prefab;

import it.gabrieledev.nautica.blueprint.Blueprint;
import it.gabrieledev.nautica.blueprint.BlueprintIO;
import org.bukkit.configuration.InvalidConfigurationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

// prefabs are blueprint files in prefabs/, same format as the snapshots
public final class PrefabStore {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    private final Path dir;
    private final Logger log;

    public PrefabStore(Path dataFolder, Logger log) throws IOException {
        this.dir = dataFolder.resolve("prefabs");
        this.log = log;
        Files.createDirectories(dir);
    }

    public static boolean validName(String name) {
        return NAME.matcher(name).matches();
    }

    // writes the built-in prefabs that are missing, never overwriting a file an admin may have edited
    public void installBuiltins() {
        for (Map.Entry<String, Blueprint> e : BuiltinPrefabs.all().entrySet()) {
            Path p = dir.resolve(e.getKey() + ".yml");
            if (Files.exists(p)) {
                continue;
            }
            try {
                Files.writeString(p, BlueprintIO.write(e.getValue()), StandardCharsets.UTF_8);
                log.info("Installed builtin prefab " + e.getKey() + " (" + e.getValue().size() + " blocks)");
            } catch (IOException ex) {
                log.warning("Couldn't write prefab " + e.getKey() + ": " + ex.getMessage());
            }
        }
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.yml")) {
            for (Path p : stream) {
                String n = p.getFileName().toString();
                out.add(n.substring(0, n.length() - 4));
            }
        } catch (IOException e) {
            log.warning("Cartella prefabs illeggibile: " + e.getMessage());
        }
        out.sort(null);
        return out;
    }

    public Blueprint load(String name) throws IOException {
        String n = name.toLowerCase(Locale.ROOT);
        if (!validName(n)) {
            return null;
        }
        Path p = dir.resolve(n + ".yml");
        if (!Files.exists(p)) {
            return null;
        }
        try {
            return BlueprintIO.read(Files.readString(p, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException | RuntimeException e) {
            throw new IOException("prefab " + n + " is unreadable: " + e.getMessage(), e);
        }
    }

    public void save(String name, Blueprint bp) throws IOException {
        String n = name.toLowerCase(Locale.ROOT);
        if (!validName(n)) {
            throw new IOException("invalid name: " + name);
        }
        Files.writeString(dir.resolve(n + ".yml"), BlueprintIO.write(bp), StandardCharsets.UTF_8);
    }
}
