package itqan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * In-memory tables of JSON rows, persisted through a {@link Backend} (a JSON file, or PostgreSQL tables).
 * Not thread-safe by itself: callers synchronize on the store instance.
 */
public final class Store {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final Backend backend;
    private final Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
    private boolean dirty;

    public Store(Backend backend) throws IOException {
        this.backend = backend;
        tables.putAll(backend.load());
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
            backend.save(tables);
            dirty = false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
