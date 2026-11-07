package csd.tripsense.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import csd.tripsense.auth.SessionService;
import csd.tripsense.chat.ChatService;
import csd.tripsense.country.Country;
import csd.tripsense.country.CountryRepository;
import csd.tripsense.impact.ImpactService;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryRepository;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsRepository;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// integration tests for the live alert stream, using a real http server and real session cookies
// the stream is read byte by byte as server-sent events, against a temporary sqlite file.
// only the AI model is mocked, and it always says the trip is affected
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gdelt.ingest.enabled=false",
        "impact.eval.enabled=false",
        "impact.eval.new-itinerary.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=2",
        "alerts.reconcile.initial-delay-ms=86400000",
        "alerts.email.enabled=false"
})
@DisplayName("Alert Stream Real Server Integration Tests")
class AlertStreamHttpTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final Path DATABASE = temporaryDatabase();

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private ItineraryRepository itineraryRepository;

    @Autowired
    private NewsRepository newsRepository;

    @Autowired
    private ImpactService impactService;

    @Autowired
    private AlertService alertService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private ChatService ai;

    private User owner;
    private User stranger;
    private Itinerary skiTrip;
    private News earthquake;

    private static Path temporaryDatabase() {
        try {
            return Files.createTempDirectory("tripsense-sse-http-").resolve("test.db");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE + "?foreign_keys=on");
    }

    @BeforeEach
    void setUp() {
        owner = savedUser("owner");
        stranger = savedUser("stranger");

        Country japan = new Country();
        japan.setName("Japan");
        japan = countryRepository.saveAndFlush(japan);

        skiTrip = new Itinerary();
        skiTrip.setUser(owner);
        skiTrip.setCountry(japan);
        skiTrip.setName("Japan ski trip");
        skiTrip = itineraryRepository.saveAndFlush(skiTrip);

        earthquake = savedNews();

        when(ai.ask(anyString(), any(Class.class))).thenAnswer(invocation -> {
            Class<?> verdictType = invocation.getArgument(1);
            var constructor = verdictType.getDeclaredConstructor(boolean.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(true, "Resort closed after earthquake");
        });
    }

    // ===== HELPERS =====

    // the database is shared by every test in this class, so each account gets a unique email
    private User savedUser(String name) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String hash = passwordEncoder.encode(PASSWORD);
        User account = new User(name + suffix + "@example.com", name + suffix, hash, "USER");
        return userRepository.saveAndFlush(account);
    }

    private News savedNews() {
        News article = new News();
        article.setTitle("Earthquake near Hokkaido");
        article.setDescription("Ski lifts closed");
        article.setUrl("https://example.com/" + UUID.randomUUID());
        return newsRepository.saveAndFlush(article);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpRequest streamRequest() {
        return HttpRequest.newBuilder(uri("/api/alerts/stream"))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
    }

    // logs in through the real login endpoint, so the client holds a real session cookie
    private HttpClient login(User account) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        try {
            // loading any page sets the csrf cookie, which the login form has to send back as a header
            client.send(HttpRequest.newBuilder(uri("/")).GET().build(), HttpResponse.BodyHandlers.discarding());
            String csrfToken = cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                    .findFirst()
                    .orElseThrow()
                    .getValue();

            String email = URLEncoder.encode(account.getEmail(), StandardCharsets.UTF_8);
            String password = URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
            String form = "email=" + email + "&password=" + password;

            HttpRequest loginRequest = HttpRequest.newBuilder(uri("/api/auth/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-XSRF-TOKEN", csrfToken)
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<Void> response = client.send(loginRequest, HttpResponse.BodyHandlers.discarding());
            assertEquals(200, response.statusCode());
            return client;
        } catch (Exception | AssertionError exception) {
            client.close();
            throw exception;
        }
    }

    // pulls the json out of the "data:" line of an event
    private JsonNode dataOf(String frame) {
        String data = frame.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()))
                .findFirst()
                .orElseThrow();
        return objectMapper.readTree(data);
    }

    // ===== STREAM TESTS =====

    @Test
    @DisplayName("Should return 401 when opening the alert stream without logging in")
    void getStream_ShouldReturn401_WhenNotLoggedIn() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            // Act
            HttpResponse<InputStream> response = client.send(streamRequest(), HttpResponse.BodyHandlers.ofInputStream());

            // Assert
            try (InputStream body = response.body()) {
                assertEquals(401, response.statusCode());
            }
        }
    }

    @Test
    @DisplayName("Should push the alert only to the owner once the assessment commits")
    void getStream_ShouldPushAlertToOwnerOnly_WhenAssessmentCommits() throws Exception {
        try (HttpClient ownerClient = login(owner);
                HttpClient strangerClient = login(stranger);
                SseProbe ownerStream = new SseProbe(ownerClient);
                SseProbe strangerStream = new SseProbe(strangerClient)) {
            // Arrange
            ownerStream.awaitReady();
            strangerStream.awaitReady();

            // Act
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                impactService.evaluateItineraryForNews(skiTrip, earthquake);
                // nothing should be pushed before the transaction commits
                assertNull(ownerStream.poll());
            });
            String frame = ownerStream.next();

            // Assert
            AlertResponse saved = alertService.list(owner.getId(), false, null, 20).content().getFirst();
            JsonNode payload = dataOf(frame);

            assertTrue(frame.contains("event:alert\n"), "event was: " + frame);
            assertTrue(frame.contains("id:" + saved.id()), "event was: " + frame);
            assertEquals(saved.id(), payload.get("id").asLong());
            assertEquals(skiTrip.getId(), payload.get("itineraryId").asLong());
            assertEquals("Resort closed after earthquake", payload.get("reason").asText());
            assertFalse(payload.has("password"));
            assertNull(strangerStream.poll());

            // a cached assessment should not push the same alert again
            impactService.evaluateItineraryForNews(skiTrip, earthquake);
            assertNull(ownerStream.poll());
        }
    }

    @Test
    @DisplayName("Should not push or store an alert when the assessment rolls back")
    void getStream_ShouldNotPushAlert_WhenAssessmentRollsBack() throws Exception {
        try (HttpClient client = login(owner);
                SseProbe stream = new SseProbe(client)) {
            // Arrange
            stream.awaitReady();

            // Act
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                impactService.evaluateItineraryForNews(skiTrip, earthquake);
                transaction.setRollbackOnly();
            });

            // Assert
            assertNull(stream.poll());
            assertTrue(alertService.list(owner.getId(), false, null, 20).content().isEmpty());
        }
    }

    @Test
    @DisplayName("Should close the stream when the session expires, and show the missed alert after logging in again")
    void getStream_ShouldCloseAndRecoverHistory_WhenSessionExpires() throws Exception {
        try (HttpClient client = login(owner);
                SseProbe stream = new SseProbe(client)) {
            // Arrange
            stream.awaitReady();

            // Act
            sessionService.expireSessionsFor(owner.getId());
            impactService.evaluateItineraryForNews(skiTrip, earthquake);

            // Assert
            stream.awaitClosed();
            assertNull(stream.poll());

            HttpResponse<InputStream> retry = client.send(streamRequest(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = retry.body()) {
                assertEquals(401, retry.statusCode());
            }
        }

        // after logging in again, the missed alert is in history and new alerts are pushed live
        try (HttpClient client = login(owner);
                SseProbe reconnected = new SseProbe(client)) {
            reconnected.awaitReady();

            HttpRequest historyRequest = HttpRequest.newBuilder(uri("/api/alerts")).GET().build();
            HttpResponse<String> history = client.send(historyRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, history.statusCode());
            assertEquals(1, objectMapper.readTree(history.body()).get("content").size());

            impactService.evaluateItineraryForNews(skiTrip, savedNews());
            String frame = reconnected.next();
            assertTrue(frame.contains("event:alert\n"), "event was: " + frame);
        }
    }

    // opens the alert stream and reads it on a background thread, keeping each named event as one frame
    private class SseProbe implements AutoCloseable {

        private final InputStream body;
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final CountDownLatch ended = new CountDownLatch(1);
        private final ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "test-sse-reader");
            thread.setDaemon(true);
            return thread;
        });

        SseProbe(HttpClient client) throws Exception {
            HttpResponse<InputStream> response = client.send(streamRequest(), HttpResponse.BodyHandlers.ofInputStream());
            body = response.body();

            try {
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                String cacheControl = response.headers().firstValue("Cache-Control").orElse("");
                assertEquals(200, response.statusCode());
                assertTrue(contentType.startsWith("text/event-stream"), "content type was: " + contentType);
                assertEquals("no-store", cacheControl);

                reader.submit(this::readFrames);
            } catch (Exception | AssertionError exception) {
                close();
                throw exception;
            }
        }

        private void readFrames() {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                StringBuilder frame = new StringBuilder();
                String line;
                while ((line = lines.readLine()) != null) {
                    if (!line.isEmpty()) {
                        frame.append(line).append('\n');
                        continue;
                    }

                    // a blank line ends a frame. heartbeats have no event name, so they are skipped
                    if (frame.toString().contains("event:")) {
                        frames.add(frame.toString());
                    }
                    frame.setLength(0);
                }
            } catch (IOException ignored) {
                // closing the probe interrupts the read on purpose
            } finally {
                ended.countDown();
            }
        }

        String next() throws InterruptedException {
            String frame = frames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame, "expected an SSE event within five seconds");
            return frame;
        }

        // returns null when nothing arrives in a short window
        String poll() {
            try {
                return frames.poll(150, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }

        void awaitReady() throws InterruptedException {
            String frame = next();
            assertTrue(frame.contains("event:ready\n"), "first event was: " + frame);
            assertTrue(frame.contains("retry:3000\n"), "first event was: " + frame);
        }

        void awaitClosed() throws InterruptedException {
            assertTrue(ended.await(5, TimeUnit.SECONDS), "expected the stream to close within five seconds");
        }

        @Override
        public void close() throws IOException {
            body.close();
            reader.shutdownNow();
        }
    }
}
