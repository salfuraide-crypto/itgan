package itqan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * End-to-end checks for the whole platform, with no test library: starts a real server on a free port with
 * a throwaway data folder, then drives it over HTTP like the browser does. Run main(); it prints each check
 * and exits with status 1 if any failed. Your own data/ folder is never touched.
 */
public final class ItqanTest {
    private static final String ADMIN_EMAIL = "admin@itqan.test";
    private static final String ADMIN_PASSWORD = "admin-test-123";

    private static int passed;
    private static final List<String> failures = new ArrayList<>();
    private static String base;
    private static Store store;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("itqan-test");
        try {
            startServer(dir);
            run("Sign-up and sign-in", ItqanTest::authentication);
            run("Who may open what", ItqanTest::permissions);
            run("Admin manages trainers", ItqanTest::adminTrainers);
            run("Course, bank, exam and grading", ItqanTest::learningFlow);
            run("Video upload and protected playback", ItqanTest::media);
            run("Deleting keeps no leftovers", ItqanTest::cascadeDeletes);
            run("Security protections", ItqanTest::security);
            run("Data survives a restart", () -> persistence(dir));
            run("R2 request signing (AWS examples)", ItqanTest::r2Signing);
        } finally {
            deleteTree(dir);
        }
        System.out.println();
        System.out.println(failures.isEmpty()
                ? "ALL PASSED (" + passed + " checks)"
                : failures.size() + " FAILED, " + passed + " passed:\n  - " + String.join("\n  - ", failures));
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    // ================================================================ scenarios

