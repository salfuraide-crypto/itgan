package itqan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * In-memory tables of JSON rows, persisted to a single JSON file.
 * Not thread-safe by itself: callers synchronize on the store instance.
 */
public final class Store {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final Path file;
    private final Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
    private boolean dirty;

    @SuppressWarnings("unchecked")
    public Store(Path file) throws IOException {
        this.file = file;
        if (!Files.exists(file)) return;
        Object root = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        if (!(root instanceof Map)) return;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) root).entrySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            if (e.getValue() instanceof List) {
                for (Object o : (List<?>) e.getValue()) if (o instanceof Map) rows.add((Map<String, Object>) o);
            }
            tables.put(String.valueOf(e.getKey()), rows);
        }
    }

    public static String newId(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) out[i] = ID_CHARS[RANDOM.nextInt(ID_CHARS.length)];
        return new String(out);
    }

    public List<Map<String, Object>> table(String name) {
        return tables.computeIfAbsent(name, k -> new ArrayList<>());
    }

    public Map<String, Object> find(String table, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Map<String, Object> row : table(table)) if (id.equals(row.get("id"))) return row;
        return null;
    }

    public Map<String, Object> first(String table, Predicate<Map<String, Object>> p) {
        for (Map<String, Object> row : table(table)) if (p.test(row)) return row;
        return null;
    }

    public List<Map<String, Object>> where(String table, Predicate<Map<String, Object>> p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : table(table)) if (p.test(row)) out.add(row);
        return out;
    }

    public int count(String table, Predicate<Map<String, Object>> p) {
        int n = 0;
        for (Map<String, Object> row : table(table)) if (p.test(row)) n++;
        return n;
    }

    public Map<String, Object> insert(String table, Map<String, Object> row) {
        Map<String, Object> stored = new LinkedHashMap<>();
        Object id = row.get("id");
        stored.put("id", id == null ? newId(12) : id);
        stored.putAll(row);
        stored.put("createdAt", System.currentTimeMillis());
        table(table).add(stored);
        touch();
        return stored;
    }

    public int removeIf(String table, Predicate<Map<String, Object>> p) {
        int removed = 0;
        for (Iterator<Map<String, Object>> it = table(table).iterator(); it.hasNext(); ) {
            if (p.test(it.next())) { it.remove(); removed++; }
        }
        if (removed > 0) touch();
        return removed;
    }

    /** Marks the data as changed so the next {@link #flush()} writes it to disk. */
    public void touch() { dirty = true; }

    public void flush() {
        if (!dirty) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, Json.stringify(tables).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
