package csd.tripsense.gdelt;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

import com.sun.net.httpserver.HttpServer;

// unit tests for GkgClient
// a small web server from the jdk runs on this machine and plays the part of gdelt, so the
// client really sends http requests but never touches the internet
@DisplayName("GkgClient Unit Tests")
class GkgClientTest {

    private HttpServer fakeGdelt;
    private String baseUrl;

    @BeforeEach
    void startFakeGdelt() throws IOException {
        // port 0 lets the system pick any free port
        fakeGdelt = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeGdelt.start();
        baseUrl = "http://localhost:" + fakeGdelt.getAddress().getPort();
    }

    @AfterEach
    void stopFakeGdelt() {
        fakeGdelt.stop(0);
    }

    // ---------------------------------------------------------------- helpers

    private void serve(String path, int status, byte[] body) {
        fakeGdelt.createContext(path, exchange -> {
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private void serveText(String path, String text) {
        serve(path, 200, text.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] zipOf(String fileName, String content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(fileName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private byte[] emptyZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new ZipOutputStream(bytes).close();
        return bytes.toByteArray();
    }

    // ===== LATEST GKG URL TESTS =====

    @Test
    @DisplayName("Should pick the gkg file from lastupdate.txt and upgrade it to https")
    void latestGkgUrl_ShouldReturnGkgLineAsHttps() {
        // Arrange: the real file lists three files, size and hash first
        serveText("/lastupdate.txt", """
                150383 297a16b493de7cf6ca809a7cc31d0b93 http://data.gdeltproject.org/gdeltv2/20261028120000.export.CSV.zip
                318084 bb27f78ba45f69a17ea6ed7755e9f8ff http://data.gdeltproject.org/gdeltv2/20261028120000.mentions.CSV.zip
                10768507 ea8dde0beb0ba98810a92db068c0ce99 http://data.gdeltproject.org/gdeltv2/20261028120000.gkg.csv.zip
                """);
        GkgClient client = new GkgClient(baseUrl + "/lastupdate.txt");

        // Act
        Optional<String> url = client.latestGkgUrl();

        // Assert
        assertEquals(Optional.of("https://data.gdeltproject.org/gdeltv2/20261028120000.gkg.csv.zip"), url);
    }

    @Test
    @DisplayName("Should return empty when lastupdate.txt lists no gkg file")
    void latestGkgUrl_ShouldReturnEmpty_WhenNoGkgLine() {
        // Arrange
        serveText("/lastupdate.txt", """
                150383 297a16b493de7cf6ca809a7cc31d0b93 http://data.gdeltproject.org/gdeltv2/20261028120000.export.CSV.zip
                """);
        GkgClient client = new GkgClient(baseUrl + "/lastupdate.txt");

        // Act + Assert
        assertTrue(client.latestGkgUrl().isEmpty());
    }

    @Test
    @DisplayName("Should skip lines that are cut short or blank")
    void latestGkgUrl_ShouldIgnoreMalformedLines() {
        // Arrange
        serveText("/lastupdate.txt", """

                just-one-part
                10768507 ea8dde0beb0ba98810a92db068c0ce99 https://data.gdeltproject.org/gdeltv2/20261028121500.gkg.csv.zip
                """);
        GkgClient client = new GkgClient(baseUrl + "/lastupdate.txt");

        // Act + Assert
        assertEquals(Optional.of("https://data.gdeltproject.org/gdeltv2/20261028121500.gkg.csv.zip"),
                client.latestGkgUrl());
    }

    @Test
    @DisplayName("Should throw when gdelt answers with an error, so the ingest job can try again next poll")
    void latestGkgUrl_ShouldThrow_WhenServerErrors() {
        // Arrange
        serve("/lastupdate.txt", 503, new byte[0]);
        GkgClient client = new GkgClient(baseUrl + "/lastupdate.txt");

        // Act + Assert
        assertThrows(RestClientResponseException.class, client::latestGkgUrl);
    }

    // ===== READ LINES TESTS =====

    @Test
    @DisplayName("Should unzip the file and hand over every line in order")
    void readLines_ShouldStreamEveryLine() throws IOException {
        // Arrange
        serve("/latest.gkg.csv.zip", 200, zipOf("latest.gkg.csv", "first\tarticle\nsecond\tarticle\nthird\tarticle\n"));
        GkgClient client = new GkgClient(baseUrl + "/lastupdate.txt");
        List<String> lines = new ArrayList<>();

        // Act
        client.readLines(baseUrl + "/latest.gkg.csv.zip", lines::add);

        // Assert
        assertEquals(List.of("first\tarticle", "second\tarticle", "third\tarticle"), lines);
    }

    @Test
    @DisplayName("Should hand over nothing when the zip has no file inside")
    void readLines_ShouldDoNothing_WhenZipIsEmpty() throws IOException {
        // Arrange
        serve("/empty.gkg.csv.zip", 200, emptyZip());
        GkgClient client = new GkgClient(baseUrl + "/lastupdate.txt");
        List<String> lines = new ArrayList<>();

        // Act
        client.readLines(baseUrl + "/empty.gkg.csv.zip", lines::add);

        // Assert
        assertTrue(lines.isEmpty());
    }

    @Test
    @DisplayName("Should throw when the zip is missing")
    void readLines_ShouldThrow_WhenZipMissing() {
        // Arrange
        serve("/missing.gkg.csv.zip", 404, new byte[0]);
        GkgClient client = new GkgClient(baseUrl + "/lastupdate.txt");

        // Act + Assert
        assertThrows(RestClientResponseException.class,
                () -> client.readLines(baseUrl + "/missing.gkg.csv.zip", line -> { }));
    }
}