    private static void authentication() throws Exception {
        Client s = new Client();
        Res r = s.post("/api/auth/register", obj("name", "متدرب أول", "email", "Student1@itqan.test", "password", "secret123"));
        check(r.status == 200, "register succeeds");
        check("student1@itqan.test".equals(get(r.json, "user", "email")), "email is stored in lower case");
        check("trainee".equals(get(r.json, "user", "role")), "new accounts are trainees");
        check(get(s.get("/api/me").json, "user", "email") != null, "registering signs you in");

        check(new Client().post("/api/auth/register", obj("name", "مكرر", "email", "student1@itqan.test", "password", "secret123")).status == 409,
                "the same email cannot register twice");
        check(new Client().post("/api/auth/register", obj("name", "قصير", "email", "x@itqan.test", "password", "123")).status == 400,
                "short passwords are refused");
        check(new Client().post("/api/auth/register", obj("name", "بريد", "email", "not-an-email", "password", "secret123")).status == 400,
                "invalid emails are refused");

        check(new Client().post("/api/auth/login", obj("email", "student1@itqan.test", "password", "wrong-pass")).status == 401,
                "a wrong password is refused");
        check(new Client().post("/api/auth/login", obj("email", "student1@itqan.test", "password", "secret123", "role", "trainer")).status == 403,
                "trainees cannot use the trainer portal");
        check(new Client().post("/api/auth/login", obj("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD)).status == 403,
                "staff cannot use the trainee sign-in");

        Client again = new Client();
        check(again.post("/api/auth/login", obj("email", "student1@itqan.test", "password", "secret123")).status == 200, "sign-in works");
        again.post("/api/auth/logout", obj());
        check(get(again.get("/api/me").json, "user") == null, "signing out ends the session");
    }

    private static void permissions() throws Exception {
        Client anon = new Client();
        check(anon.get("/api/a/overview").status == 401, "visitors must sign in for admin pages");
        check(anon.get("/api/t/courses").status == 401, "visitors must sign in for trainer pages");
        check(anon.get("/api/s/home").status == 401, "visitors must sign in for trainee pages");

        Client trainee = trainee("perm-trainee");
        check(trainee.get("/api/a/overview").status == 403, "trainees cannot open admin pages");
        check(trainee.get("/api/t/courses").status == 403, "trainees cannot open trainer pages");
        check(trainee.post("/api/t/courses", obj("title", "دورة")).status == 403, "trainees cannot create courses");

        Client trainer = trainer("perm-trainer");
        check(trainer.get("/api/a/overview").status == 403, "trainers cannot open admin pages");
        check(trainer.get("/api/s/home").status == 403, "trainers cannot open trainee pages");

        Res forged = trainee.send("POST", "/api/auth/logout", "{}", false);
        check(forged.status == 403, "requests without the app's header are refused (other sites cannot forge them)");
    }

    private static void adminTrainers() throws Exception {
        Client admin = admin();
        Res created = admin.post("/api/a/trainers", obj("name", "مدرب جديد", "email", "new-trainer@itqan.test", "password", "secret123"));
        check(created.status == 200, "admin can add a trainer");
        String id = (String) get(created.json, "trainer", "id");
        check(admin.post("/api/a/trainers", obj("name", "مكرر", "email", "new-trainer@itqan.test", "password", "secret123")).status == 409,
                "a trainer email cannot be reused");

        Client t = new Client();
        check(t.post("/api/auth/login", obj("email", "new-trainer@itqan.test", "password", "secret123", "role", "trainer")).status == 200,
                "the new trainer can sign in");
        check(admin.put("/api/a/trainers/" + id + "/password", obj("password", "changed123")).status == 200, "admin can reset a password");
        check(get(t.get("/api/me").json, "user") == null, "resetting a password signs the trainer out");
        check(new Client().post("/api/auth/login", obj("email", "new-trainer@itqan.test", "password", "changed123", "role", "trainer")).status == 200,
                "the new password works");
        check(admin.delete("/api/a/trainers/" + id).status == 200, "admin can delete a trainer");
        check(new Client().post("/api/auth/login", obj("email", "new-trainer@itqan.test", "password", "changed123", "role", "trainer")).status == 401,
                "a deleted trainer cannot sign in");
    }

    private static void learningFlow() throws Exception {
        Client trainer = trainer("flow-trainer");
        String course = (String) get(trainer.post("/api/t/courses", obj("title", "أساسيات جافا", "description", "")).json, "course", "id");
        check(course != null, "trainer creates a course");
        check(trainer.post("/api/t/courses", obj("title", "")).status == 400, "a course needs a title");

        Client other = trainer("flow-other");
        check(other.put("/api/t/courses/" + course, obj("title", "اختراق")).status == 404, "another trainer cannot edit the course");
        check(other.delete("/api/t/courses/" + course).status == 404, "another trainer cannot delete the course");

        String bank = (String) get(trainer.post("/api/t/banks", obj("name", "بنك ١", "courseId", course, "audience", "all")).json, "bank", "id");
        String q1 = (String) get(trainer.post("/api/t/banks/" + bank + "/questions",
                obj("text", "جافا لغة برمجة", "type", "tf", "correct", 0, "difficulty", "easy")).json, "question", "id");
        String q2 = (String) get(trainer.post("/api/t/banks/" + bank + "/questions",
                obj("text", "أي كلمة تعرّف صنفًا؟", "type", "mcq", "options", Arrays.asList("int", "class", "void"), "correct", 1, "difficulty", "medium")).json,
                "question", "id");
        check(q1 != null && q2 != null, "trainer adds true/false and multiple-choice questions");
        check(trainer.post("/api/t/banks/" + bank + "/questions", obj("text", "بدون إجابة", "type", "mcq",
                "options", Arrays.asList("أ", "ب"), "correct", 5, "difficulty", "easy")).status == 400, "a question needs a valid correct answer");

        String draft = (String) get(trainer.post("/api/t/exams", obj("title", "مسودة", "courseId", course, "durationMinutes", 10,
                "status", "draft", "questionIds", Arrays.asList(q1))).json, "exam", "id");
        String exam = (String) get(trainer.post("/api/t/exams", obj("title", "اختبار ١", "courseId", course, "durationMinutes", 10,
                "status", "published", "questionIds", Arrays.asList(q1, q2))).json, "exam", "id");
        check(exam != null, "trainer publishes an exam");
        check(trainer.post("/api/t/exams", obj("title", "فارغ", "courseId", course, "durationMinutes", 10, "status", "published",
                "questionIds", new ArrayList<>())).status == 400, "an exam cannot be published without questions");

        Client s = trainee("flow-trainee");
        check(s.post("/api/s/exams/" + exam + "/start", obj()).status == 403, "a trainee must enroll before taking an exam");
        check(s.post("/api/s/courses/" + course + "/enroll", obj()).status == 200, "trainee enrolls in the course");
        check(s.post("/api/s/exams/" + draft + "/start", obj()).status == 404, "draft exams are hidden from trainees");

        String attempt = (String) s.post("/api/s/exams/" + exam + "/start", obj()).json.get("attemptId");
        check(attempt != null, "trainee starts the exam");
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put(q1, 0);   // right
        answers.put(q2, 2);   // wrong
        Res result = s.post("/api/s/attempts/" + attempt + "/submit", obj("answers", answers));
        check(num(result.json.get("score")) == 1 && num(result.json.get("total")) == 2 && num(result.json.get("percent")) == 50,
                "grading counts 1 of 2 correct as 50%");
        check(attempt.equals(s.post("/api/s/exams/" + exam + "/start", obj()).json.get("attemptId")), "an exam cannot be retaken for a new score");

        Client stranger = trainee("flow-stranger");
        check(stranger.get("/api/s/attempts/" + attempt).status == 404, "a trainee cannot see someone else's attempt");

        Client admin = admin();
        boolean courseListed = false;
        for (Object o : list(admin.get("/api/a/courses").json.get("courses"))) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (course.equals(m.get("id"))) courseListed = num(m.get("exams")) == 2 && num(m.get("trainees")) == 1 && "مدرب flow-trainer".equals(m.get("trainerName"));
        }
        check(courseListed, "the admin's courses page lists the course with its trainer, exams and trainees");
        boolean examListed = false;
        for (Object o : list(admin.get("/api/a/exams").json.get("exams"))) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (exam.equals(m.get("id"))) examListed = num(m.get("submitted")) == 1 && "published".equals(m.get("status"));
        }
        check(examListed, "the admin's exams page lists the exam and how many submitted it");
        check(trainer.get("/api/a/courses").status == 403 && s.get("/api/a/exams").status == 403, "only the admin can open the all-courses and all-exams lists");
    }

