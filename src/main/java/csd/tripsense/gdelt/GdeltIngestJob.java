package csd.tripsense.gdelt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;

import csd.tripsense.news.NewsIngestCompletedEvent;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsService;
import csd.tripsense.country.Country;
import csd.tripsense.country.CountryService;

/** Polls GDELT for the newest 15-minute GKG file and stores articles the AI judges relevant. */
@Component
public class GdeltIngestJob {

    private static final Logger log = LoggerFactory.getLogger(GdeltIngestJob.class);

    private final GkgClient client;
    private final GkgParser parser;
    private final GkgRelevanceFilter relevanceFilter;
    private final NewsService newsService;
    private final boolean enabled;
    private final int maxPerRun;
    private volatile String lastProcessedUrl;
    private final Set<String> checkedUrls = ConcurrentHashMap.newKeySet();   // already judged (accepted or rejected) for currentFile
    private volatile String currentFile;
    private final int batchSize;
    private final CountryService countryService;
    private final ApplicationEventPublisher events;

    public GdeltIngestJob(GkgClient client, GkgParser parser, GkgRelevanceFilter relevanceFilter,
                        NewsService newsService, CountryService countryService,
                        ApplicationEventPublisher events,
                        @Value("${gdelt.ingest.enabled}") boolean enabled,
                        @Value("${gdelt.ai.max-per-run:60}") int maxPerRun,
                        @Value("${gdelt.ai.batch-size:10}") int batchSize) {
        this.client = client;
        this.parser = parser;
        this.relevanceFilter = relevanceFilter;
        this.newsService = newsService;
        this.countryService = countryService;
        this.enabled = enabled;
        this.maxPerRun = maxPerRun;
        this.batchSize = Math.max(1, batchSize);
        this.events = events;
    }

    @Scheduled(fixedRateString = "${gdelt.ingest.interval-ms}",
            initialDelayString = "${gdelt.ingest.initial-delay-ms}")
    public void run() {
        log.info("GDELT ingest run started (enabled={})", enabled);
        if (!enabled) return;

        try {
            Optional<String> latest = client.latestGkgUrl();
            if (latest.isEmpty()) {
                log.warn("GDELT: no GKG file listed in lastupdate.txt");
                return;
            }
            String url = latest.get();
            if (url.equals(lastProcessedUrl)) {
                log.info("GDELT: no new file yet (latest is still {})", url);
                return;
            }

            // a new file means a fresh list of already-checked articles
            if (!url.equals(currentFile)) {
                checkedUrls.clear();
                currentFile = url;
            }

            // 1. cheap rules: one entry per URL
            Map<String, GkgArticle> byUrl = new LinkedHashMap<>();
            int[] rows = {0};
            client.readLines(url, line -> {
                rows[0]++;
                parser.parse(line).ifPresent(a -> byUrl.putIfAbsent(a.url(), a));
            });

            // 2. skip articles already stored, or already judged in an earlier run on this file
            Set<String> existing = newsService.findExistingUrls(byUrl.keySet());
            List<GkgArticle> pending = byUrl.values().stream()
                    .filter(a -> !existing.contains(a.url()) && !checkedUrls.contains(a.url()))
                    .toList();

            // 3. this run's share
            List<GkgArticle> toCheck = pending.size() > maxPerRun ? pending.subList(0, maxPerRun) : pending;

            // 3b. one small query: GDELT country codes -> our Country rows
            Map<String, Country> countriesByCode = countriesFor(toCheck);

            // 4. ask the model in batches; if a call fails, stop and continue from there next poll
            List<News> accepted = new ArrayList<>();
            List<String> checked = new ArrayList<>();
            for (int start = 0; start < toCheck.size(); start += batchSize) {
                List<GkgArticle> batch = toCheck.subList(start, Math.min(start + batchSize, toCheck.size()));
                try {
                    List<Boolean> verdicts = relevanceFilter.judgeBatch(batch);
                    for (int i = 0; i < batch.size(); i++) {
                        if (verdicts.get(i)) {
                            accepted.add(toNews(batch.get(i), countriesByCode));
                        }
                        checked.add(batch.get(i).url());
                    }
                } catch (Exception e) {
                    log.warn("GDELT: AI filter failed, stopping this run: {}", e.getMessage());
                    break;
                }
            }

            int saved = newsService.saveNewArticles(accepted);
            checkedUrls.addAll(checked);            // only after the save worked

            int remaining = pending.size() - checked.size();
            boolean fileDone = remaining == 0;
            if (fileDone) {
                lastProcessedUrl = url;             // everything judged, this file is done
            }
            log.info("GDELT: scanned {} rows, {} candidates, {} pending, checked {}, accepted {}, saved {}, {} left for next run ({})",
                    rows[0], byUrl.size(), pending.size(), checked.size(), accepted.size(), saved, remaining, url);

            if (saved > 0 || fileDone) {
                events.publishEvent(new NewsIngestCompletedEvent(url));   // triggers impact evaluation
            }
        } catch (Exception e) {
            log.warn("GDELT ingest failed: {}", e.getMessage());
        }
    }
    
        private Map<String, Country> countriesFor(List<GkgArticle> articles) {
            Set<String> codes = articles.stream()
                    .flatMap(a -> a.countryCodes().stream())
                    .collect(Collectors.toSet());
            if (codes.isEmpty()) return Map.of();
            return countryService.getCountriesByFipsCodes(codes).stream()
                    .collect(Collectors.toMap(Country::getFipsCode, c -> c, (a, b) -> a));
        }

    private News toNews(GkgArticle a, Map<String, Country> countriesByCode) {
        News n = new News();
        n.setTitle(a.title());
        n.setUrl(a.url());
        n.setSource(a.domain());
        n.setPublishedAt(a.publishedAt());   // batch time, UTC
        n.setDescription(a.description());

        Set<Country> countries = new HashSet<>();
        for (String code : a.countryCodes()) {
            Country c = countriesByCode.get(code);
            if (c != null) countries.add(c);   // codes we have no Country row for are skipped
        }
        n.setCountries(countries);
        return n;
    }
}