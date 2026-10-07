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
import java.util.Properties;

/** Where the store's JSON snapshot is kept: a local file, or a PostgreSQL database on a host. */
public interface Backend {
    /** The saved snapshot, or null when nothing has been saved yet. */
    String load() throws IOException;

    void save(String json) throws IOException;

    String describe();

    /** Saves to a JSON file, written atomically. */
    final class FileBackend implements Backend {
        private final Path file;

        public FileBackend(Path file) { this.file = file; }

        @Override
        public String load() throws IOException {
            return Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : null;
        }

        @Override
        public void save(String json) throws IOException {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
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
     * Saves the snapshot as one row in PostgreSQL, so data survives restarts on hosts
     * whose disk is temporary. Accepts the usual postgresql://user:pass@host/db?sslmode=require URL.
     */
    final class PostgresBackend implements Backend {
        private final String jdbcUrl;
        private final Properties props = new Properties();
        private final String host;
        private Connection connection;

        public PostgresBackend(String databaseUrl) throws IOException {
            try {
                URI uri = new URI(databaseUrl.trim());
                if (uri.getHost() == null) throw new IllegalArgumentException("missing host");
                host = uri.getHost();
                String query = uri.getRawQuery();
                jdbcUrl = "jdbc:postgresql://" + host + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                        + uri.getRawPath() + (query == null ? "?sslmode=require" : "?" + query);
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
                st.execute("CREATE TABLE IF NOT EXISTS itqan_store (id INT PRIMARY KEY, data TEXT NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
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
        public String load() throws IOException {
            try (Statement st = connection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT data FROM itqan_store WHERE id = 1")) {
                return rs.next() ? rs.getString(1) : null;
            } catch (SQLException e) {
                throw new IOException("Could not read from the database: " + e.getMessage(), e);
            }
        }

        @Override
        public void save(String json) throws IOException {
            String sql = "INSERT INTO itqan_store (id, data, updated_at) VALUES (1, ?, now()) "
                    + "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, updated_at = now()";
            for (int attempt = 0; ; attempt++) {
                try (PreparedStatement ps = connection().prepareStatement(sql)) {
                    ps.setString(1, json);
                    ps.executeUpdate();
                    return;
                } catch (SQLException e) {
                    connection = null; // reconnect on the next try (e.g. the database went to sleep)
                    if (attempt >= 1) throw new IOException("Could not save to the database: " + e.getMessage(), e);
                }
            }
        }

        @Override
        public String describe() { return "PostgreSQL at " + host; }
    }
}