    private static void media() throws Exception {
        Client trainer = trainer("media-trainer");
        String course = (String) get(trainer.post("/api/t/courses", obj("title", "دورة فيديو")).json, "course", "id");
        byte[] video = new byte[5000];
        for (int i = 0; i < video.length; i++) video[i] = (byte) i;

        Res bad = trainer.upload("/api/t/courses/" + course + "/videos?name=notes.txt&title=x", "text/plain", "abc".getBytes(StandardCharsets.UTF_8));
        check(bad.status == 400, "non-video files are refused");
        Res up = trainer.upload("/api/t/courses/" + course + "/videos?name=lesson.mp4&title=" + enc("الدرس الأول"), "video/mp4", video);
        check(up.status == 200, "trainer uploads a video");
        String file = String.valueOf(get(up.json, "video", "url")).replace("/media/", "");
        String videoId = (String) get(up.json, "video", "id");

        check(new Client().getRaw("/media/" + file, null).status == 404, "visitors cannot watch videos");
        Client s = trainee("media-trainee");
        check(s.getRaw("/media/" + file, null).status == 404, "trainees outside the course cannot watch");
        s.post("/api/s/courses/" + course + "/enroll", obj());
        Res full = s.getRaw("/media/" + file, null);
        check(full.status == 200 && full.bytes.length == video.length, "enrolled trainees can watch");
        Res part = s.getRaw("/media/" + file, "bytes=100-199");
        check(part.status == 206 && part.bytes.length == 100 && part.bytes[0] == video[100], "seeking inside a video works (range requests)");
        check(trainer.getRaw("/media/" + file, null).status == 200, "the course's trainer can watch");
        check(trainer("media-other").getRaw("/media/" + file, null).status == 404, "other trainers cannot watch");

        check(trainer.delete("/api/t/videos/" + videoId).status == 200, "trainer deletes the video");
        check(s.getRaw("/media/" + file, null).status == 404, "a deleted video is gone");
    }

