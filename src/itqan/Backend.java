package itqan;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Where the store's tables are kept: a local JSON file, or real tables in a PostgreSQL database on a host. */
public interface Backend {
    /** The saved tables (table name to rows), empty when nothing has been saved yet. */
    Map<String, List<Map<String, Object>>> load() throws IOException;

    void save(Map<String, List<Map<String, Object>>> tables) throws IOException;

    String describe();

    /** Turns a JSON snapshot ({"users": [...], ...}) into tables. */
    @SuppressWarnings("unchecked")
    static Map<String, List<Map<String, Object>>> parseSnapshot(String json) {
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty()) return tables;
        Object root = Json.parse(json);
        if (!(root instanceof Map)) return tables;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) root).entrySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            if (e.getValue() instanceof List) {
                for (Object o : (List<?>) e.getValue()) if (o instanceof Map) rows.add((Map<String, Object>) o);
            }
            tables.put(String.valueOf(e.getKey()), rows);
        }
        return tables;
    }

    /** Saves to a JSON file, written atomically. */
    final class FileBackend implements Backend {
        private final Path file;

        public FileBackend(Path file) { this.file = file; }

        @Override
        public Map<String, List<Map<String, Object>>> load() throws IOException {
            return parseSnapshot(Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : null);
        }

        @Override
        public void save(Map<String, List<Map<String, Object>>> tables) throws IOException {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, Json.stringify(tables).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public String describe() { return file.toAbsolutePath().toString(); }
    }

    /**
     * Keeps each kind of data in its own PostgreSQL table (users, courses, exams, ...), one row per record:
     * id (primary key), data (the record's fields as JSONB), seq (insertion order) and updated_at.
     * Only rows that changed since the last save are written. Accepts the usual
     * postgresql://user:pass@host/db?sslmode=require URL.
     *
     * Databases from older versions kept everything as one JSON snapshot in itqan_store; that snapshot is
     * copied into the tables once, on first start, and itqan_store is left untouched as a backup.
     */
    final class PostgresBackend implements Backend {
        private static final Pattern TABLE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,40}");

        private final String jdbcUrl;
        private final Properties props = new Properties();
        private final String host;
        private Connection connection;
        /** What each table holds in the database right now: table name to (row id to row JSON). */
        private final Map<String, Map<String, String>> saved = new HashMap<>();

        public PostgresBackend(String databaseUrl) throws IOException {
            try {
                URI uri = new URI(databaseUrl.trim());
                if (uri.getHost() == null) throw new IllegalArgumentException("missing host");
                host = uri.getHost();
                String query = uri.getRawQuery();
                jdbcUrl = "jdbc:postgresql://" + host + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                        + uri.getRawPath() + (query == null ? "?sslmode=require" : "?" + query);
                // Pooled URLs (PgBouncer in transaction mode) break server-side prepared statements.
                props.setProperty("prepareThreshold", "0");
                String userInfo = uri.getRawUserInfo();
                if (userInfo != null) {
                    int colon = userInfo.indexOf(':');
                    props.setProperty("user", URLDecoder.decode(colon < 0 ? userInfo : userInfo.substring(0, colon), "UTF-8"));
                    if (colon >= 0) props.setProperty("password", URLDecoder.decode(userInfo.substring(colon + 1), "UTF-8"));
                }
            } catch (Exception e) {
                throw new IOException("DATABASE_URL is not a valid postgresql:// URL", e);
            }
            try (Statement st = connection().createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS itqan_tables (name TEXT PRIMARY KEY)");
            } catch (SQLException e) {
                throw new IOException("Could not connect to the database: " + e.getMessage(), e);
            }
        }

        private Connection connection() throws SQLException {
            if (connection == null || !connection.isValid(5)) {
                if (connection != null) {
                    try { connection.close(); } catch (SQLException ignored) { /* replacing it */ }
                }
                connection = DriverManager.getConnection(jdbcUrl, props);
            }
            return connection;
        }

        @Override
        public Map<String, List<Map<String, Object>>> load() throws IOException {
            try {
                migrateSnapshot();
                Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
                saved.clear();
                for (String name : tableNames()) {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    Map<String, String> ids = new HashMap<>();
                    try (Statement st = connection().createStatement();
                         ResultSet rs = st.executeQuery("SELECT data::text FROM " + quote(name) + " ORDER BY seq")) {
                        while (rs.next()) {
                            Object row = Json.parse(rs.getString(1));
                            if (!(row instanceof Map)) continue;
                            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) row;
                            rows.add(map);
                            ids.put(rowId(map), Json.stringify(map));
                        }
                    }
                    tables.put(name, rows);
                    saved.put(name, ids);
                }
                return tables;
            } catch (SQLException e) {
                throw new IOException("Could not read from the database: " + e.getMessage(), e);
            }
        }

        /** Copies the old one-row JSON snapshot into the tables, once, when the tables are still empty. */
        private void migrateSnapshot() throws SQLException {
            if (!tableNames().isEmpty()) return;
            String json;
            try (Statement st = connection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT to_regclass('itqan_store') IS NOT NULL")) {
                if (!rs.next() || !rs.getBoolean(1)) return;
            }
            try (Statement st = connection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT data FROM itqan_store WHERE id = 1")) {
                if (!rs.next()) return;
                json = rs.getString(1);
            }
            Map<String, List<Map<String, Object>>> tables = parseSnapshot(json);
            if (tables.isEmpty()) return;
            write(tables);
            int rows = 0;
            for (List<Map<String, Object>> t : tables.values()) rows += t.size();
            System.out.println("Moved " + rows + " records from itqan_store into " + tables.size()
                    + " tables (itqan_store is kept as a backup).");
        }

        private List<String> tableNames() throws SQLException {
            List<String> names = new ArrayList<>();
            try (Statement st = connection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT name FROM itqan_tables ORDER BY name")) {
                while (rs.next()) names.add(rs.getString(1));
            }
            return names;
        }

        @Override
        public void save(Map<String, List<Map<String, Object>>> tables) throws IOException {
            for (int attempt = 0; ; attempt++) {
                try {
                    write(tables);
                    return;
                } catch (SQLException e) {
                    System.err.println("Database save failed (attempt " + (attempt + 1) + "): " + e.getMessage());
                    connection = null; // reconnect on the next try (e.g. the database went to sleep)
                    if (attempt >= 2) throw new IOException("Could not save to the database: " + e.getMessage(), e);
                }
            }
        }

        /** Writes the rows that were added, changed or removed since the last save, in one transaction. */
        private void write(Map<String, List<Map<String, Object>>> tables) throws SQLException {
            Map<String, Map<String, String>> next = new HashMap<>();
            Connection c = connection();
            c.setAutoCommit(false);
            try {
                for (Map.Entry<String, List<Map<String, Object>>> e : tables.entrySet()) {
                    String name = e.getKey();
                    if (!TABLE_NAME.matcher(name).matches() || name.startsWith("itqan_")) {
                        throw new SQLException("Unsupported table name: " + name);
                    }
                    Map<String, String> before = saved.get(name);
                    if (before == null) {
                        createTable(c, name);
                        before = new HashMap<>();
                    }
                    Map<String, String> now = new LinkedHashMap<>();
                    for (Map<String, Object> row : e.getValue()) now.put(rowId(row), Json.stringify(row));

                    try (PreparedStatement upsert = c.prepareStatement("INSERT INTO " + quote(name)
                            + " (id, data, updated_at) VALUES (?, ?::jsonb, now()) "
                            + "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, updated_at = now()");
                         PreparedStatement delete = c.prepareStatement("DELETE FROM " + quote(name) + " WHERE id = ?")) {
                        boolean upserts = false;
                        boolean deletes = false;
                        for (Map.Entry<String, String> row : now.entrySet()) {
                            if (row.getValue().equals(before.get(row.getKey()))) continue;
                            upsert.setString(1, row.getKey());
                            upsert.setString(2, row.getValue());
                            upsert.addBatch();
                            upserts = true;
                        }
                        Set<String> gone = new HashSet<>(before.keySet());
                        gone.removeAll(now.keySet());
                        for (String id : gone) {
                            delete.setString(1, id);
                            delete.addBatch();
                            deletes = true;
                        }
                        if (deletes) delete.executeBatch();
                        if (upserts) upsert.executeBatch();
                    }
                    next.put(name, now);
                }
                c.commit();
            } catch (SQLException e) {
                try { c.rollback(); } catch (SQLException ignored) { /* the connection is being replaced */ }
                throw e;
            } finally {
                try { c.setAutoCommit(true); } catch (SQLException ignored) { /* the connection is being replaced */ }
            }
            for (Map.Entry<String, Map<String, String>> e : next.entrySet()) saved.put(e.getKey(), e.getValue());
        }

        private static void createTable(Connection c, String name) throws SQLException {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS " + quote(name) + " (id TEXT PRIMARY KEY, data JSONB NOT NULL, "
                        + "seq BIGSERIAL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO itqan_tables (name) VALUES (?) ON CONFLICT DO NOTHING")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
        }

        private static String rowId(Map<String, Object> row) {
            return String.valueOf(row.get("id"));
        }

        private static String quote(String name) {
            return "\"" + name + "\"";
        }

        @Override
        public String describe() { return "PostgreSQL tables at " + host; }
    }
}
