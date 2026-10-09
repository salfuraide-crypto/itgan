package itqan;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Every JSON endpoint of the platform. Handlers run while holding the store lock. */
final class Api {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Set<String> VIDEO_EXT = new HashSet<>(Arrays.asList("mp4", "m4v", "webm", "ogg", "ogv", "mov", "mkv"));
    private static final Set<String> DIFFICULTIES = new HashSet<>(Arrays.asList("easy", "medium", "hard"));
    private static final long MAX_COVER_BYTES = 8L * 1024 * 1024;
    /** Submissions arriving this long after the deadline are still accepted (network delay). */
    private static final long ATTEMPT_GRACE_MS = 20_000;
    /** A video counts as watched once roughly this share of it has been played. */
    private static final double WATCHED_RATIO = 0.8;
    /** Passwords hashed before this setting existed used 60,000 PBKDF2 rounds; they are upgraded on sign-in. */
    private static final int LEGACY_ROUNDS = 60_000;
    private static final int HASH_ROUNDS = 210_000;
    static final int MIN_PASSWORD = 8;
    /** Wrong passwords allowed for one email within LOGIN_WINDOW_MS before sign-in is paused for that email. */
    static final int MAX_LOGIN_FAILURES = 5;
    static final long LOGIN_WINDOW_MS = 15 * 60_000L;
    /** Hashed when the email is unknown, so a wrong email takes as long as a wrong password. */
    private static final String DUMMY_SALT = "AAAAAAAAAAAAAAAAAAAAAA==";

    private final Store db;
    private final Path uploadDir;
    private final R2 r2;
    /** Recent wrong passwords per email: {failures, window start, paused until}. Kept in memory only. */
    private final Map<String, long[]> loginFailures = new java.util.HashMap<>();

    /** r2 is null when uploads stay on the local disk. */
    Api(Store db, Path uploadDir, R2 r2) {
        this.db = db;
        this.uploadDir = uploadDir;
        this.r2 = r2;
    }

    void register(Server s) {
        s.route("POST", "/api/auth/register", null, this::register);
        s.route("POST", "/api/auth/login", null, this::login);
        s.route("POST", "/api/auth/logout", null, this::logout);
        s.route("GET", "/api/me", null, this::me);

        s.route("GET", "/api/a/overview", "admin", this::adminOverview);
        s.route("POST", "/api/a/trainers", "admin", this::createTrainer);
        s.route("PUT", "/api/a/trainers/{id}/password", "admin", this::resetTrainerPassword);
        s.route("DELETE", "/api/a/trainers/{id}", "admin", this::deleteTrainer);
        s.route("GET", "/api/a/trainees", "admin", this::adminTrainees);
        s.route("GET", "/api/a/courses", "admin", this::adminCourses);
        s.route("GET", "/api/a/exams", "admin", this::adminExams);
        s.route("DELETE", "/api/a/trainees/{id}", "admin", this::deleteTrainee);

        s.route("GET", "/api/t/dashboard", "trainer", this::trainerDashboard);
        s.route("GET", "/api/t/courses", "trainer", this::trainerCourses);
        s.route("POST", "/api/t/courses", "trainer", this::createCourse);
        s.route("GET", "/api/t/courses/{id}", "trainer", this::trainerCourse);
        s.route("PUT", "/api/t/courses/{id}", "trainer", this::updateCourse);
        s.route("DELETE", "/api/t/courses/{id}", "trainer", this::deleteCourse);
        s.upload("/api/t/courses/{id}/cover", "trainer", this::uploadCover);
        s.upload("/api/t/courses/{id}/videos", "trainer", this::uploadVideo);
        s.route("PUT", "/api/t/videos/{id}", "trainer", this::updateVideo);
        s.route("DELETE", "/api/t/videos/{id}", "trainer", this::deleteVideo);

        s.route("GET", "/api/t/lives", "trainer", this::trainerLives);
        s.route("POST", "/api/t/lives", "trainer", this::createLive);
        s.route("PUT", "/api/t/lives/{id}", "trainer", this::updateLive);
        s.route("POST", "/api/t/lives/{id}/end", "trainer", this::endLive);
        s.route("DELETE", "/api/t/lives/{id}", "trainer", this::deleteLive);
        s.upload("/api/t/lives/{id}/recording", "trainer", this::uploadRecording);

        s.route("GET", "/api/t/courses/{id}/trainees", "trainer", this::courseTraineeOptions);
        s.route("GET", "/api/t/banks", "trainer", this::trainerBanks);
        s.route("POST", "/api/t/banks", "trainer", this::createBank);
        s.route("GET", "/api/t/banks/{id}", "trainer", this::trainerBank);
        s.route("PUT", "/api/t/banks/{id}", "trainer", this::updateBank);
        s.route("DELETE", "/api/t/banks/{id}", "trainer", this::deleteBank);
        s.route("POST", "/api/t/banks/{id}/questions", "trainer", this::createQuestion);
        s.route("PUT", "/api/t/questions/{id}", "trainer", this::updateQuestion);
        s.route("DELETE", "/api/t/questions/{id}", "trainer", this::deleteQuestion);

        s.route("GET", "/api/t/question-pool", "trainer", this::questionPool);
        s.route("GET", "/api/t/exams", "trainer", this::trainerExams);
        s.route("POST", "/api/t/exams", "trainer", this::createExam);
        s.route("GET", "/api/t/exams/{id}", "trainer", this::trainerExam);
        s.route("PUT", "/api/t/exams/{id}", "trainer", this::updateExam);
        s.route("DELETE", "/api/t/exams/{id}", "trainer", this::deleteExam);
        s.route("GET", "/api/t/trainees", "trainer", this::trainerTrainees);

        s.route("GET", "/api/s/home", "trainee", this::traineeHome);
        s.route("POST", "/api/s/courses/{id}/enroll", "trainee", this::enroll);
        s.route("GET", "/api/s/courses/{id}", "trainee", this::traineeCourse);
        s.route("GET", "/api/s/videos", "trainee", this::traineeVideos);
        s.route("GET", "/api/s/videos/{id}", "trainee", this::traineeVideo);
        s.route("POST", "/api/s/videos/{id}/progress", "trainee", this::videoProgress);
        s.route("GET", "/api/s/lives", "trainee", this::traineeLives);
        s.route("GET", "/api/s/exams", "trainee", this::traineeExams);
        s.route("POST", "/api/s/exams/{id}/start", "trainee", this::startExam);
        s.route("GET", "/api/s/attempts/{id}", "trainee", this::getAttempt);
        s.route("POST", "/api/s/attempts/{id}/answers", "trainee", this::saveAnswers);
        s.route("POST", "/api/s/attempts/{id}/submit", "trainee", this::submitAttempt);
        s.route("GET", "/api/s/progress", "trainee", this::traineeProgress);
        s.route("GET", "/api/s/banks", "trainee", this::traineeBanks);
        s.route("GET", "/api/s/banks/{id}", "trainee", this::traineeBank);
        s.route("POST", "/api/s/questions/{id}/check", "trainee", this::checkPracticeAnswer);

        s.mediaGuard(this::canAccessMedia);
    }

    // ================================================================ helpers

    static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static String s(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? "" : v.toString();
    }

