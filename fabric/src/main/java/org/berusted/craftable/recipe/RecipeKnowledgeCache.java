package org.berusted.craftable.recipe;

import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Rebuildable file boundary. The worker receives only immutable strings, never game objects. */
final class RecipeKnowledgeCache implements AutoCloseable {
    static final int MAX_KEYS = 16_384, MAX_REFERENCES = 262_144, MAX_BYTES = 16 * 1024 * 1024;
    private final Path file;
    private final Consumer<String> report;
    private final java.util.concurrent.ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "Craftable recipe knowledge I/O");
        thread.setDaemon(true);
        return thread;
    });
    private long epoch;
    private Pending pending;
    private boolean running, closed;
    private CompletableFuture<Void> idle = CompletableFuture.completedFuture(null);

    RecipeKnowledgeCache(Path file, Consumer<String> report) {
        this.file = file.toAbsolutePath().normalize();
        this.report = report;
    }

    record Snapshot(String fingerprint, Map<List<String>, List<String>> relations) {
        Snapshot {
            if (!fingerprint.matches("[0-9a-f]{64}") || relations.size() > MAX_KEYS)
                throw new IllegalArgumentException("knowledge header limit");
            var copy = new TreeMap<List<String>, List<String>>(RecipeKnowledgeCache::compareKeys);
            int options = 0, producers = 0;
            for (var entry : relations.entrySet()) {
                var key = List.copyOf(entry.getKey());
                var value = List.copyOf(entry.getValue());
                validateIds(key);
                validateIds(value);
                options = Math.addExact(options, key.size());
                producers = Math.addExact(producers, value.size());
                if (options > MAX_REFERENCES || producers > MAX_REFERENCES)
                    throw new IllegalArgumentException("knowledge reference limit");
                copy.put(key, value);
            }
            relations = java.util.Collections.unmodifiableMap(copy);
        }
    }

    private record Pending(long epoch, Snapshot snapshot) {}

    synchronized long advance() {
        pending = null;
        return ++epoch;
    }

    Snapshot load(String fingerprint) {
        long start = System.nanoTime();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (Files.isSymbolicLink(file) || Files.size(file) > MAX_BYTES) throw new IOException("file size/type");
            // Streaming, fixed schema parsing bounds memory and nesting before
            // allocating a tree. The stream limit also covers a growing file.
            try (var input = new FilterInputStream(Files.newInputStream(file)) {
                int bytes;
                @Override public int read() throws IOException {
                    int value = super.read();
                    if (value >= 0 && ++bytes > MAX_BYTES) throw new IOException("byte limit");
                    return value;
                }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    int n = in.read(b, off, Math.min(len, MAX_BYTES - bytes + 1));
                    if (n > 0 && (bytes += n) > MAX_BYTES) throw new IOException("byte limit");
                    return n;
                }
            }; var reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()))) {
                reader.setLenient(false);
                reader.beginObject();
                name(reader, "version");
                if (reader.nextInt() != 1) throw new IOException("format version");
                name(reader, "fingerprint");
                if (!fingerprint.equals(reader.nextString())) throw new IOException("recipe fingerprint changed");
                name(reader, "relations");
                reader.beginArray();
                Map<List<String>, List<String>> relations = new LinkedHashMap<>();
                int options = 0, producers = 0;
                while (reader.hasNext()) {
                    if (relations.size() >= MAX_KEYS) throw new IOException("key limit");
                    reader.beginObject();
                    name(reader, "items");
                    var items = ids(reader, MAX_REFERENCES - options);
                    options += items.size();
                    name(reader, "recipes");
                    var recipes = ids(reader, MAX_REFERENCES - producers);
                    producers += recipes.size();
                    reader.endObject();
                    if (relations.put(items, recipes) != null) throw new IOException("duplicate key");
                }
                reader.endArray();
                name(reader, "checksum");
                String expected = reader.nextString();
                reader.endObject();
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("trailing content");
                var snapshot = new Snapshot(fingerprint, relations);
                if (!checksum(snapshot).equals(expected)) throw new IOException("checksum");
                report.accept("read keys=" + relations.size() + " ms=" + (System.nanoTime() - start) / 1e6);
                return snapshot;
            }
        } catch (IOException | RuntimeException exception) {
            report.accept("ignored " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return null;
        }
    }

    synchronized void submit(long generation, Snapshot snapshot) {
        if (closed || generation != epoch) return;
        // One worker and one replaceable pending snapshot, never one queued
        // task per craft. advance() invalidates both pending and in-flight data.
        pending = new Pending(generation, snapshot);
        if (running) return;
        running = true;
        idle = new CompletableFuture<>();
        writer.execute(this::drain);
    }

    private void drain() {
        while (true) {
            Pending next;
            synchronized (this) {
                next = pending;
                pending = null;
                if (next == null) {
                    running = false;
                    idle.complete(null);
                    return;
                }
            }
            write(next);
        }
    }

    private void write(Pending next) {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        boolean ownedTemporary = false;
        try {
            Files.createDirectories(file.getParent());
            if (Files.isSymbolicLink(file) || Files.isSymbolicLink(temporary)) throw new IOException("symbolic cache file");
            ownedTemporary = true;
            try (var output = new FilterOutputStream(Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                int bytes;
                @Override public void write(int value) throws IOException {
                    if (++bytes > MAX_BYTES) throw new IOException("byte limit");
                    out.write(value);
                }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    if (len > MAX_BYTES - bytes) throw new IOException("byte limit");
                    bytes += len;
                    out.write(b, off, len);
                }
            }; var json = new JsonWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                json.beginObject().name("version").value(1).name("fingerprint").value(next.snapshot.fingerprint());
                json.name("relations").beginArray();
                for (var entry : next.snapshot.relations().entrySet()) {
                    json.beginObject().name("items").beginArray();
                    for (String item : entry.getKey()) json.value(item);
                    json.endArray().name("recipes").beginArray();
                    for (String recipe : entry.getValue()) json.value(recipe);
                    json.endArray().endObject();
                }
                json.endArray().name("checksum").value(checksum(next.snapshot)).endObject();
            }
            // This short publication section excludes a generation change.
            // Never delete the old valid file first or fall back to a torn copy.
            synchronized (this) {
                if (closed || next.epoch != epoch) return;
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            report.accept("saved keys=" + next.snapshot.relations().size() + " bytes=" + Files.size(file));
        } catch (IOException | RuntimeException exception) {
            report.accept("write skipped " + exception.getClass().getSimpleName());
        } finally {
            if (ownedTemporary) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* Only our fixed temporary file. */ }
            }
        }
    }

    boolean awaitIdle() {
        CompletableFuture<Void> completion;
        synchronized (this) { completion = idle; }
        try { completion.get(5, TimeUnit.SECONDS); return true; }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); return false; }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) { return false; }
    }

    @Override public void close() {
        boolean completed = awaitIdle();
        synchronized (this) { closed = true; pending = null; epoch++; }
        writer.shutdownNow();
        if (!completed) report.accept("shutdown timed out; cache can be rebuilt");
    }

    private static void name(JsonReader reader, String expected) throws IOException {
        if (!expected.equals(reader.nextName())) throw new IOException("schema field");
    }

    private static List<String> ids(JsonReader reader, int remaining) throws IOException {
        var result = new java.util.ArrayList<String>();
        reader.beginArray();
        while (reader.hasNext()) {
            if (result.size() >= remaining) throw new IOException("reference limit");
            if (reader.peek() != JsonToken.STRING) throw new IOException("ID type");
            String id = reader.nextString();
            if (!validId(id) || (!result.isEmpty() && result.getLast().compareTo(id) >= 0))
                throw new IOException("noncanonical ID");
            result.add(id);
        }
        reader.endArray();
        return List.copyOf(result);
    }

    private static void validateIds(List<String> ids) {
        String previous = null;
        for (String id : ids) {
            if (!validId(id) || (previous != null && previous.compareTo(id) >= 0))
                throw new IllegalArgumentException("noncanonical ID");
            previous = id;
        }
    }

    private static boolean validId(String id) {
        return id.length() <= 512 && id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+");
    }

    private static int compareKeys(List<String> a, List<String> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int difference = a.get(i).compareTo(b.get(i));
            if (difference != 0) return difference;
        }
        return Integer.compare(a.size(), b.size());
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static void field(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    static String hex(MessageDigest digest) { return HexFormat.of().formatHex(digest.digest()); }

    private static String checksum(Snapshot snapshot) {
        var digest = digest();
        field(digest, "craftable-knowledge-v1");
        field(digest, snapshot.fingerprint());
        for (var entry : snapshot.relations().entrySet()) {
            field(digest, "items:" + entry.getKey().size());
            entry.getKey().forEach(id -> field(digest, id));
            field(digest, "recipes:" + entry.getValue().size());
            entry.getValue().forEach(id -> field(digest, id));
        }
        return hex(digest);
    }

    /** Canonical recipe JSON hashing: map iteration order must not change identity. */
    static void json(MessageDigest digest, JsonElement value) { json(digest, value, 0); }

    private static void json(MessageDigest digest, JsonElement value, int depth) {
        if (depth > 32) throw new IllegalArgumentException("recipe nesting");
        if (value.isJsonObject()) {
            field(digest, "object");
            value.getAsJsonObject().keySet().stream().sorted().forEach(key -> {
                field(digest, key);
                json(digest, value.getAsJsonObject().get(key), depth + 1);
            });
            field(digest, "end-object");
        } else if (value.isJsonArray()) {
            field(digest, "array");
            value.getAsJsonArray().forEach(child -> json(digest, child, depth + 1));
            field(digest, "end-array");
        } else field(digest, value.toString());
    }
}
