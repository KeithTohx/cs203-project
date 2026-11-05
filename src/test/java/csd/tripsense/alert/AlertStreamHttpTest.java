package csd.tripsense.alert;

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
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Real HTTP, real security/session cookies, real SSE bytes and real SQLite transactions. Only AI is mocked. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gdelt.ingest.enabled=false", "impact.eval.enabled=false", "impact.eval.new-itinerary.enabled=false", "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.datasource.hikari.maximum-pool-size=2",
        "alerts.reconcile.initial-delay-ms=86400000", "alerts.email.enabled=false"
})
class AlertStreamHttpTest {
    private static final String PASSWORD = "correct-horse-battery";
    private static final Path DATABASE = database();
    private static Path database() {
        try { return Files.createTempDirectory("tripsense-sse-http-").resolve("test.db"); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE + "?foreign_keys=on");
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired CountryRepository countries;
    @Autowired ItineraryRepository trips;
    @Autowired NewsRepository news;
    @Autowired ImpactService impacts;
    @Autowired AlertService alerts;
    @Autowired SessionService sessions;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean ChatService ai;
    private User owner;
    private User other;
    private Itinerary trip;
    private News article;

    @BeforeEach void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String hash = passwords.encode(PASSWORD);
        owner = users.saveAndFlush(new User("owner" + suffix + "@example.com", "owner" + suffix, hash, "USER"));
        other = users.saveAndFlush(new User("other" + suffix + "@example.com", "other" + suffix, hash, "USER"));
        Country country = new Country(); country.setName("Japan"); country = countries.saveAndFlush(country);
        trip = new Itinerary(); trip.setUser(owner); trip.setCountry(country); trip.setName("Japan ski trip");
        trip = trips.saveAndFlush(trip);
        article = newArticle();
        when(ai.ask(anyString(), any(Class.class))).thenAnswer(invocation -> {
            var constructor = ((Class<?>) invocation.getArgument(1)).getDeclaredConstructor(boolean.class, String.class);
            constructor.setAccessible(true); return constructor.newInstance(true, "Resort closed after earthquake");
        });
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    private News newArticle() {
        News article = new News(); article.setTitle("Earthquake near Hokkaido"); article.setDescription("Ski lifts closed");
        article.setUrl("https://example.com/" + UUID.randomUUID()); return news.saveAndFlush(article);
    }

    private HttpClient login(User account) throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        try {
            client.send(HttpRequest.newBuilder(uri("/")).GET().build(), HttpResponse.BodyHandlers.discarding());
            String csrf = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
                    .findFirst().orElseThrow().getValue();
            String form = "email=" + URLEncoder.encode(account.getEmail(), StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8);
            var response = client.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("X-XSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(200);
            return client;
        } catch (Exception | AssertionError exception) { client.close(); throw exception; }
    }

    @Test void streamRejectsAnonymousHttpRequests() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(uri("/api/alerts/stream"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) { assertThat(response.statusCode()).isEqualTo(401); }
        }
    }

    @Test void committedAssessmentPushesSerializedSseOnlyToTheOwnerWithoutRefreshing() throws Exception {
        try (HttpClient ownerClient = login(owner); HttpClient otherClient = login(other);
             SseProbe ownerStream = new SseProbe(ownerClient); SseProbe otherStream = new SseProbe(otherClient)) {
            ownerStream.ready(); otherStream.ready();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                impacts.evaluateItineraryForNews(trip, article);
                assertThat(ownerStream.poll()).isNull();
            });
            String frame = ownerStream.next();
            assertThat(frame).contains("event:alert\n", "id:");
            var payload = json.readTree(frame.lines().filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5)).findFirst().orElseThrow());
            assertThat(payload.get("itineraryId").asLong()).isEqualTo(trip.getId());
            assertThat(payload.get("reason").asText()).isEqualTo("Resort closed after earthquake");
            assertThat(payload.has("password")).isFalse();
            var saved = alerts.list(owner.getId(), false, null, 20).content().getFirst();
            assertThat(payload.get("id").asLong()).isEqualTo(saved.id());
            assertThat(frame).contains("id:" + saved.id());
            assertThat(otherStream.poll()).isNull();
            impacts.evaluateItineraryForNews(trip, article); // a cached assessment sends no duplicate
            assertThat(ownerStream.poll()).isNull();
        }
    }

    @Test void rolledBackAssessmentDoesNotReachTheHttpStreamOrHistory() throws Exception {
        try (HttpClient client = login(owner); SseProbe stream = new SseProbe(client)) {
            stream.ready();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                impacts.evaluateItineraryForNews(trip, article); status.setRollbackOnly();
            });
            assertThat(stream.poll()).isNull();
            assertThat(alerts.list(owner.getId(), false, null, 20).content()).isEmpty();
        }
    }

    @Test void expiredSessionClosesTheStreamAndReconnectCanRecoverHistory() throws Exception {
        try (HttpClient client = login(owner); SseProbe stream = new SseProbe(client)) {
            stream.ready(); sessions.expireSessionsFor(owner.getId());
            impacts.evaluateItineraryForNews(trip, article);
            stream.awaitClosed();
            assertThat(stream.poll()).isNull();
            var response = client.send(HttpRequest.newBuilder(uri("/api/alerts/stream")).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) { assertThat(response.statusCode()).isEqualTo(401); }
        }
        try (HttpClient client = login(owner); SseProbe reconnected = new SseProbe(client)) {
            reconnected.ready();
            var history = client.send(HttpRequest.newBuilder(uri("/api/alerts")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(history.statusCode()).isEqualTo(200);
            assertThat(json.readTree(history.body()).get("content").size()).isEqualTo(1);
            impacts.evaluateItineraryForNews(trip, newArticle());
            assertThat(reconnected.next()).contains("event:alert\n");
        }
    }

    private class SseProbe implements AutoCloseable {
        private final InputStream body;
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final CountDownLatch ended = new CountDownLatch(1);
        private final ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "test-sse-reader"); thread.setDaemon(true); return thread;
        });

        SseProbe(HttpClient client) throws Exception {
            var response = client.send(HttpRequest.newBuilder(uri("/api/alerts/stream"))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "text/event-stream").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            body = response.body();
            try {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
                assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
                reader.submit(() -> {
                    try (var lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                        StringBuilder frame = new StringBuilder(); String line;
                        while ((line = lines.readLine()) != null) {
                            if (line.isEmpty()) {
                                if (frame.toString().contains("event:")) frames.add(frame.toString());
                                frame.setLength(0);
                            } else frame.append(line).append('\n');
                        }
                    } catch (IOException ignored) { /* closing a probe deliberately interrupts its read */ }
                    finally { ended.countDown(); }
                });
            } catch (Exception | AssertionError exception) { close(); throw exception; }
        }

        String next() throws InterruptedException {
            String frame = frames.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("SSE event within five seconds").isNotNull(); return frame;
        }
        void ready() throws InterruptedException { assertThat(next()).contains("event:ready\n", "retry:3000\n"); }
        String poll() {
            try { return frames.poll(150, TimeUnit.MILLISECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
        }
        void awaitClosed() throws InterruptedException { assertThat(ended.await(5, TimeUnit.SECONDS)).isTrue(); }
        @Override public void close() throws IOException { body.close(); reader.shutdownNow(); }
    }
}
