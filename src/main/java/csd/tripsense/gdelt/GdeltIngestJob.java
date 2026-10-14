package csd.tripsense.gdelt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import csd.tripsense.news.News;
import csd.tripsense.news.NewsService;

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

    public GdeltIngestJob(GkgClient client, GkgParser parser, GkgRelevanceFilter relevanceFilter,
                          NewsService newsService,
                          @Value("${gdelt.ingest.enabled}") boolean enabled,
                          @Value("${gdelt.ai.max-per-run:60}") int maxPerRun) {
        this.client = client;
        this.parser = parser;
        this.relevanceFilter = relevanceFilter;
        this.newsService = newsService;
        this.enabled = enabled;
        this.maxPerRun = maxPerRun;
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

            // 4. ask the model; if it fails, stop and continue from here next poll
            List<News> accepted = new ArrayList<>();
            List<String> checked = new ArrayList<>();
            boolean aborted = false;
            for (GkgArticle article : toCheck) {
                try {
                    if (relevanceFilter.isRelevant(article)) {
                        accepted.add(toNews(article));
                    }
                    checked.add(article.url());
                } catch (Exception e) {
                    log.warn("GDELT: AI filter failed, stopping this run: {}", e.getMessage());
                    aborted = true;
                    break;
                }
            }

            int saved = newsService.saveNewArticles(accepted);
            checkedUrls.addAll(checked);            // only after the save worked

            int remaining = pending.size() - checked.size();
            if (remaining == 0) {
                lastProcessedUrl = url;             // everything judged, this file is done
            }
            log.info("GDELT: scanned {} rows, {} candidates, {} pending, checked {}, accepted {}, saved {}, {} left for next run ({})",
                    rows[0], byUrl.size(), pending.size(), checked.size(), accepted.size(), saved, remaining, url);
        } catch (Exception e) {
            log.warn("GDELT ingest failed: {}", e.getMessage());
        }
    }

    private News toNews(GkgArticle a) {
        News n = new News();
        n.setTitle(a.title());
        n.setUrl(a.url());
        n.setSource(a.domain());
        n.setPublishedAt(a.publishedAt());   // batch time, UTC
        n.setDescription(a.description());
        return n;
    }
}