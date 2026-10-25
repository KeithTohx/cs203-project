package csd.tripsense.gdelt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import csd.tripsense.country.Country;
import csd.tripsense.country.CountryService;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsIngestCompletedEvent;
import csd.tripsense.news.NewsService;

/**
 * Unit tests for GdeltIngestJob
 * Every collaborator is mocked, so there is no network, no AI and no database
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GdeltIngestJob Unit Tests")
class GdeltIngestJobTest {

    private static final String FILE_A = "https://data.gdeltproject.org/gdeltv2/20261020070000.gkg.csv.zip";
    private static final String FILE_B = "https://data.gdeltproject.org/gdeltv2/20261020071500.gkg.csv.zip";

    @Mock private GkgClient client;
    @Mock private GkgParser parser;
    @Mock private GkgRelevanceFilter relevanceFilter;
    @Mock private NewsService newsService;
    @Mock private CountryService countryService;
    @Mock private ApplicationEventPublisher events;

    @Captor private ArgumentCaptor<List<GkgArticle>> batchCaptor;   // what was sent to judgeBatch
    @Captor private ArgumentCaptor<List<News>> savedCaptor;         // what was sent to saveNewArticles

    // ---------------------------------------------------------------- helpers

    private GdeltIngestJob job(boolean enabled, int maxPerRun, int batchSize) {
        return new GdeltIngestJob(client, parser, relevanceFilter, newsService, countryService, events,
                enabled, maxPerRun, batchSize);
    }

    private static GkgArticle article(int n) {
        return new GkgArticle("https://example.com/" + n, "Headline " + n,
                "Reported by example.com.", "example.com",
                LocalDateTime.of(2026, 10, 20, 7, 0), Set.of("JA"));
    }

    private static List<String> articleUrls(List<GkgArticle> articles) {
        return articles.stream().map(GkgArticle::url).toList();
    }

    private static List<String> newsUrls(List<News> news) {
        return news.stream().map(News::getUrl).toList();
    }

    /** Makes GDELT file `url` contain the lines "line1", "line2"... and the parser turn "lineN" into article(N). */
    private void givenFile(String url, int... articleNumbers) throws IOException {
        doAnswer(inv -> {
            Consumer<String> consumer = inv.getArgument(1);
            for (int n : articleNumbers) {
                consumer.accept("line" + n);
            }
            return null;
        }).when(client).readLines(eq(url), any());
        for (int n : articleNumbers) {
            when(parser.parse("line" + n)).thenReturn(Optional.of(article(n)));
        }
    }

    /** The AI rejects everything it is asked about. */
    private void givenAiRejectsEverything() {
        when(relevanceFilter.judgeBatch(anyList()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).stream().map(a -> false).toList());
    }

    // ===== SWITCH / ENTRY POINTS =====

    // TESTS: if (!enabled) return;
    @Test
    @DisplayName("Should do nothing when the job is disabled")
    void run_ShouldDoNothing_WhenDisabled() {
        job(false, 300, 10).run();

        verifyNoInteractions(client, parser, relevanceFilter, newsService, countryService, events);
    }

    // TESTS: if (latest.isEmpty()) { log.warn("no GKG file listed..."); return; }
    @Test
    @DisplayName("Should stop early when no GKG file is listed")
    void run_ShouldStopEarly_WhenNoFileListed() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.empty());

        job(true, 300, 10).run();

        verify(client, never()).readLines(any(), any());
        verifyNoInteractions(parser, relevanceFilter, newsService, countryService, events);
    }

    // ===== BATCHING AND SAVING =====

    // TESTS: the chunking loop  for (int start = 0; start < toCheck.size(); start += batchSize)
    //        + if (verdicts.get(i)) { accepted.add(toNews(...)); }  + newsService.saveNewArticles(accepted)
    @Test
    @DisplayName("Should send articles in batches and save only the accepted ones")
    void run_ShouldSendArticlesInBatches_AndSaveOnlyTheAcceptedOnes() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A));
        givenFile(FILE_A, 1, 2, 3);
        when(relevanceFilter.judgeBatch(anyList()))
                .thenReturn(List.of(true, false),     // batch 1 = articles 1,2 -> keep 1, drop 2
                            List.of(true));           // batch 2 = article 3    -> keep 3

        job(true, 300, 2).run();                      // batchSize = 2

        verify(relevanceFilter, times(2)).judgeBatch(batchCaptor.capture());
        assertEquals(List.of(article(1).url(), article(2).url()), articleUrls(batchCaptor.getAllValues().get(0)));
        assertEquals(List.of(article(3).url()), articleUrls(batchCaptor.getAllValues().get(1)));

        verify(newsService, times(1)).saveNewArticles(savedCaptor.capture());
        assertEquals(List.of(article(1).url(), article(3).url()), newsUrls(savedCaptor.getValue()));
    }

    // TESTS: private News toNews(GkgArticle a)  (checked through what is handed to saveNewArticles)
    @Test
    @DisplayName("Should convert an accepted article into a News entity")
    void run_ShouldConvertAnAcceptedArticleIntoANewsEntity() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A));
        givenFile(FILE_A, 1);
        when(relevanceFilter.judgeBatch(anyList())).thenReturn(List.of(true));

        job(true, 300, 10).run();

        verify(newsService, times(1)).saveNewArticles(savedCaptor.capture());
        News saved = savedCaptor.getValue().get(0);
        assertNotNull(saved);
        assertEquals("Headline 1", saved.getTitle());                              // setTitle(a.title())
        assertEquals("https://example.com/1", saved.getUrl());                     // setUrl(a.url())
        assertEquals("example.com", saved.getSource());                            // setSource(a.domain())
        assertEquals("Reported by example.com.", saved.getDescription());          // setDescription(...)
        assertEquals(LocalDateTime.of(2026, 10, 20, 7, 0), saved.getPublishedAt());
    }

    // ===== READING AND FILTERING =====

    // TESTS: client.readLines(url, line -> parser.parse(line).ifPresent(a -> byUrl.putIfAbsent(a.url(), a)))
    //        -> rejected lines (Optional.empty) are ignored, repeated URLs are judged once
    @Test
    @DisplayName("Should ignore rejected lines and judge duplicate URLs only once")
    void run_ShouldIgnoreRejectedLines_AndJudgeDuplicateUrlsOnce() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A));
        doAnswer(inv -> {
            Consumer<String> consumer = inv.getArgument(1);
            consumer.accept("a");
            consumer.accept("b");
            consumer.accept("c");
            return null;
        }).when(client).readLines(eq(FILE_A), any());
        when(parser.parse("a")).thenReturn(Optional.of(article(1)));
        when(parser.parse("b")).thenReturn(Optional.of(article(1)));   // same URL as "a"
        when(parser.parse("c")).thenReturn(Optional.empty());          // dropped by the parser
        when(relevanceFilter.judgeBatch(anyList())).thenReturn(List.of(false));

        job(true, 300, 10).run();

        verify(relevanceFilter, times(1)).judgeBatch(batchCaptor.capture());
        assertEquals(List.of(article(1).url()), articleUrls(batchCaptor.getValue()));
    }

    // TESTS: Set<String> existing = newsService.findExistingUrls(...);
    //        .filter(a -> !existing.contains(a.url()) ...)
    @Test
    @DisplayName("Should skip articles that are already in the database")
    void run_ShouldSkipArticlesThatAreAlreadyInTheDatabase() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A));
        givenFile(FILE_A, 1, 2);
        when(newsService.findExistingUrls(any())).thenReturn(Set.of(article(1).url()));
        when(relevanceFilter.judgeBatch(anyList())).thenReturn(List.of(true));

        job(true, 300, 10).run();

        verify(relevanceFilter, times(1)).judgeBatch(batchCaptor.capture());
        assertEquals(List.of(article(2).url()), articleUrls(batchCaptor.getValue()));
    }

    // ===== PER-RUN CAP AND CARRY-OVER =====

    // TESTS: List<GkgArticle> toCheck = pending.size() > maxPerRun ? pending.subList(0, maxPerRun) : pending;
    //        + checkedUrls.addAll(checked) so the NEXT run only sees the leftovers (carry-over)
    //        + if (remaining == 0) lastProcessedUrl = url;  so a finished file is not read again
    @Test
    @DisplayName("Should cap articles per run, carry the leftovers over, then stop reading a finished file")
    void run_ShouldCapArticlesPerRun_CarryLeftoversOver_ThenStopReadingFinishedFile() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A));
        givenFile(FILE_A, 1, 2, 3, 4, 5);
        givenAiRejectsEverything();
        GdeltIngestJob job = job(true, 3, 10);        // maxPerRun = 3

        job.run();                                     // judges 1,2,3   (2 left over)
        job.run();                                     // judges 4,5     (nothing left -> file finished)
        job.run();                                     // same file again: returns before reading it

        verify(relevanceFilter, times(2)).judgeBatch(batchCaptor.capture());
        assertEquals(List.of(article(1).url(), article(2).url(), article(3).url()),
                articleUrls(batchCaptor.getAllValues().get(0)));
        assertEquals(List.of(article(4).url(), article(5).url()),
                articleUrls(batchCaptor.getAllValues().get(1)));
        verify(client, times(2)).readLines(eq(FILE_A), any());   // run 3 did not read the file
    }

    // TESTS: catch (Exception e) { log.warn("AI filter failed, stopping this run"); aborted = true; break; }
    //        -> batches judged before the failure are still saved, the failed batch is retried next run
    @Test
    @DisplayName("Should save earlier batches on AI failure and retry only the failed ones next run")
    void run_ShouldSaveEarlierBatches_AndRetryOnlyTheFailedOnesNextRun_WhenAiFails() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A));
        givenFile(FILE_A, 1, 2);
        when(relevanceFilter.judgeBatch(anyList()))
                .thenReturn(List.of(true))                         // run 1, batch [1] ok
                .thenThrow(new RuntimeException("model down"))     // run 1, batch [2] fails -> abort
                .thenReturn(List.of(true));                        // run 2, batch [2] ok
        GdeltIngestJob job = job(true, 300, 1);                    // batchSize = 1

        job.run();
        job.run();

        verify(relevanceFilter, times(3)).judgeBatch(batchCaptor.capture());
        assertEquals(List.of(article(2).url()), articleUrls(batchCaptor.getAllValues().get(2)));   // article 1 was NOT re-judged

        verify(newsService, times(2)).saveNewArticles(savedCaptor.capture());
        assertEquals(List.of(article(1).url()), newsUrls(savedCaptor.getAllValues().get(0)));      // saved despite the abort
        assertEquals(List.of(article(2).url()), newsUrls(savedCaptor.getAllValues().get(1)));
    }

    // TESTS: if (!url.equals(currentFile)) { checkedUrls.clear(); currentFile = url; }
    //        -> a newer GDELT file starts fresh; leftovers of the old file are abandoned
    @Test
    @DisplayName("Should forget the old file's leftovers when a newer file appears")
    void run_ShouldForgetTheOldFilesLeftovers_WhenNewFileAppears() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A), Optional.of(FILE_B));
        givenFile(FILE_A, 1, 2);
        givenFile(FILE_B, 1);
        givenAiRejectsEverything();
        GdeltIngestJob job = job(true, 1, 10);         // maxPerRun = 1: article 2 is left over

        job.run();                                      // file A: judges article 1 only
        job.run();                                      // file B: article 1 is judged again

        verify(relevanceFilter, times(2)).judgeBatch(batchCaptor.capture());
        for (List<GkgArticle> batch : batchCaptor.getAllValues()) {
            assertEquals(List.of(article(1).url()), articleUrls(batch));
        }
        // article 2 (left over from file A) is never judged: its file was replaced
    }

    // ===== COUNTRY LINKING =====

    // TESTS: toNews(a, countriesByCode) -> the article's FIPS codes become Country links
    @Test
    @DisplayName("Should link accepted news to the matching countries")
    void run_ShouldLinkAcceptedNewsToTheMatchingCountries() throws Exception {
        Country japan = new Country();
        japan.setId(1L);
        japan.setName("Japan");
        japan.setFipsCode("JA");
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A));
        givenFile(FILE_A, 1);                                   // article(1) has codes {"JA"}
        when(countryService.getCountriesByFipsCodes(any())).thenReturn(List.of(japan));
        when(relevanceFilter.judgeBatch(anyList())).thenReturn(List.of(true));

        job(true, 300, 10).run();

        verify(newsService, times(1)).saveNewArticles(savedCaptor.capture());
        Set<Country> countries = savedCaptor.getValue().get(0).getCountries();
        assertNotNull(countries);
        assertEquals(1, countries.size());
        assertTrue(countries.contains(japan));
    }

    // TESTS: a code with no Country row is skipped, but the article is still saved
    @Test
    @DisplayName("Should still save the news with no countries when a code has no Country row")
    void run_ShouldSaveNewsWithNoCountries_WhenCodeHasNoCountryRow() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A));
        givenFile(FILE_A, 1);
        when(countryService.getCountriesByFipsCodes(any())).thenReturn(List.of());
        when(relevanceFilter.judgeBatch(anyList())).thenReturn(List.of(true));

        job(true, 300, 10).run();

        verify(newsService, times(1)).saveNewArticles(savedCaptor.capture());
        assertEquals(1, savedCaptor.getValue().size());
        assertTrue(savedCaptor.getValue().get(0).getCountries().isEmpty());
    }

    // ===== INGEST-COMPLETED EVENT =====

    // TESTS: a fully judged file publishes the event that starts impact evaluation
    @Test
    @DisplayName("Should publish the ingest-completed event when a file is fully judged")
    void run_ShouldPublishIngestCompletedEvent_WhenFileFullyJudged() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A));
        givenFile(FILE_A, 1);
        givenAiRejectsEverything();

        job(true, 300, 10).run();

        verify(events, times(1)).publishEvent(new NewsIngestCompletedEvent(FILE_A));
    }

    // TESTS: leftovers (cap hit) mean the file is not finished, so no event yet
    @Test
    @DisplayName("Should not publish the event when leftovers remain and nothing was saved")
    void run_ShouldNotPublishEvent_WhenLeftoversRemain() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A));
        givenFile(FILE_A, 1, 2, 3);
        givenAiRejectsEverything();

        job(true, 2, 10).run();                        // maxPerRun = 2, article 3 is left over

        verify(events, never()).publishEvent(any(Object.class));
    }

    // TESTS: news saved while leftovers remain still triggers impact evaluation
    @Test
    @DisplayName("Should publish the event when news was saved even though leftovers remain")
    void run_ShouldPublishIngestCompletedEvent_WhenNewsSavedWithLeftovers() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A));
        givenFile(FILE_A, 1, 2, 3);
        when(relevanceFilter.judgeBatch(anyList())).thenReturn(List.of(true, false));
        when(newsService.saveNewArticles(anyList())).thenReturn(1);

        job(true, 2, 10).run();                        // article 3 is left over, article 1 is saved

        verify(events, times(1)).publishEvent(new NewsIngestCompletedEvent(FILE_A));
    }
}
