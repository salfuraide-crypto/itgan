package itqan;

import java.awt.Desktop;
import java.net.BindException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Starts the Itqan platform: loads the data, registers the API and serves the web app. */
public final class App {
    private App() { }

    public static void main(String[] args) throws Exception {
        run(args);
    }

    public static void run(String[] args) throws Exception {
        Path base = locateProjectDir();
        // On a host, ITQAN_DATA points at a persistent disk so data survives redeploys.
        Path dataDir = Paths.get(env("ITQAN_DATA", base.resolve("data").toString()));
        Path uploads = dataDir.resolve("uploads");
        Files.createDirectories(uploads);

        // DATABASE_URL (e.g. a free Neon/Supabase PostgreSQL) keeps data when the host's disk is temporary.
        String databaseUrl = env("DATABASE_URL", "");
        Backend backend = databaseUrl.isEmpty()
                ? new Backend.FileBackend(dataDir.resolve("itqan.json"))
                : new Backend.PostgresBackend(databaseUrl);
        Store store = new Store(backend);
        Api api = new Api(store, uploads);
        synchronized (store) {
            // ITQAN_TRAINER_* are the older names of the same settings and still work.
            api.seedAdmin(env("ITQAN_ADMIN_EMAIL", env("ITQAN_TRAINER_EMAIL", "admin@itqan.test")),
                    env("ITQAN_ADMIN_PASSWORD", env("ITQAN_TRAINER_PASSWORD", "itqan123")));
            store.flush();
        }

        Server server = new Server(store, base.resolve("web"), uploads);
        api.register(server);

        int port = port(args);
        String host = env("ITQAN_HOST", "127.0.0.1");
        // If the port is taken by another program, use the next free one.
        for (int attempt = 0; ; attempt++) {
            try {
                server.start(host, port);
                break;
            } catch (BindException e) {
                if (attempt >= 20) {
                    System.err.println("No free port found near " + port + ". Pass a port as the first argument (e.g. 9090).");
                    System.exit(1);
                    return;
                }
                System.out.println("Port " + port + " is in use by another program, trying " + (port + 1));
                port++;
            }
        }

        String url = "http://localhost:" + port + "/";
        System.out.println("Itqan is running at " + url);
        System.out.println("Trainer portal:     " + url + "#/trainer/login");
        System.out.println("Data stored in:     " + backend.describe());
        System.out.println("Uploaded files in:  " + uploads.toAbsolutePath());
        if (!"0".equals(System.getenv("ITQAN_OPEN_BROWSER"))) openBrowser(url);
    }

    /** The folder holding web/index.html: the working directory or one of its parents. */
    private static Path locateProjectDir() throws Exception {
        Path dir = Paths.get("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("web").resolve("index.html"))) return p;
        }
        Path classes = Paths.get(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for (Path p = classes; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("web").resolve("index.html"))) return p;
        }
        System.err.println("Could not find the web folder; run the app from the project directory.");
        return dir;
    }

    private static int port(String[] args) {
        String value = args.length > 0 ? args[0] : env("PORT", "8080");
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 8080;
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.trim().isEmpty() ? fallback : v.trim();
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Throwable ignored) {
            // No desktop browser available; the URL is printed above.
        }
    }
}