    static long n(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    static double d(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
    }

    static boolean b(Map<String, Object> m, String k) { return Boolean.TRUE.equals(m.get(k)); }

    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof List) return (List<Object>) v;
        List<Object> l = new ArrayList<>();
        m.put(k, l);
        return l;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Map) return (Map<String, Object>) v;
        Map<String, Object> created = new LinkedHashMap<>();
        m.put(k, created);
        return created;
    }

    static ApiError bad(String message) { return new ApiError(400, message); }

    static ApiError notFound(String message) { return new ApiError(404, message); }

    static String text(String value, int max, String emptyMessage) {
        if (emptyMessage != null && value.isEmpty()) throw bad(emptyMessage);
        if (value.length() > max) throw bad("النص أطول من المسموح (" + max + " حرفًا)");
        return value;
    }

    static long now() { return System.currentTimeMillis(); }

    static Comparator<Map<String, Object>> byNum(String key) {
        return Comparator.comparingLong(m -> n(m, key));
    }

    static String mediaUrl(String file) { return file.isEmpty() ? null : "/media/" + file; }

    private void deleteFile(String name) {
        if (name == null || name.isEmpty()) return;
        try {
            Files.deleteIfExists(uploadDir.resolve(name));
            if (r2 != null) r2.delete(name);
        } catch (IOException e) {
            System.err.println("Could not delete " + name + ": " + e.getMessage());
        }
    }

    private String courseTitle(String id) {
        Map<String, Object> c = db.find("courses", id);
        return c == null ? "" : s(c, "title");
    }

    private String userName(String id) {
        Map<String, Object> u = db.find("users", id);
        return u == null ? "" : s(u, "name");
    }

    // ================================================================ accounts

    /**
     * Makes sure there is an admin. The account named by the env email is promoted if it exists
     * (older installs created it as a trainer), otherwise it is created.
     */
    void seedAdmin(String email, String password) {
        for (Map<String, Object> u : db.table("users")) if ("admin".equals(u.get("role"))) return;
        Map<String, Object> existing = userByEmail(email);
        if (existing != null) {
            existing.put("role", "admin");
            if ("المدرب".equals(existing.get("name"))) existing.put("name", "المدير");
            db.touch();
            System.out.println("Promoted to admin: " + email);
            return;
        }
        createUser("المدير", email.toLowerCase(Locale.ROOT), password, "admin");
        System.out.println("Created the admin account: " + email);
    }

    private Map<String, Object> createUser(String name, String email, String password, String role) {
        Map<String, Object> u = obj("name", name, "email", email, "role", role);
        setPassword(u, password);
        return db.insert("users", u);
    }

    private static void setPassword(Map<String, Object> user, String password) {
        String salt = newSalt();
        user.put("salt", salt);
        user.put("passwordHash", hash(password, salt, HASH_ROUNDS));
        user.put("rounds", (long) HASH_ROUNDS);
    }

    private static void checkNewPassword(String password) {
        if (password.length() < MIN_PASSWORD) throw bad("كلمة المرور يجب ألا تقل عن " + MIN_PASSWORD + " أحرف");
        if (password.length() > 200) throw bad("كلمة المرور طويلة جدًا");
    }

    private Map<String, Object> userByEmail(String email) {
        for (Map<String, Object> u : db.table("users")) if (email.equalsIgnoreCase(s(u, "email"))) return u;
        return null;
    }

    static Map<String, Object> publicUser(Map<String, Object> u) {
        return obj("id", u.get("id"), "name", u.get("name"), "email", u.get("email"), "role", u.get("role"));
    }

    private Object register(Req r) {
        String name = text(r.str("name"), 80, "أدخل اسمك");
        if (name.length() < 2) throw bad("الاسم قصير جدًا");
        String email = r.str("email").toLowerCase(Locale.ROOT);
        if (email.length() > 120 || !EMAIL.matcher(email).matches()) throw bad("البريد الإلكتروني غير صالح");
        String password = r.raw("password");
        checkNewPassword(password);
        if (userByEmail(email) != null) throw new ApiError(409, "هذا البريد مسجّل مسبقًا، جرّب تسجيل الدخول");
        Map<String, Object> user = createUser(name, email, password, "trainee");
        startSession(r, user);
        return obj("user", publicUser(user));
    }

    private Object login(Req r) {
        String key = r.str("email").toLowerCase(Locale.ROOT);
        long now = now();
        long[] f = loginFailures.get(key);
        if (f != null && f[2] > now) {
            long minutes = Math.max(1, (f[2] - now + 59_999) / 60_000);
            throw new ApiError(429, "محاولات دخول خاطئة كثيرة لهذا الحساب. حاول مرة أخرى بعد " + minutes + " دقيقة.");
        }
        Map<String, Object> user = userByEmail(key);
        if (user == null) hash(r.raw("password"), DUMMY_SALT, HASH_ROUNDS);
        if (user == null || !checkPassword(user, r.raw("password"))) {
            recordLoginFailure(key, now);
            throw new ApiError(401, "البريد الإلكتروني أو كلمة المرور غير صحيحة");
        }
        loginFailures.remove(key);
        if (n(user, "rounds") < HASH_ROUNDS) {
            setPassword(user, r.raw("password"));   // upgrade an older, weaker hash now that we know the password
            db.touch();
        }
        // The staff portal serves trainers and the admin; the trainee page serves trainees only.
        boolean staffPortal = "trainer".equals(r.str("role"));
        boolean isStaff = "trainer".equals(user.get("role")) || "admin".equals(user.get("role"));
        if (staffPortal && !isStaff) throw new ApiError(403, "هذا الحساب ليس حساب مدرب، ادخل من صفحة دخول المتدربين");
        if (!staffPortal && isStaff) throw new ApiError(403, "هذا حساب مدرب أو إدارة، ادخل من بوابة المدربين");
        startSession(r, user);
        return obj("user", publicUser(user));
    }

    private void recordLoginFailure(String key, long now) {
        if (loginFailures.size() > 10_000) loginFailures.values().removeIf(x -> x[2] < now && now - x[1] > LOGIN_WINDOW_MS);
        long[] f = loginFailures.get(key);
        if (f == null || now - f[1] > LOGIN_WINDOW_MS) {
            f = new long[] {0, now, 0};
            loginFailures.put(key, f);
        }
        if (++f[0] >= MAX_LOGIN_FAILURES) {
            f[0] = 0;
            f[1] = now;
            f[2] = now + LOGIN_WINDOW_MS;
        }
    }

    private void startSession(Req r, Map<String, Object> user) {
        String token = Store.newId(40);
        db.insert("sessions", obj("id", token, "userId", user.get("id")));
        r.setCookie = Server.COOKIE + "=" + token + "; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=" + Server.SESSION_DAYS * 24 * 3600;
    }

    private Object logout(Req r) {
        final String token = r.token;
        if (token != null) db.removeIf("sessions", x -> token.equals(x.get("id")));
        r.setCookie = Server.COOKIE + "=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0";
        return obj("ok", true);
    }

    private Object me(Req r) {
        return obj("user", r.user == null ? null : publicUser(r.user));
    }

    private static String newSalt() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    static String hash(String password, String salt, int rounds) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), rounds, 256);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(key);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean checkPassword(Map<String, Object> user, String password) {
        byte[] expected = s(user, "passwordHash").getBytes(StandardCharsets.UTF_8);
        long rounds = n(user, "rounds");
        byte[] actual = hash(password, s(user, "salt"), rounds > 0 ? (int) rounds : LEGACY_ROUNDS).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    // ============================================================ shared model

    private List<Map<String, Object>> videosOf(String courseId) {
        List<Map<String, Object>> v = db.where("videos", x -> courseId.equals(x.get("courseId")));
        v.sort(byNum("order").thenComparing(byNum("createdAt")));
        return v;
    }

    private List<Map<String, Object>> examsOf(String courseId, boolean publishedOnly) {
        List<Map<String, Object>> e = db.where("exams", x -> courseId.equals(x.get("courseId"))
                && (!publishedOnly || "published".equals(x.get("status"))));
        e.sort(byNum("createdAt"));
        return e;
    }

    private Map<String, Object> enrollment(String userId, String courseId) {
        return db.first("enrollments", e -> userId.equals(e.get("userId")) && courseId.equals(e.get("courseId")));
    }

    private Map<String, Object> watchOf(String userId, String videoId) {
        return db.first("watches", w -> userId.equals(w.get("userId")) && videoId.equals(w.get("videoId")));
    }

    private boolean watched(String userId, String videoId) {
        Map<String, Object> w = watchOf(userId, videoId);
        return w != null && b(w, "watched");
    }

    /** The user's attempt at an exam, finalized first if its time ran out. */
    private Map<String, Object> attemptOf(String userId, String examId) {
        Map<String, Object> a = db.first("attempts", x -> userId.equals(x.get("userId")) && examId.equals(x.get("examId")));
        if (a != null) finalizeIfExpired(a);
        return a;
    }

    static boolean submitted(Map<String, Object> attempt) { return attempt != null && n(attempt, "submittedAt") > 0; }

    static String attemptStatus(Map<String, Object> a) {
        return a == null ? "none" : submitted(a) ? "submitted" : "in_progress";
    }

    private Map<String, Object> progress(String userId, String courseId) {
        List<Map<String, Object>> videos = videosOf(courseId);
        List<Map<String, Object>> exams = examsOf(courseId, true);
        int videosWatched = 0;
        int examsDone = 0;
        for (Map<String, Object> v : videos) if (watched(userId, s(v, "id"))) videosWatched++;
        for (Map<String, Object> e : exams) if (submitted(attemptOf(userId, s(e, "id")))) examsDone++;
        int total = videos.size() + exams.size();
        long percent = total == 0 ? 0 : Math.round(100.0 * (videosWatched + examsDone) / total);
        return obj("percent", percent, "videosWatched", videosWatched, "videosTotal", videos.size(),
                "examsDone", examsDone, "examsTotal", exams.size());
    }

    private static String liveStatus(Map<String, Object> live, long now) {
        if (n(live, "endedAt") > 0) return "ended";
        long start = n(live, "startsAt");
        if (now < start) return "upcoming";
        if (now < start + n(live, "durationMinutes") * 60_000L) return "live";
        return "ended";
    }

    private Map<String, Object> liveView(Map<String, Object> live, long now, boolean forTrainer) {
        String status = liveStatus(live, now);
        String recording = s(live, "recordingVideoId");
        if (!recording.isEmpty() && db.find("videos", recording) == null) recording = "";
        return obj("id", live.get("id"), "title", live.get("title"),
                "courseId", live.get("courseId"), "courseTitle", courseTitle(s(live, "courseId")),
                "startsAt", n(live, "startsAt"), "durationMinutes", n(live, "durationMinutes"),
                "url", forTrainer || "live".equals(status) ? live.get("url") : null,
                "status", status, "recordingVideoId", recording.isEmpty() ? null : recording);
    }

    private List<Map<String, Object>> liveViews(List<Map<String, Object>> lives, boolean forTrainer) {
        long now = now();
        lives.sort(byNum("startsAt"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : lives) out.add(liveView(l, now, forTrainer));
        return out;
    }

    private Map<String, Object> videoView(Map<String, Object> v) {
        return obj("id", v.get("id"), "courseId", v.get("courseId"), "title", v.get("title"),
                "description", s(v, "description"), "order", n(v, "order"), "source", v.get("source"),
                "liveId", s(v, "liveId"), "duration", n(v, "duration"), "size", n(v, "size"),
                "url", mediaUrl(s(v, "file")), "createdAt", v.get("createdAt"));
    }

    private Map<String, Object> questionView(Map<String, Object> q) {
        return obj("id", q.get("id"), "bankId", q.get("bankId"), "text", q.get("text"), "type", q.get("type"),
                "options", list(q, "options"), "correct", n(q, "correct"), "difficulty", q.get("difficulty"),
                "explanation", s(q, "explanation"), "createdAt", q.get("createdAt"));
    }

    private List<Object> existingQuestionIds(Map<String, Object> exam) {
        List<Object> ids = new ArrayList<>();
        for (Object id : list(exam, "questionIds")) if (db.find("questions", String.valueOf(id)) != null) ids.add(id);
        return ids;
    }

    private Map<String, Object> examView(Map<String, Object> e) {
        final String id = s(e, "id");
        List<Object> ids = existingQuestionIds(e);
        return obj("id", id, "title", e.get("title"), "courseId", e.get("courseId"),
                "courseTitle", courseTitle(s(e, "courseId")), "durationMinutes", n(e, "durationMinutes"),
                "status", e.get("status"), "questionIds", ids, "questionCount", ids.size(),
                "attempts", db.count("attempts", a -> id.equals(a.get("examId")) && submitted(a)),
                "createdAt", e.get("createdAt"));
    }

    private boolean isVideo(Req.Upload u) {
        return u.mime.startsWith("video/") || VIDEO_EXT.contains(Server.extension(u.name));
    }

    /** Inserts or moves a video to a 1-based position (0 or out of range = last) and renumbers the course. */
    private void placeVideo(String courseId, Map<String, Object> video, long position) {
        List<Map<String, Object>> ordered = videosOf(courseId);
        ordered.removeIf(v -> v.get("id").equals(video.get("id")));
        int index = position < 1 || position > ordered.size() ? ordered.size() : (int) position - 1;
        ordered.add(index, video);
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).put("order", (long) i + 1);
        db.touch();
    }

    private void renumberVideos(String courseId) {
        List<Map<String, Object>> ordered = videosOf(courseId);
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).put("order", (long) i + 1);
        db.touch();
    }

    boolean canAccessMedia(Map<String, Object> user, String fileName) {
        String uid = s(user, "id");
        for (Map<String, Object> c : db.table("courses")) if (fileName.equals(c.get("cover"))) return true;
        for (Map<String, Object> v : db.table("videos")) {
            if (!fileName.equals(v.get("file"))) continue;
            String courseId = s(v, "courseId");
            if ("trainer".equals(user.get("role"))) {
                Map<String, Object> c = db.find("courses", courseId);
                return c != null && uid.equals(c.get("trainerId"));
            }
            return enrollment(uid, courseId) != null;
        }
        return false;
    }

    // ============================================================ trainer side

    private Map<String, Object> ownCourse(Req r, String id) {
        if (id.isEmpty()) throw bad("اختر الدورة");
        Map<String, Object> c = db.find("courses", id);
        if (c == null || !r.userId().equals(c.get("trainerId"))) throw notFound("الدورة غير موجودة");
        return c;
    }

    private Map<String, Object> ownVideo(Req r, String id) {
        Map<String, Object> v = db.find("videos", id);
        if (v == null) throw notFound("المقطع غير موجود");
        ownCourse(r, s(v, "courseId"));
        return v;
    }

    private Map<String, Object> ownLive(Req r, String id) {
        Map<String, Object> l = db.find("lives", id);
        if (l == null || !r.userId().equals(l.get("trainerId"))) throw notFound("البث غير موجود");
        return l;
    }

    private Map<String, Object> ownBank(Req r, String id) {
        Map<String, Object> b = db.find("banks", id);
        if (b == null || !r.userId().equals(b.get("trainerId"))) throw notFound("بنك الأسئلة غير موجود");
        return b;
    }

    private Map<String, Object> ownQuestionOrNull(Req r, String id) {
        Map<String, Object> q = db.find("questions", id);
        if (q == null) return null;
        Map<String, Object> bank = db.find("banks", s(q, "bankId"));
        return bank != null && r.userId().equals(bank.get("trainerId")) ? q : null;
    }

    private Map<String, Object> ownExam(Req r, String id) {
        Map<String, Object> e = db.find("exams", id);
        if (e == null || !r.userId().equals(e.get("trainerId"))) throw notFound("الاختبار غير موجود");
        return e;
    }

    private List<Map<String, Object>> trainerCourseRows(Req r) {
        final String uid = r.userId();
        List<Map<String, Object>> courses = db.where("courses", c -> uid.equals(c.get("trainerId")));
        courses.sort(byNum("createdAt").reversed());
        return courses;
    }

    private List<Map<String, Object>> courseOptions(Req r) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : trainerCourseRows(r)) out.add(obj("id", c.get("id"), "title", c.get("title")));
        return out;
    }

    private Map<String, Object> trainerCourseCard(Map<String, Object> c) {
        final String id = s(c, "id");
        return obj("id", id, "title", c.get("title"), "description", s(c, "description"),
                "cover", mediaUrl(s(c, "cover")), "videos", videosOf(id).size(),
                "lives", db.count("lives", x -> id.equals(x.get("courseId"))),
                "exams", examsOf(id, false).size(),
                "trainees", db.count("enrollments", x -> id.equals(x.get("courseId"))),
                "createdAt", c.get("createdAt"));
    }

    private List<Map<String, Object>> traineeRows(final String courseId) {
        List<Map<String, Object>> exams = examsOf(courseId, true);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> en : db.where("enrollments", x -> courseId.equals(x.get("courseId")))) {
            Map<String, Object> u = db.find("users", s(en, "userId"));
            if (u == null) continue;
            String uid = s(u, "id");
            List<Map<String, Object>> scores = new ArrayList<>();
            for (Map<String, Object> e : exams) {
                Map<String, Object> a = attemptOf(uid, s(e, "id"));
                scores.add(obj("examId", e.get("id"), "title", e.get("title"), "status", attemptStatus(a),
                        "percent", submitted(a) ? n(a, "percent") : null));
            }
            rows.add(obj("id", uid, "name", u.get("name"), "email", u.get("email"), "enrolledAt", en.get("createdAt"),
                    "progress", progress(uid, courseId), "exams", scores));
        }
        rows.sort(Comparator.comparing(x -> s(x, "name")));
        return rows;
    }

    private Object trainerDashboard(Req r) {
        final String uid = r.userId();
        List<Map<String, Object>> courses = trainerCourseRows(r);
        Set<String> courseIds = new HashSet<>();
        for (Map<String, Object> c : courses) courseIds.add(s(c, "id"));
        Set<String> trainees = new HashSet<>();
        for (Map<String, Object> e : db.table("enrollments")) if (courseIds.contains(s(e, "courseId"))) trainees.add(s(e, "userId"));

        List<Map<String, Object>> upcoming = new ArrayList<>();
        for (Map<String, Object> l : liveViews(db.where("lives", x -> uid.equals(x.get("trainerId"))), true)) {
            if (!"ended".equals(l.get("status"))) upcoming.add(l);
        }
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Map<String, Object> c : courses) if (recent.size() < 4) recent.add(trainerCourseCard(c));

        return obj("counts", obj(
                        "courses", courses.size(),
                        "trainees", trainees.size(),
                        "videos", db.count("videos", v -> courseIds.contains(s(v, "courseId"))),
                        "banks", db.count("banks", b -> uid.equals(b.get("trainerId"))),
                        "exams", db.count("exams", e -> uid.equals(e.get("trainerId"))),
                        "upcomingLives", upcoming.size()),
                "upcomingLives", upcoming.size() > 5 ? upcoming.subList(0, 5) : upcoming,
                "courses", recent);
    }

    private Object trainerCourses(Req r) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : trainerCourseRows(r)) out.add(trainerCourseCard(c));
        return obj("courses", out);
    }

    private void readCourse(Req r, Map<String, Object> c) {
        c.put("title", text(r.str("title"), 120, "أدخل عنوان الدورة"));
        c.put("description", text(r.str("description"), 3000, null));
    }

    private Object createCourse(Req r) {
        Map<String, Object> c = obj("trainerId", r.userId(), "cover", "");
        readCourse(r, c);
        return obj("course", trainerCourseCard(db.insert("courses", c)));
    }

    private Object updateCourse(Req r) {
        Map<String, Object> c = ownCourse(r, r.param(0));
        readCourse(r, c);
        return obj("course", trainerCourseCard(c));
    }

    private Object trainerCourse(Req r) {
        Map<String, Object> c = ownCourse(r, r.param(0));
        final String id = s(c, "id");
        List<Map<String, Object>> videos = new ArrayList<>();
        for (Map<String, Object> v : videosOf(id)) videos.add(videoView(v));
        List<Map<String, Object>> exams = new ArrayList<>();
        for (Map<String, Object> e : examsOf(id, false)) exams.add(examView(e));
        return obj("course", trainerCourseCard(c), "videos", videos,
                "lives", liveViews(db.where("lives", x -> id.equals(x.get("courseId"))), true),
                "exams", exams, "trainees", traineeRows(id));
    }

    private Object deleteCourse(Req r) {
        removeCourse(ownCourse(r, r.param(0)));
        return null;
    }

    /** Deletes a course with its videos, live sessions, exams, enrollments and progress. */
    private void removeCourse(Map<String, Object> c) {
        final String id = s(c, "id");
        for (Map<String, Object> v : videosOf(id)) deleteFile(s(v, "file"));
        final Set<String> examIds = new HashSet<>();
        for (Map<String, Object> e : examsOf(id, false)) examIds.add(s(e, "id"));
        db.removeIf("videos", x -> id.equals(x.get("courseId")));
        db.removeIf("lives", x -> id.equals(x.get("courseId")));
        db.removeIf("exams", x -> id.equals(x.get("courseId")));
        db.removeIf("attempts", x -> examIds.contains(s(x, "examId")));
        db.removeIf("enrollments", x -> id.equals(x.get("courseId")));
        db.removeIf("watches", x -> id.equals(x.get("courseId")));
        for (Map<String, Object> bank : db.table("banks")) if (id.equals(bank.get("courseId"))) bank.put("courseId", "");
        deleteFile(s(c, "cover"));
        db.removeIf("courses", x -> id.equals(x.get("id")));
    }

    private Object uploadCover(Req r) {
        Map<String, Object> c = ownCourse(r, r.param(0));
        Req.Upload u = r.upload;
        if (u.size == 0) throw bad("الملف فارغ");
        if (!u.mime.startsWith("image/")) throw bad("صورة الغلاف يجب أن تكون ملف صورة");
        if (u.size > MAX_COVER_BYTES) throw bad("حجم صورة الغلاف يجب ألا يتجاوز 8 ميجابايت");
        deleteFile(s(c, "cover"));
        c.put("cover", u.storedName());
        u.kept = true;
        return obj("course", trainerCourseCard(c));
    }

    private Object uploadVideo(Req r) {
        Map<String, Object> c = ownCourse(r, r.param(0));
        Req.Upload u = r.upload;
        String title = text(r.q("title"), 150, "أدخل عنوان المقطع");
        String description = text(r.q("description"), 3000, null);
        if (u.size == 0) throw bad("الملف فارغ");
        if (!isVideo(u)) throw bad("الملف يجب أن يكون مقطع فيديو (mp4 أو webm مثلًا)");
        long position;
        try { position = Long.parseLong(r.q("position")); } catch (NumberFormatException e) { position = 0; }
        Map<String, Object> v = db.insert("videos", obj("courseId", c.get("id"), "title", title, "description", description,
                "file", u.storedName(), "mime", u.mime, "size", u.size, "source", "upload", "liveId", "",
                "duration", 0L, "order", 0L));
        u.kept = true;
        placeVideo(s(c, "id"), v, position);
        return obj("video", videoView(v));
    }

    private Object updateVideo(Req r) {
        Map<String, Object> v = ownVideo(r, r.param(0));
        if (r.has("title")) v.put("title", text(r.str("title"), 150, "أدخل عنوان المقطع"));
        if (r.has("description")) v.put("description", text(r.str("description"), 3000, null));
        if (r.has("position")) placeVideo(s(v, "courseId"), v, r.lng("position", 0));
        return obj("video", videoView(v));
    }

    private Object deleteVideo(Req r) {
        Map<String, Object> v = ownVideo(r, r.param(0));
        final String id = s(v, "id");
        deleteFile(s(v, "file"));
        db.removeIf("watches", w -> id.equals(w.get("videoId")));
        for (Map<String, Object> l : db.table("lives")) if (id.equals(l.get("recordingVideoId"))) l.put("recordingVideoId", "");
        for (Map<String, Object> e : db.table("enrollments")) if (id.equals(e.get("lastVideoId"))) e.put("lastVideoId", "");
        db.removeIf("videos", x -> id.equals(x.get("id")));
        renumberVideos(s(v, "courseId"));
        return null;
    }

    // ------------------------------------------------------------ live

    private Object trainerLives(Req r) {
        final String uid = r.userId();
        return obj("lives", liveViews(db.where("lives", x -> uid.equals(x.get("trainerId"))), true),
                "courses", courseOptions(r));
    }

    private void readLive(Req r, Map<String, Object> l) {
        l.put("title", text(r.str("title"), 150, "أدخل عنوان البث"));
        l.put("courseId", ownCourse(r, r.str("courseId")).get("id"));
        long startsAt = r.lng("startsAt", 0);
        if (startsAt <= 0) throw bad("حدد تاريخ البث ووقته");
        long duration = r.lng("durationMinutes", 60);
        if (duration < 5 || duration > 600) throw bad("مدة البث يجب أن تكون بين 5 و 600 دقيقة");
        String url = text(r.str("url"), 500, "أدخل رابط البث");
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw bad("رابط البث يجب أن يبدأ بـ https://");
        l.put("startsAt", startsAt);
        l.put("durationMinutes", duration);
        l.put("url", url);
    }

    private Object createLive(Req r) {
        Map<String, Object> l = obj("trainerId", r.userId(), "endedAt", 0L, "recordingVideoId", "");
        readLive(r, l);
        return obj("live", liveView(db.insert("lives", l), now(), true));
    }

    private Object updateLive(Req r) {
        Map<String, Object> l = ownLive(r, r.param(0));
        readLive(r, l);
        // Rescheduling an ended session into the future reopens it.
        if (n(l, "startsAt") + n(l, "durationMinutes") * 60_000L > now() && s(l, "recordingVideoId").isEmpty()) l.put("endedAt", 0L);
        return obj("live", liveView(l, now(), true));
    }

    private Object endLive(Req r) {
        Map<String, Object> l = ownLive(r, r.param(0));
        if (!"ended".equals(liveStatus(l, now()))) l.put("endedAt", now());
        return obj("live", liveView(l, now(), true));
    }

    private Object deleteLive(Req r) {
        final String id = ownLive(r, r.param(0)).get("id").toString();
        db.removeIf("lives", x -> id.equals(x.get("id")));
        return null;
    }

    private Object uploadRecording(Req r) {
        Map<String, Object> l = ownLive(r, r.param(0));
        Req.Upload u = r.upload;
        if (!"ended".equals(liveStatus(l, now()))) throw bad("يمكن رفع التسجيل بعد انتهاء البث");
        String existing = s(l, "recordingVideoId");
        if (!existing.isEmpty() && db.find("videos", existing) != null) throw bad("تم رفع تسجيل هذا البث مسبقًا");
        if (u.size == 0) throw bad("الملف فارغ");
        if (!isVideo(u)) throw bad("الملف يجب أن يكون مقطع فيديو (mp4 أو webm مثلًا)");
        String title = r.q("title").isEmpty() ? s(l, "title") : text(r.q("title"), 150, null);
        String courseId = s(l, "courseId");
        Map<String, Object> v = db.insert("videos", obj("courseId", courseId, "title", title,
                "description", text(r.q("description"), 3000, null), "file", u.storedName(), "mime", u.mime,
                "size", u.size, "source", "live", "liveId", l.get("id"), "duration", 0L, "order", 0L));
        u.kept = true;
        placeVideo(courseId, v, 0);
        l.put("recordingVideoId", v.get("id"));
        if (n(l, "endedAt") == 0) l.put("endedAt", now());
        return obj("live", liveView(l, now(), true), "video", videoView(v));
    }

    // ------------------------------------------------------------ question banks

    private Map<String, Object> bankView(Map<String, Object> b) {
        final String id = s(b, "id");
        String courseId = s(b, "courseId");
        return obj("id", id, "name", b.get("name"), "courseId", courseId.isEmpty() ? null : courseId,
                "courseTitle", courseId.isEmpty() ? null : courseTitle(courseId),
                "questionCount", db.count("questions", q -> id.equals(q.get("bankId"))),
                "audience", "selected".equals(b.get("audience")) ? "selected" : "all",
                "traineeIds", bankTraineeIds(b),
                "createdAt", b.get("createdAt"));
    }

    /** Trainees picked for a "selected" bank who are still enrolled in its course. */
    private List<Object> bankTraineeIds(Map<String, Object> b) {
        String courseId = s(b, "courseId");
        List<Object> ids = new ArrayList<>();
        if (courseId.isEmpty()) return ids;
        for (Object id : list(b, "traineeIds")) if (enrollment(String.valueOf(id), courseId) != null) ids.add(id);
        return ids;
    }

    /** Whether a trainee may see a bank: enrolled in its course and, if the bank is targeted, picked by the trainer. */
    private boolean bankVisibleTo(Map<String, Object> b, String userId) {
        String courseId = s(b, "courseId");
        if (courseId.isEmpty() || enrollment(userId, courseId) == null) return false;
        return !"selected".equals(b.get("audience")) || list(b, "traineeIds").contains(userId);
    }

    /** Bank view for trainees: hides who else the bank is aimed at. */
    private Map<String, Object> traineeBankView(Map<String, Object> b) {
        Map<String, Object> v = bankView(b);
        v.remove("traineeIds");
        v.remove("audience");
        return v;
    }

    private Object courseTraineeOptions(Req r) {
        final String id = s(ownCourse(r, r.param(0)), "id");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> en : db.where("enrollments", e -> id.equals(e.get("courseId")))) {
            Map<String, Object> u = db.find("users", s(en, "userId"));
            if (u != null) out.add(obj("id", u.get("id"), "name", u.get("name"), "email", u.get("email")));
        }
        out.sort(Comparator.comparing(x -> s(x, "name")));
        return obj("trainees", out);
    }

    private List<Map<String, Object>> trainerBankRows(Req r) {
        final String uid = r.userId();
        List<Map<String, Object>> banks = db.where("banks", b -> uid.equals(b.get("trainerId")));
        banks.sort(byNum("createdAt").reversed());
        return banks;
    }

    private Object trainerBanks(Req r) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> b : trainerBankRows(r)) out.add(bankView(b));
        return obj("banks", out, "courses", courseOptions(r));
    }

    private void readBank(Req r, Map<String, Object> b) {
        b.put("name", text(r.str("name"), 100, "أدخل اسم البنك"));
        final String courseId = r.str("courseId");
        b.put("courseId", courseId.isEmpty() ? "" : ownCourse(r, courseId).get("id"));
        List<Object> traineeIds = new ArrayList<>();
        boolean selected = !courseId.isEmpty() && "selected".equals(r.str("audience"));
        if (selected) {
            for (Object o : r.list("traineeIds")) {
                String uid = String.valueOf(o);
                if (traineeIds.contains(uid)) continue;
                if (enrollment(uid, courseId) == null) throw bad("أحد المتدربين المختارين غير ملتحق بالدورة");
                traineeIds.add(uid);
            }
            if (traineeIds.isEmpty()) throw bad("اختر متدربًا واحدًا على الأقل، أو اجعل البنك لكل متدربي الدورة");
        }
        b.put("audience", selected ? "selected" : "all");
        b.put("traineeIds", traineeIds);
    }

    private Object createBank(Req r) {
        Map<String, Object> b = obj("trainerId", r.userId());
        readBank(r, b);
        return obj("bank", bankView(db.insert("banks", b)));
    }

    private Object updateBank(Req r) {
        Map<String, Object> b = ownBank(r, r.param(0));
        readBank(r, b);
        return obj("bank", bankView(b));
    }

    private Object trainerBank(Req r) {
        Map<String, Object> b = ownBank(r, r.param(0));
        final String id = s(b, "id");
        List<Map<String, Object>> questions = db.where("questions", q -> id.equals(q.get("bankId")));
        questions.sort(byNum("createdAt"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> q : questions) out.add(questionView(q));
        return obj("bank", bankView(b), "questions", out, "courses", courseOptions(r));
    }

    private Object deleteBank(Req r) {
        Map<String, Object> b = ownBank(r, r.param(0));
        final String id = s(b, "id");
        final Set<String> questionIds = new HashSet<>();
        for (Map<String, Object> q : db.where("questions", q -> id.equals(q.get("bankId")))) questionIds.add(s(q, "id"));
        for (Map<String, Object> e : db.table("exams")) list(e, "questionIds").removeIf(q -> questionIds.contains(String.valueOf(q)));
        db.removeIf("questions", q -> id.equals(q.get("bankId")));
        db.removeIf("banks", x -> id.equals(x.get("id")));
        return null;
    }

    private void readQuestion(Req r, Map<String, Object> q) {
        q.put("text", text(r.str("text"), 2000, "أدخل نص السؤال"));
        String type = r.str("type");
        if (!type.equals("mcq") && !type.equals("tf")) throw bad("اختر نوع السؤال");
        List<Object> options = new ArrayList<>();
        if (type.equals("tf")) {
            options.add("صح");
            options.add("خطأ");
        } else {
            for (Object o : r.list("options")) {
                String opt = o == null ? "" : o.toString().trim();
                if (!opt.isEmpty()) options.add(text(opt, 300, null));
            }
            if (options.size() < 2) throw bad("أضف خيارين على الأقل");
            if (options.size() > 6) throw bad("الحد الأقصى ستة خيارات");
        }
        long correct = r.lng("correct", -1);
        if (correct < 0 || correct >= options.size()) throw bad("حدد الإجابة الصحيحة");
        String difficulty = r.str("difficulty");
        if (!DIFFICULTIES.contains(difficulty)) throw bad("اختر مستوى الصعوبة");
        q.put("type", type);
        q.put("options", options);
        q.put("correct", correct);
        q.put("difficulty", difficulty);
        q.put("explanation", text(r.str("explanation"), 3000, null));
    }

    private Object createQuestion(Req r) {
        Map<String, Object> b = ownBank(r, r.param(0));
        Map<String, Object> q = obj("bankId", b.get("id"));
        readQuestion(r, q);
        return obj("question", questionView(db.insert("questions", q)));
    }

    private Object updateQuestion(Req r) {
        Map<String, Object> q = ownQuestionOrNull(r, r.param(0));
        if (q == null) throw notFound("السؤال غير موجود");
        readQuestion(r, q);
        return obj("question", questionView(q));
    }

    private Object deleteQuestion(Req r) {
        Map<String, Object> q = ownQuestionOrNull(r, r.param(0));
        if (q == null) throw notFound("السؤال غير موجود");
        final String id = s(q, "id");
        for (Map<String, Object> e : db.table("exams")) list(e, "questionIds").removeIf(x -> id.equals(String.valueOf(x)));
        db.removeIf("questions", x -> id.equals(x.get("id")));
        return null;
    }

    // ------------------------------------------------------------ exams

    private Object questionPool(Req r) {
        List<Map<String, Object>> banks = new ArrayList<>();
        for (Map<String, Object> b : trainerBankRows(r)) {
            final String id = s(b, "id");
            List<Map<String, Object>> questions = db.where("questions", q -> id.equals(q.get("bankId")));
            questions.sort(byNum("createdAt"));
            List<Map<String, Object>> qs = new ArrayList<>();
            for (Map<String, Object> q : questions) {
                qs.add(obj("id", q.get("id"), "text", q.get("text"), "type", q.get("type"), "difficulty", q.get("difficulty")));
            }
            Map<String, Object> view = bankView(b);
            view.put("questions", qs);
            banks.add(view);
        }
        return obj("banks", banks, "courses", courseOptions(r));
    }

    private Object trainerExams(Req r) {
        final String uid = r.userId();
        List<Map<String, Object>> exams = db.where("exams", e -> uid.equals(e.get("trainerId")));
        exams.sort(byNum("createdAt").reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : exams) out.add(examView(e));
        return obj("exams", out, "courses", courseOptions(r));
    }

    private void readExam(Req r, Map<String, Object> e) {
        e.put("title", text(r.str("title"), 150, "أدخل عنوان الاختبار"));
        e.put("courseId", ownCourse(r, r.str("courseId")).get("id"));
        long duration = r.lng("durationMinutes", 0);
        if (duration < 1 || duration > 600) throw bad("مدة الاختبار يجب أن تكون بين 1 و 600 دقيقة");
        String status = "published".equals(r.str("status")) ? "published" : "draft";
        List<Object> ids = new ArrayList<>();
        for (Object o : r.list("questionIds")) {
            String qid = String.valueOf(o);
            if (ids.contains(qid)) continue;
            if (ownQuestionOrNull(r, qid) == null) throw bad("أحد الأسئلة المختارة لم يعد موجودًا");
            ids.add(qid);
        }
        if (status.equals("published") && ids.isEmpty()) throw bad("اختر سؤالًا واحدًا على الأقل قبل نشر الاختبار");
        e.put("durationMinutes", duration);
        e.put("status", status);
        e.put("questionIds", ids);
    }

    private Object createExam(Req r) {
        Map<String, Object> e = obj("trainerId", r.userId());
        readExam(r, e);
        return obj("exam", examView(db.insert("exams", e)));
    }

    private Object updateExam(Req r) {
        Map<String, Object> e = ownExam(r, r.param(0));
        readExam(r, e);
        return obj("exam", examView(e));
    }

    private Object trainerExam(Req r) {
        Map<String, Object> e = ownExam(r, r.param(0));
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Object id : existingQuestionIds(e)) {
            Map<String, Object> q = db.find("questions", String.valueOf(id));
            Map<String, Object> view = questionView(q);
            Map<String, Object> bank = db.find("banks", s(q, "bankId"));
            view.put("bankName", bank == null ? "" : bank.get("name"));
            questions.add(view);
        }
        return obj("exam", examView(e), "questions", questions);
    }

    private Object deleteExam(Req r) {
        final String id = s(ownExam(r, r.param(0)), "id");
        db.removeIf("attempts", a -> id.equals(a.get("examId")));
        db.removeIf("exams", x -> id.equals(x.get("id")));
        return null;
    }

    private Object trainerTrainees(Req r) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : trainerCourseRows(r)) {
            out.add(obj("id", c.get("id"), "title", c.get("title"), "trainees", traineeRows(s(c, "id"))));
        }
        return obj("courses", out);
    }

    // ============================================================ trainee side

    private Map<String, Object> traineeCourseCard(Map<String, Object> c) {
        final String id = s(c, "id");
        return obj("id", id, "title", c.get("title"), "description", s(c, "description"),
                "cover", mediaUrl(s(c, "cover")), "trainerName", userName(s(c, "trainerId")),
                "videos", videosOf(id).size(), "exams", examsOf(id, true).size(), "createdAt", c.get("createdAt"));
    }

    private Map<String, Object> requireCourse(String id) {
        Map<String, Object> c = db.find("courses", id);
        if (c == null) throw notFound("الدورة غير موجودة");
        return c;
    }

    private Map<String, Object> requireEnrolled(Req r, String courseId) {
        Map<String, Object> e = enrollment(r.userId(), courseId);
        if (e == null) throw new ApiError(403, "التحق بالدورة أولًا لتتمكن من الوصول إلى محتواها");
        return e;
    }

    private List<Map<String, Object>> enrolledCourses(Req r) {
        final String uid = r.userId();
        List<Map<String, Object>> rows = db.where("enrollments", e -> uid.equals(e.get("userId")));
        rows.sort(Comparator.<Map<String, Object>>comparingLong(e -> Math.max(n(e, "lastActivityAt"), n(e, "createdAt"))).reversed());
        List<Map<String, Object>> courses = new ArrayList<>();
        for (Map<String, Object> e : rows) {
            Map<String, Object> c = db.find("courses", s(e, "courseId"));
            if (c != null) courses.add(c);
        }
        return courses;
    }

    /** Where the "continue" button should take the trainee next. */
    private Map<String, Object> resumeTarget(String userId, String courseId) {
        List<Map<String, Object>> videos = videosOf(courseId);
        Map<String, Object> en = enrollment(userId, courseId);
        String last = s(en, "lastVideoId");
        for (Map<String, Object> v : videos) {
            if (s(v, "id").equals(last) && !watched(userId, last)) return resumeVideo(userId, v);
        }
        for (Map<String, Object> v : videos) if (!watched(userId, s(v, "id"))) return resumeVideo(userId, v);
        for (Map<String, Object> e : examsOf(courseId, true)) {
            if (!submitted(attemptOf(userId, s(e, "id")))) return obj("type", "exam", "id", e.get("id"), "title", e.get("title"));
        }
        if (videos.isEmpty()) return obj("type", "empty", "id", courseId);
        return obj("type", "done", "id", courseId);
    }

    private Map<String, Object> resumeVideo(String userId, Map<String, Object> v) {
        Map<String, Object> w = watchOf(userId, s(v, "id"));
        return obj("type", "video", "id", v.get("id"), "title", v.get("title"), "position", w == null ? 0 : d(w, "position"));
    }

    private Object traineeHome(Req r) {
        String uid = r.userId();
        List<Map<String, Object>> enrolled = new ArrayList<>();
        Set<String> enrolledIds = new HashSet<>();
        for (Map<String, Object> c : enrolledCourses(r)) {
            String id = s(c, "id");
            enrolledIds.add(id);
            enrolled.add(obj("course", traineeCourseCard(c), "progress", progress(uid, id), "resume", resumeTarget(uid, id)));
        }
        List<Map<String, Object>> all = new ArrayList<>(db.table("courses"));
        all.sort(byNum("createdAt").reversed());
        List<Map<String, Object>> available = new ArrayList<>();
        for (Map<String, Object> c : all) if (!enrolledIds.contains(s(c, "id"))) available.add(traineeCourseCard(c));
        return obj("enrolled", enrolled, "available", available);
    }

    private Object enroll(Req r) {
        Map<String, Object> c = requireCourse(r.param(0));
        if (enrollment(r.userId(), s(c, "id")) == null) {
            db.insert("enrollments", obj("userId", r.userId(), "courseId", c.get("id"), "lastVideoId", "", "lastActivityAt", now()));
        }
        return obj("ok", true);
    }

    private Map<String, Object> traineeVideoItem(String userId, Map<String, Object> v) {
        Map<String, Object> w = watchOf(userId, s(v, "id"));
        return obj("id", v.get("id"), "title", v.get("title"), "description", s(v, "description"),
                "order", n(v, "order"), "source", v.get("source"), "duration", n(v, "duration"),
                "watched", w != null && b(w, "watched"), "position", w == null ? 0 : d(w, "position"));
    }

    private Map<String, Object> traineeExamItem(String userId, Map<String, Object> e) {
        Map<String, Object> a = attemptOf(userId, s(e, "id"));
        Map<String, Object> attempt = a == null ? null : obj("id", a.get("id"), "status", attemptStatus(a),
                "score", n(a, "score"), "total", n(a, "total"), "percent", n(a, "percent"), "deadline", n(a, "deadline"));
        return obj("id", e.get("id"), "title", e.get("title"), "courseId", e.get("courseId"),
                "courseTitle", courseTitle(s(e, "courseId")), "durationMinutes", n(e, "durationMinutes"),
                "questionCount", existingQuestionIds(e).size(), "attempt", attempt);
    }

    private Object traineeCourse(Req r) {
        Map<String, Object> c = requireCourse(r.param(0));
        final String id = s(c, "id");
        String uid = r.userId();
        boolean enrolled = enrollment(uid, id) != null;
        List<Map<String, Object>> videos = new ArrayList<>();
        for (Map<String, Object> v : videosOf(id)) videos.add(traineeVideoItem(uid, v));
        Map<String, Object> out = obj("course", traineeCourseCard(c), "enrolled", enrolled, "videos", videos);
        if (enrolled) {
            List<Map<String, Object>> exams = new ArrayList<>();
            for (Map<String, Object> e : examsOf(id, true)) exams.add(traineeExamItem(uid, e));
            out.put("exams", exams);
            out.put("lives", liveViews(db.where("lives", x -> id.equals(x.get("courseId"))), false));
            out.put("progress", progress(uid, id));
            out.put("resume", resumeTarget(uid, id));
        }
        return out;
    }

    private Object traineeVideos(Req r) {
        String uid = r.userId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : enrolledCourses(r)) {
            String id = s(c, "id");
            List<Map<String, Object>> videos = new ArrayList<>();
            for (Map<String, Object> v : videosOf(id)) videos.add(traineeVideoItem(uid, v));
            out.add(obj("id", id, "title", c.get("title"), "videos", videos));
        }
        return obj("courses", out);
    }

    private Object traineeVideo(Req r) {
        Map<String, Object> v = db.find("videos", r.param(0));
        if (v == null) throw notFound("المقطع غير موجود");
        String courseId = s(v, "courseId");
        requireEnrolled(r, courseId);
        String uid = r.userId();
        List<Map<String, Object>> ordered = videosOf(courseId);
        List<Map<String, Object>> playlist = new ArrayList<>();
        int index = 0;
        for (int i = 0; i < ordered.size(); i++) {
            Map<String, Object> item = ordered.get(i);
            if (item.get("id").equals(v.get("id"))) index = i;
            playlist.add(obj("id", item.get("id"), "title", item.get("title"), "order", n(item, "order"),
                    "source", item.get("source"), "duration", n(item, "duration"), "watched", watched(uid, s(item, "id"))));
        }
        Map<String, Object> video = videoView(v);
        Map<String, Object> w = watchOf(uid, s(v, "id"));
        video.put("watched", w != null && b(w, "watched"));
        video.put("position", w == null ? 0 : d(w, "position"));
        return obj("video", video, "course", obj("id", courseId, "title", courseTitle(courseId)),
                "playlist", playlist,
                "prev", index > 0 ? playlist.get(index - 1) : null,
                "next", index < playlist.size() - 1 ? playlist.get(index + 1) : null,
                "progress", progress(uid, courseId));
    }

    private Object videoProgress(Req r) {
        Map<String, Object> v = db.find("videos", r.param(0));
        if (v == null) throw notFound("المقطع غير موجود");
        String courseId = s(v, "courseId");
        Map<String, Object> en = requireEnrolled(r, courseId);
        String uid = r.userId();
        Map<String, Object> w = watchOf(uid, s(v, "id"));
        if (w == null) {
            w = db.insert("watches", obj("userId", uid, "videoId", v.get("id"), "courseId", courseId,
                    "position", 0.0, "played", 0.0, "duration", 0.0, "watched", false));
        }
        double duration = r.dbl("duration", 0);
        if (duration > 0) {
            w.put("duration", duration);
            if (n(v, "duration") == 0) v.put("duration", Math.round(duration));
        } else {
            duration = d(w, "duration");
        }
        double position = Math.max(0, r.dbl("position", 0));
        if (duration > 0) position = Math.min(position, duration);
        w.put("position", position);
        // Only actually-played seconds count, so jumping to the end does not mark a video as watched.
        // They also cannot exceed the real time since the last report (allowing up to 2.5x playback speed),
        // so sending many reports quickly does not fake progress.
        long nowMs = now();
        long last = n(w, "reportedAt");
        double allowed = last > 0 ? 2.5 * (nowMs - last) / 1000.0 + 2 : 15;
        double played = Math.min(Math.min(30, allowed), Math.max(0, r.dbl("played", 0)));
        w.put("played", d(w, "played") + played);
        w.put("reportedAt", nowMs);
        boolean wasWatched = b(w, "watched");
        if (!wasWatched && duration > 0 && d(w, "played") >= WATCHED_RATIO * duration) w.put("watched", true);
        en.put("lastVideoId", v.get("id"));
        en.put("lastActivityAt", now());
        db.touch();
        return obj("watched", b(w, "watched"), "justCompleted", !wasWatched && b(w, "watched"), "progress", progress(uid, courseId));
    }

    private Object traineeLives(Req r) {
        final Set<String> courseIds = new HashSet<>();
        for (Map<String, Object> c : enrolledCourses(r)) courseIds.add(s(c, "id"));
        return obj("lives", liveViews(db.where("lives", l -> courseIds.contains(s(l, "courseId"))), false),
                "serverNow", now());
    }

    private Object traineeExams(Req r) {
        String uid = r.userId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : enrolledCourses(r)) {
            for (Map<String, Object> e : examsOf(s(c, "id"), true)) out.add(traineeExamItem(uid, e));
        }
        return obj("exams", out);
    }

    private Object startExam(Req r) {
        Map<String, Object> e = db.find("exams", r.param(0));
        if (e == null || !"published".equals(e.get("status"))) throw notFound("الاختبار غير متاح");
        requireEnrolled(r, s(e, "courseId"));
        Map<String, Object> a = attemptOf(r.userId(), s(e, "id"));
        if (a != null) return obj("attemptId", a.get("id"), "submitted", submitted(a));
        List<Object> questionIds = existingQuestionIds(e);
        if (questionIds.isEmpty()) throw bad("لا يحتوي هذا الاختبار على أسئلة بعد");
        long start = now();
        a = db.insert("attempts", obj("examId", e.get("id"), "userId", r.userId(), "courseId", e.get("courseId"),
                "questionIds", questionIds, "answers", new LinkedHashMap<String, Object>(),
                "startedAt", start, "deadline", start + n(e, "durationMinutes") * 60_000L,
                "submittedAt", 0L, "score", 0L, "total", (long) questionIds.size(), "percent", 0L));
        Map<String, Object> en = enrollment(r.userId(), s(e, "courseId"));
        if (en != null) en.put("lastActivityAt", start);
        return obj("attemptId", a.get("id"), "submitted", false);
    }

    private Map<String, Object> ownAttempt(Req r) {
        Map<String, Object> a = db.find("attempts", r.param(0));
        if (a == null || !r.userId().equals(a.get("userId"))) throw notFound("المحاولة غير موجودة");
        finalizeIfExpired(a);
        return a;
    }

    private void finalizeIfExpired(Map<String, Object> a) {
        if (!submitted(a) && now() > n(a, "deadline") + ATTEMPT_GRACE_MS) grade(a);
    }

    private void grade(Map<String, Object> a) {
        Map<String, Object> answers = map(a, "answers");
        long total = 0;
        long score = 0;
        for (Object id : list(a, "questionIds")) {
            Map<String, Object> q = db.find("questions", String.valueOf(id));
            if (q == null) continue;
            total++;
            Object chosen = answers.get(String.valueOf(id));
            if (chosen instanceof Number && ((Number) chosen).longValue() == n(q, "correct")) score++;
        }
        a.put("score", score);
        a.put("total", total);
        a.put("percent", total == 0 ? 0L : Math.round(100.0 * score / total));
        a.put("submittedAt", now());
        db.touch();
    }

    private Map<String, Object> cleanAnswers(Map<String, Object> attempt, Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Object id : list(attempt, "questionIds")) {
            Object v = raw.get(String.valueOf(id));
            if (v instanceof Number) out.put(String.valueOf(id), ((Number) v).longValue());
        }
        return out;
    }

    private Map<String, Object> examHeader(Map<String, Object> a) {
        Map<String, Object> e = db.find("exams", s(a, "examId"));
        return obj("id", a.get("examId"), "title", e == null ? "" : e.get("title"),
                "durationMinutes", n(e, "durationMinutes"), "courseId", a.get("courseId"),
                "courseTitle", courseTitle(s(a, "courseId")));
    }

    private Map<String, Object> takePayload(Map<String, Object> a) {
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Object id : list(a, "questionIds")) {
            Map<String, Object> q = db.find("questions", String.valueOf(id));
            if (q == null) continue;
            questions.add(obj("id", q.get("id"), "text", q.get("text"), "type", q.get("type"), "options", list(q, "options")));
        }
        return obj("attemptId", a.get("id"), "submitted", false, "exam", examHeader(a),
                "deadline", n(a, "deadline"), "serverNow", now(), "answers", map(a, "answers"), "questions", questions);
    }

    private Map<String, Object> resultPayload(Map<String, Object> a) {
        Map<String, Object> answers = map(a, "answers");
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Object id : list(a, "questionIds")) {
            Map<String, Object> q = db.find("questions", String.valueOf(id));
            if (q == null) continue;
            Object chosen = answers.get(String.valueOf(id));
            Long chosenIndex = chosen instanceof Number ? ((Number) chosen).longValue() : null;
            questions.add(obj("id", q.get("id"), "text", q.get("text"), "type", q.get("type"),
                    "options", list(q, "options"), "correct", n(q, "correct"), "chosen", chosenIndex,
                    "isCorrect", chosenIndex != null && chosenIndex == n(q, "correct"),
                    "explanation", s(q, "explanation")));
        }
        return obj("attemptId", a.get("id"), "submitted", true, "exam", examHeader(a),
                "score", n(a, "score"), "total", n(a, "total"), "percent", n(a, "percent"),
                "submittedAt", n(a, "submittedAt"), "questions", questions);
    }

    private Object getAttempt(Req r) {
        Map<String, Object> a = ownAttempt(r);
        return submitted(a) ? resultPayload(a) : takePayload(a);
    }

    private Object saveAnswers(Req r) {
        Map<String, Object> a = ownAttempt(r);
        if (submitted(a)) return obj("submitted", true);
        a.put("answers", cleanAnswers(a, r.map("answers")));
        return obj("submitted", false);
    }

    private Object submitAttempt(Req r) {
        Map<String, Object> a = ownAttempt(r);
        if (!submitted(a)) {
            a.put("answers", cleanAnswers(a, r.map("answers")));
            grade(a);
        }
        return resultPayload(a);
    }

    private Object traineeProgress(Req r) {
        String uid = r.userId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : enrolledCourses(r)) {
            String id = s(c, "id");
            List<Map<String, Object>> exams = new ArrayList<>();
            for (Map<String, Object> e : examsOf(id, true)) {
                Map<String, Object> a = attemptOf(uid, s(e, "id"));
                exams.add(obj("id", e.get("id"), "title", e.get("title"), "status", attemptStatus(a),
                        "percent", submitted(a) ? n(a, "percent") : null, "attemptId", a == null ? null : a.get("id")));
            }
            out.add(obj("course", traineeCourseCard(c), "progress", progress(uid, id), "exams", exams));
        }
        return obj("courses", out);
    }

    /** Question banks the trainee may see, grouped by their enrolled courses. */
    private Object traineeBanks(Req r) {
        final String uid = r.userId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : enrolledCourses(r)) {
            final String courseId = s(c, "id");
            List<Map<String, Object>> banks = db.where("banks", b -> courseId.equals(b.get("courseId")) && bankVisibleTo(b, uid));
            banks.sort(byNum("createdAt").reversed());
            List<Map<String, Object>> views = new ArrayList<>();
            for (Map<String, Object> b : banks) views.add(traineeBankView(b));
            out.add(obj("id", courseId, "title", c.get("title"), "banks", views));
        }
        return obj("courses", out);
    }

    /** A bank's questions without the correct answers or explanations, so exams cannot be looked up. */
    private Object traineeBank(Req r) {
        Map<String, Object> b = db.find("banks", r.param(0));
        if (b == null || !bankVisibleTo(b, r.userId())) throw notFound("بنك الأسئلة غير موجود");
        final String id = s(b, "id");
        List<Map<String, Object>> questions = db.where("questions", q -> id.equals(q.get("bankId")));
        questions.sort(byNum("createdAt"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> q : questions) {
            out.add(obj("id", q.get("id"), "text", q.get("text"), "type", q.get("type"),
                    "options", list(q, "options"), "difficulty", q.get("difficulty")));
        }
        return obj("bank", traineeBankView(b), "questions", out);
    }

    /** Practice: checks one answer to a bank question and only then reveals the correct one. */
    private Object checkPracticeAnswer(Req r) {
        Map<String, Object> q = db.find("questions", r.param(0));
        Map<String, Object> bank = q == null ? null : db.find("banks", s(q, "bankId"));
        if (bank == null || !bankVisibleTo(bank, r.userId())) throw notFound("السؤال غير موجود");
        long answer = r.lng("answer", -1);
        if (answer < 0 || answer >= list(q, "options").size()) throw bad("اختر إجابة أولًا");
        return obj("isCorrect", answer == n(q, "correct"), "correct", n(q, "correct"), "explanation", s(q, "explanation"));
    }

    // ============================================================ admin side

    private List<Map<String, Object>> usersWithRole(String role) {
        List<Map<String, Object>> users = db.where("users", u -> role.equals(u.get("role")));
        users.sort(byNum("createdAt").reversed());
        return users;
    }

    private Map<String, Object> trainerRow(Map<String, Object> t) {
        final String id = s(t, "id");
        final Set<String> courseIds = new HashSet<>();
        for (Map<String, Object> c : db.where("courses", c -> id.equals(c.get("trainerId")))) courseIds.add(s(c, "id"));
        Set<String> trainees = new HashSet<>();
        for (Map<String, Object> e : db.table("enrollments")) if (courseIds.contains(s(e, "courseId"))) trainees.add(s(e, "userId"));
        return obj("id", id, "name", t.get("name"), "email", t.get("email"), "createdAt", t.get("createdAt"),
                "courses", courseIds.size(), "trainees", trainees.size(),
                "banks", db.count("banks", b -> id.equals(b.get("trainerId"))));
    }

    private Object adminOverview(Req r) {
        List<Map<String, Object>> trainers = new ArrayList<>();
        for (Map<String, Object> t : usersWithRole("trainer")) trainers.add(trainerRow(t));
        return obj("counts", obj(
                        "trainers", trainers.size(),
                        "trainees", usersWithRole("trainee").size(),
                        "courses", db.table("courses").size(),
                        "exams", db.table("exams").size()),
                "trainers", trainers);
    }

    /** Every course on the platform with its trainer and how much is in it. */
    private Object adminCourses(Req r) {
        List<Map<String, Object>> courses = new ArrayList<>(db.table("courses"));
        courses.sort(byNum("createdAt").reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : courses) {
            final String id = s(c, "id");
            out.add(obj("id", id, "title", c.get("title"), "trainerName", userName(s(c, "trainerId")),
                    "videos", videosOf(id).size(), "exams", examsOf(id, false).size(),
                    "trainees", db.count("enrollments", e -> id.equals(e.get("courseId"))), "createdAt", c.get("createdAt")));
        }
        return obj("courses", out);
    }

    /** Every exam on the platform with its course, trainer, status and how many trainees submitted it. */
    private Object adminExams(Req r) {
        List<Map<String, Object>> exams = new ArrayList<>(db.table("exams"));
        exams.sort(byNum("createdAt").reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : exams) {
            final String id = s(e, "id");
            out.add(obj("id", id, "title", e.get("title"), "courseTitle", courseTitle(s(e, "courseId")),
                    "trainerName", userName(s(e, "trainerId")), "status", e.get("status"),
                    "questions", existingQuestionIds(e).size(), "durationMinutes", n(e, "durationMinutes"),
                    "submitted", db.count("attempts", a -> id.equals(a.get("examId")) && submitted(a))));
        }
        return obj("exams", out);
    }

    private Object createTrainer(Req r) {
        String name = text(r.str("name"), 80, "أدخل اسم المدرب");
        if (name.length() < 2) throw bad("الاسم قصير جدًا");
        String email = r.str("email").toLowerCase(Locale.ROOT);
        if (email.length() > 120 || !EMAIL.matcher(email).matches()) throw bad("البريد الإلكتروني غير صالح");
        String password = r.raw("password");
        checkNewPassword(password);
        if (userByEmail(email) != null) throw new ApiError(409, "هذا البريد مستخدم لحساب آخر");
        return obj("trainer", trainerRow(createUser(name, email, password, "trainer")));
    }

    private Map<String, Object> userWithRole(String id, String role, String missing) {
        Map<String, Object> u = db.find("users", id);
        if (u == null || !role.equals(u.get("role"))) throw notFound(missing);
        return u;
    }

    private Object resetTrainerPassword(Req r) {
        Map<String, Object> t = userWithRole(r.param(0), "trainer", "المدرب غير موجود");
        String password = r.raw("password");
        checkNewPassword(password);
        setPassword(t, password);
        loginFailures.remove(s(t, "email").toLowerCase(Locale.ROOT));
        final String id = s(t, "id");
        db.removeIf("sessions", x -> id.equals(x.get("userId")));
        db.touch();
        return obj("ok", true);
    }

    /** Removes a trainer together with their courses, question banks and exams. */
    private Object deleteTrainer(Req r) {
        Map<String, Object> t = userWithRole(r.param(0), "trainer", "المدرب غير موجود");
        final String id = s(t, "id");
        for (Map<String, Object> c : db.where("courses", c -> id.equals(c.get("trainerId")))) removeCourse(c);
        final Set<String> bankIds = new HashSet<>();
        for (Map<String, Object> b : db.where("banks", b -> id.equals(b.get("trainerId")))) bankIds.add(s(b, "id"));
        db.removeIf("questions", q -> bankIds.contains(s(q, "bankId")));
        db.removeIf("banks", b -> id.equals(b.get("trainerId")));
        db.removeIf("exams", e -> id.equals(e.get("trainerId")));
        db.removeIf("lives", l -> id.equals(l.get("trainerId")));
        db.removeIf("sessions", x -> id.equals(x.get("userId")));
        db.removeIf("users", u -> id.equals(u.get("id")));
        return null;
    }

    private Object adminTrainees(Req r) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> u : usersWithRole("trainee")) {
            final String id = s(u, "id");
            List<Object> courses = new ArrayList<>();
            for (Map<String, Object> e : db.where("enrollments", e -> id.equals(e.get("userId")))) {
                String title = courseTitle(s(e, "courseId"));
                if (!title.isEmpty()) courses.add(title);
            }
            out.add(obj("id", id, "name", u.get("name"), "email", u.get("email"), "createdAt", u.get("createdAt"), "courses", courses));
        }
        return obj("trainees", out);
    }

    private Object deleteTrainee(Req r) {
        final String id = s(userWithRole(r.param(0), "trainee", "المتدرب غير موجود"), "id");
        db.removeIf("enrollments", x -> id.equals(x.get("userId")));
        db.removeIf("watches", x -> id.equals(x.get("userId")));
        db.removeIf("attempts", x -> id.equals(x.get("userId")));
        db.removeIf("sessions", x -> id.equals(x.get("userId")));
        for (Map<String, Object> b : db.table("banks")) list(b, "traineeIds").removeIf(x -> id.equals(String.valueOf(x)));
        db.removeIf("users", u -> id.equals(u.get("id")));
        return null;
    }
}
