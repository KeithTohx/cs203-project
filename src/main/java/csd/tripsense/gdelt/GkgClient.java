package csd.tripsense.gdelt;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * GkgClient: fetches lastupdate.txt, finds the .gkg.csv.zip link, upgrades it to https, and streams the zip’s lines. 
 * It uses a JDK HTTP client that follows redirects. 
*/
@Component
public class GkgClient {

    private final RestClient restClient;
    private final String lastUpdateUrl;

    public GkgClient(@Value("${gdelt.lastupdate-url}") String lastUpdateUrl) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)   // http -> https redirects
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.lastUpdateUrl = lastUpdateUrl;
    }

    /** URL of the newest GKG file: the line in lastupdate.txt ending in .gkg.csv.zip. */
    public Optional<String> latestGkgUrl() {
        String text = restClient.get().uri(lastUpdateUrl).retrieve().body(String.class);
        if (text == null) return Optional.empty();
        return text.lines()
                .map(line -> line.strip().split("\\s+"))
                .filter(p -> p.length >= 3 && p[2].endsWith(".gkg.csv.zip"))
                .map(p -> p[2].replaceFirst("^http://", "https://"))
                .findFirst();
    }

    /** Downloads the zip and streams each line of the file to the consumer. */
    public void readLines(String zipUrl, Consumer<String> consumer) throws IOException {
        byte[] zip = restClient.get().uri(zipUrl).retrieve().body(byte[].class);
        if (zip == null) return;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            if (zis.getNextEntry() == null) return;
            BufferedReader reader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                consumer.accept(line);
            }
        }
    }
}