    private static void cascadeDeletes() throws Exception {
        Client admin = admin();
        Client trainer = trainer("cascade-trainer");
        String course = (String) get(trainer.post("/api/t/courses", obj("title", "دورة للحذف")).json, "course", "id");
        String bank = (String) get(trainer.post("/api/t/banks", obj("name", "بنك", "courseId", course, "audience", "all")).json, "bank", "id");
        String q = (String) get(trainer.post("/api/t/banks/" + bank + "/questions",
                obj("text", "س", "type", "tf", "correct", 0, "difficulty", "easy")).json, "question", "id");
        String exam = (String) get(trainer.post("/api/t/exams", obj("title", "اختبار", "courseId", course, "durationMinutes", 5,
                "status", "published", "questionIds", Arrays.asList(q))).json, "exam", "id");
        trainer.post("/api/t/lives", obj("title", "بث", "courseId", course, "durationMinutes", 60, "url", "https://zoom.us/j/1",
                "startsAt", System.currentTimeMillis() + 86_400_000L));
        Client s = trainee("cascade-trainee");
        s.post("/api/s/courses/" + course + "/enroll", obj());
        String attempt = (String) s.post("/api/s/exams/" + exam + "/start", obj()).json.get("attemptId");
        boolean wasEnrolled = list(s.get("/api/s/home").json.get("enrolled")).size() == 1;

        check(trainer.delete("/api/t/courses/" + course).status == 200, "trainer deletes the course");
        check(s.get("/api/s/attempts/" + attempt).status == 404, "its exam attempts are removed");
        check(wasEnrolled && list(s.get("/api/s/home").json.get("enrolled")).isEmpty(), "trainees are no longer enrolled in it");
        check(list(trainer.get("/api/t/lives").json.get("lives")).isEmpty(), "its live sessions are removed");
        check(list(trainer.get("/api/t/exams").json.get("exams")).isEmpty(), "its exams are removed");

        String sid = null;
        for (Object o : list(admin.get("/api/a/trainees").json.get("trainees"))) {
            Map<?, ?> m = (Map<?, ?>) o;
            if ("cascade-trainee@itqan.test".equals(m.get("email"))) sid = (String) m.get("id");
        }
        check(admin.delete("/api/a/trainees/" + sid).status == 200, "admin deletes a trainee");
        check(get(s.get("/api/me").json, "user") == null, "the deleted trainee is signed out");
    }

    private static void security() throws Exception {
        Res page = new Client().getRaw("/", null);
        check(String.valueOf(page.headers.get("Strict-Transport-Security")).contains("max-age"), "pages tell browsers to use https only (HSTS)");
        check("DENY".equals(page.headers.get("X-Frame-Options")), "other sites cannot embed the platform (clickjacking)");
        String csp = String.valueOf(page.headers.get("Content-Security-Policy"));
        check(csp.contains("script-src 'self'") && csp.contains("frame-ancestors 'none'"), "a content security policy limits where scripts come from");

        Client s = new Client();
        Res reg = s.post("/api/auth/register", obj("name", "متدرب أمان", "email", "sec-user@itqan.test", "password", "secret123"));
        String cookie = String.valueOf(reg.setCookie);
        check(cookie.contains("HttpOnly") && cookie.contains("Secure") && cookie.contains("SameSite=Lax"),
                "the session cookie is HttpOnly, Secure and SameSite");
        check(new Client().post("/api/auth/register", obj("name", "قصيرة", "email", "short7@itqan.test", "password", "1234567")).status == 400,
                "passwords shorter than 8 characters are refused");

        for (int i = 0; i < Api.MAX_LOGIN_FAILURES; i++) {
            new Client().post("/api/auth/login", obj("email", "sec-user@itqan.test", "password", "wrong-" + i));
        }
        check(new Client().post("/api/auth/login", obj("email", "sec-user@itqan.test", "password", "secret123")).status == 429,
                "after " + Api.MAX_LOGIN_FAILURES + " wrong passwords sign-in pauses, even with the right one");
        check(new Client().post("/api/auth/login", obj("email", "student1@itqan.test", "password", "secret123")).status == 200,
                "the pause affects only that account");

        String salt = "c2FsdHNhbHRzYWx0c2FsdA==";
        synchronized (store) {
            Map<String, Object> old = new LinkedHashMap<>();
            old.put("name", "حساب قديم");
            old.put("email", "legacy@itqan.test");
            old.put("role", "trainee");
            old.put("salt", salt);
            old.put("passwordHash", Api.hash("oldpass1", salt, 60_000));
            store.insert("users", old);
            store.flush();
        }
        check(new Client().post("/api/auth/login", obj("email", "legacy@itqan.test", "password", "oldpass1")).status == 200,
                "accounts with the older password hashing still sign in");
        Object rounds;
        synchronized (store) { rounds = store.first("users", u -> "legacy@itqan.test".equals(u.get("email"))).get("rounds"); }
        check(rounds instanceof Number && ((Number) rounds).longValue() >= 210_000, "their password hashing is upgraded on sign-in");

        Client aged = trainee("sec-aged");
        synchronized (store) {
            for (Map<String, Object> se : store.table("sessions")) {
                Map<String, Object> u = store.find("users", String.valueOf(se.get("userId")));
                if (u != null && "sec-aged@itqan.test".equals(u.get("email"))) se.put("createdAt", System.currentTimeMillis() - 31L * 86_400_000L);
            }
        }
        check(get(aged.get("/api/me").json, "user") == null, "sessions older than 30 days are refused by the server");

        Client trainer = trainer("sec-trainer");
        String course = (String) get(trainer.post("/api/t/courses", obj("title", "دورة تقدم")).json, "course", "id");
        String video = (String) get(trainer.upload("/api/t/courses/" + course + "/videos?name=v.mp4&title=v", "video/mp4", new byte[3000]).json, "video", "id");
        Client watcher = trainee("sec-watcher");
        watcher.post("/api/s/courses/" + course + "/enroll", obj());
        Res last = null;
        for (int i = 0; i < 10; i++) last = watcher.post("/api/s/videos/" + video + "/progress", obj("position", 50, "duration", 60, "played", 30));
        check(last != null && Boolean.FALSE.equals(last.json.get("watched")), "rapid fake progress reports do not mark a video as watched");
    }

