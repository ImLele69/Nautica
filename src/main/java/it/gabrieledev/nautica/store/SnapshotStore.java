package it.gabrieledev.nautica.store;

import it.gabrieledev.nautica.blueprint.Blueprint;
import it.gabrieledev.nautica.blueprint.BlueprintIO;
import it.gabrieledev.nautica.ship.ShipRecord;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

// one header and one blueprint file per ship, each replaced atomically (temp file, fsync, rename).
//
// Every write goes through a single thread, so a synchronous write issued after an asynchronous one can
// never be overtaken by it. Serialization happens on the caller's thread, which is the main thread:
// the store never touches Bukkit objects
public final class SnapshotStore {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path ships;
    private final Path archive;
    private final Logger log;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Nautica-IO");
        t.setDaemon(true);
        return t;
    });

    public SnapshotStore(Path dataFolder, Logger log) throws IOException {
        this.ships = dataFolder.resolve("ships");
        this.archive = dataFolder.resolve("archive");
        this.log = log;
        Files.createDirectories(ships);
        Files.createDirectories(archive);
    }

    public CompletableFuture<Void> writeRecord(ShipRecord record) {
        String text = record.write();
        Path target = recordPath(record.id());
        return CompletableFuture.runAsync(() -> atomicWrite(target, text), io);
    }

    // blocks until the header is on disk. Used at state transitions that must precede world changes
    public void writeRecordNow(ShipRecord record) throws IOException {
        await(writeRecord(record));
    }

    public CompletableFuture<Void> writeBlueprint(UUID id, Blueprint blueprint) {
        String text = BlueprintIO.write(blueprint);
        blueprint.clearDirty();
        Path target = blueprintPath(id);
        return CompletableFuture.runAsync(() -> atomicWrite(target, text), io);
    }

    public void writeBlueprintNow(UUID id, Blueprint blueprint) throws IOException {
        await(writeBlueprint(id, blueprint));
    }

    // the departure write: blueprint first, header second, in one task. If the blueprint cannot be
    // written the header is not touched, so the record never points at a snapshot that is not there
    public CompletableFuture<Void> writeDeparture(ShipRecord record, Blueprint blueprint) {
        String bpText = BlueprintIO.write(blueprint);
        blueprint.clearDirty();
        String recordText = record.write();
        Path bpTarget = blueprintPath(record.id());
        Path recordTarget = recordPath(record.id());
        return CompletableFuture.runAsync(() -> {
            atomicWrite(bpTarget, bpText);
            atomicWrite(recordTarget, recordText);
        }, io);
    }

    public Blueprint readBlueprint(UUID id) throws IOException {
        Path p = blueprintPath(id);
        if (!Files.exists(p)) {
            return null;
        }
        try {
            return BlueprintIO.read(Files.readString(p, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException | RuntimeException e) {
            throw new IOException("unreadable blueprint for " + id + ": " + e.getMessage(), e);
        }
    }

    public List<ShipRecord> loadRecords() {
        List<ShipRecord> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(ships, "*.yml")) {
            for (Path p : stream) {
                if (p.getFileName().toString().endsWith(".blueprint.yml")) {
                    continue;
                }
                try {
                    YamlConfiguration y = new YamlConfiguration();
                    y.loadFromString(Files.readString(p, StandardCharsets.UTF_8));
                    out.add(ShipRecord.read(y));
                } catch (IOException | InvalidConfigurationException | RuntimeException e) {
                    log.log(Level.SEVERE, "Unreadable ship record, left on disk: " + p.getFileName(), e);
                }
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "Can't read the ships folder", e);
        }
        return out;
    }

    // keeps a copy of the current blueprint in archive/, pruning to keep copies per ship
    public CompletableFuture<Void> archive(UUID id, int keep) {
        Path source = blueprintPath(id);
        return CompletableFuture.runAsync(() -> {
            try {
                if (!Files.exists(source) || keep <= 0) {
                    return;
                }
                Path copy = archive.resolve(id + "-" + LocalDateTime.now().format(STAMP) + ".blueprint.yml");
                Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
                List<Path> mine = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(archive, id + "-*.blueprint.yml")) {
                    stream.forEach(mine::add);
                }
                mine.sort(null);
                for (int i = 0; i < mine.size() - keep; i++) {
                    Files.deleteIfExists(mine.get(i));
                }
            } catch (IOException e) {
                log.log(Level.WARNING, "Archiving the blueprint failed for " + id, e);
            }
        }, io);
    }

    // moves both files to archive/ instead of deleting them: a removed ship can still be inspected
    public CompletableFuture<Void> retire(UUID id) {
        return CompletableFuture.runAsync(() -> {
            String stamp = LocalDateTime.now().format(STAMP);
            try {
                if (Files.exists(recordPath(id))) {
                    Files.move(recordPath(id), archive.resolve(id + "-" + stamp + ".retired.yml"));
                }
                if (Files.exists(blueprintPath(id))) {
                    Files.move(blueprintPath(id), archive.resolve(id + "-" + stamp + ".retired.blueprint.yml"));
                }
            } catch (IOException e) {
                log.log(Level.WARNING, "Retiring the files of ship " + id + " failed", e);
            }
        }, io);
    }

    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(30, TimeUnit.SECONDS)) {
                log.severe("Disk writes still pending after 30 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Path recordPath(UUID id) {
        return ships.resolve(id + ".yml");
    }

    private Path blueprintPath(UUID id) {
        return ships.resolve(id + ".blueprint.yml");
    }

    private void atomicWrite(Path target, String text) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel dir = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
                dir.force(true);
            } catch (IOException ignored) {
                // not every filesystem lets a directory be opened for fsync
            }
        } catch (IOException e) {
            throw new SnapshotWriteException(target, e);
        }
    }

    private static void await(CompletableFuture<Void> f) throws IOException {
        try {
            f.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("scrittura interrotta", e);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("scrittura su disco oltre 30 secondi", e);
        }
    }

    public static final class SnapshotWriteException extends RuntimeException {
        SnapshotWriteException(Path target, IOException cause) {
            super("scrittura atomica fallita: " + target.getFileName() + ": " + cause.getMessage(), cause);
        }
    }
}