    private static void persistence(Path dir) throws Exception {
        Store reloaded = new Store(new Backend.FileBackend(dir.resolve("itqan.json")));
        check(reloaded.first("users", u -> "student1@itqan.test".equals(u.get("email"))) != null,
                "accounts are read back from the saved file");
        check(reloaded.first("courses", c -> "أساسيات جافا".equals(c.get("title"))) != null,
                "courses (with Arabic text) are read back intact");
    }

    private static void r2Signing() throws Exception {
        String ak = "AKIAIOSFODNN7EXAMPLE";
        String sk = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date when = f.parse("20130524T000000Z");
        R2 r = new R2("https://examplebucket.s3.amazonaws.com", "examplebucket", ak, sk);

        String url = r.presign("GET", "examplebucket.s3.amazonaws.com", "/test.txt", 86400, when, "us-east-1");
        check(url.endsWith("X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"),
                "presigned links match AWS's published example");

        TreeMap<String, String> h = new TreeMap<>();
        String empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        h.put("host", "examplebucket.s3.amazonaws.com");
        h.put("range", "bytes=0-9");
        h.put("x-amz-content-sha256", empty);
        h.put("x-amz-date", "20130524T000000Z");
        String auth = r.authorization("GET", "/test.txt", "", h, empty, "20130524T000000Z", "us-east-1");
        check(auth.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"),
                "signed requests match AWS's published example");
    }

    // ================================================================ helpers

    private static void startServer(Path dir) throws Exception {
        Path uploads = dir.resolve("uploads");
        Files.createDirectories(uploads);
        store = new Store(new Backend.FileBackend(dir.resolve("itqan.json")));
        Api api = new Api(store, uploads, null);
        synchronized (store) {
            api.seedAdmin(ADMIN_EMAIL, ADMIN_PASSWORD);
            store.flush();
        }
        Path web = locateWeb();
        Server server = new Server(store, web, uploads, null);
        api.register(server);
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        server.start("127.0.0.1", port);
        base = "http://127.0.0.1:" + port;
        System.out.println("Test server on " + base + " (data in " + dir + ")");
    }

    private static Path locateWeb() {
        for (Path p = java.nio.file.Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("web").resolve("index.html"))) return p.resolve("web");
        }
        return java.nio.file.Paths.get("web");
    }

    private interface Scenario { void run() throws Exception; }

    private static void run(String name, Scenario scenario) {
        System.out.println();
        System.out.println("== " + name);
        try {
            scenario.run();
        } catch (Throwable t) {
            failures.add(name + ": crashed with " + t);
            System.out.println("  CRASH " + t);
        }
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            passed++;
            System.out.println("  ok    " + what);
        } else {
            failures.add(what);
            System.out.println("  FAIL  " + what);
        }
    }

    private static Client admin() throws IOException {
        Client c = new Client();
        c.post("/api/auth/login", obj("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD, "role", "trainer"));
        return c;
    }

    private static Client trainer(String name) throws IOException {
        admin().post("/api/a/trainers", obj("name", "مدرب " + name, "email", name + "@itqan.test", "password", "secret123"));
        Client c = new Client();
        c.post("/api/auth/login", obj("email", name + "@itqan.test", "password", "secret123", "role", "trainer"));
        return c;
    }

    private static Client trainee(String name) throws IOException {
        Client c = new Client();
        c.post("/api/auth/register", obj("name", "متدرب " + name, "email", name + "@itqan.test", "password", "secret123"));
        return c;
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Object get(Map<String, Object> json, String... path) {
        Object cur = json;
        for (String p : path) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(p);
        }
        return cur;
    }

    private static List<?> list(Object o) { return o instanceof List ? (List<?>) o : new ArrayList<>(); }

    private static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : -1; }

    private static String enc(String s) throws IOException { return URLEncoder.encode(s, "UTF-8"); }

    private static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp folder; the OS cleans it eventually
        }
    }

    private static final class Res {
        int status;
        String setCookie;
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        byte[] bytes = new byte[0];
        Map<String, Object> json = new LinkedHashMap<>();
    }

    /** A browser-like client: keeps its own session cookie and sends the app's anti-forgery header. */
    private static final class Client {
        private String cookie;

        Res get(String path) throws IOException { return send("GET", path, null, true); }

        Res post(String path, Map<String, Object> body) throws IOException { return send("POST", path, Json.stringify(body), true); }

        Res put(String path, Map<String, Object> body) throws IOException { return send("PUT", path, Json.stringify(body), true); }

        Res delete(String path) throws IOException { return send("DELETE", path, null, true); }

        Res send(String method, String path, String body, boolean header) throws IOException {
            HttpURLConnection c = open(method, path);
            if (header) c.setRequestProperty("X-Requested-With", "itqan");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            return finish(c, true);
        }

        Res upload(String path, String mime, byte[] data) throws IOException {
            HttpURLConnection c = open("POST", path);
            c.setRequestProperty("X-Requested-With", "itqan");
            c.setRequestProperty("Content-Type", mime);
            c.setDoOutput(true);
            try (OutputStream out = c.getOutputStream()) { out.write(data); }
            return finish(c, true);
        }

        Res getRaw(String path, String range) throws IOException {
            HttpURLConnection c = open("GET", path);
            if (range != null) c.setRequestProperty("Range", range);
            return finish(c, false);
        }

        private HttpURLConnection open(String method, String path) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
            c.setRequestMethod(method);
            c.setInstanceFollowRedirects(false);
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
            return c;
        }

        @SuppressWarnings("unchecked")
        private Res finish(HttpURLConnection c, boolean parseJson) throws IOException {
            Res r = new Res();
            r.status = c.getResponseCode();
            for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                if (h.getKey() != null && !h.getValue().isEmpty()) r.headers.put(h.getKey(), h.getValue().get(0));
            }
            String setCookie = c.getHeaderField("Set-Cookie");
            r.setCookie = setCookie;
            if (setCookie != null) {
                String pair = setCookie.split(";", 2)[0];
                cookie = pair.endsWith("=") ? null : pair;
            }
            InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (InputStream i = in) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = i.read(b)) > 0) buf.write(b, 0, n);
                }
                r.bytes = buf.toByteArray();
            }
            if (parseJson && r.bytes.length > 0) {
                Object parsed = Json.parse(new String(r.bytes, StandardCharsets.UTF_8));
                if (parsed instanceof Map) r.json = (Map<String, Object>) parsed;
            }
            return r;
        }
    }
}
