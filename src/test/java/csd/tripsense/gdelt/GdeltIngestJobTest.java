package csd.tripsense.gdelt;

import static org.assertj.core.api.Assertions.assertThat;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import csd.tripsense.news.News;
import csd.tripsense.news.NewsService;

@ExtendWith(MockitoExtension.class)
class GdeltIngestJobTest {

    private static final String FILE_A = "https://data.gdeltproject.org/gdeltv2/20261020070000.gkg.csv.zip";
    private static final String FILE_B = "https://data.gdeltproject.org/gdeltv2/20261020071500.gkg.csv.zip";

    // every collaborator is faked, so no network, no AI, no database
    @Mock GkgClient client;
    @Mock GkgParser parser;
    @Mock GkgRelevanceFilter relevanceFilter;
    @Mock NewsService newsService;

    @Captor ArgumentCaptor<List<GkgArticle>> batchCaptor;   // what was sent to judgeBatch
    @Captor ArgumentCaptor<List<News>> savedCaptor;         // what was sent to saveNewArticles

    // ---------------------------------------------------------------- helpers

    private GdeltIngestJob job(boolean enabled, int maxPerRun, int batchSize) {
        return new GdeltIngestJob(client, parser, relevanceFilter, newsService,
                enabled, maxPerRun, batchSize);
    }

    private static GkgArticle article(int n) {
        return new GkgArticle("https://example.com/" + n, "Headline " + n,
                "Reported by example.com.", "example.com",
                LocalDateTime.of(2026, 10, 20, 7, 0), Set.of("JA"));
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

    // ================================================================ run()

    // TESTS: if (!enabled) return;
    @Test
    void run_disabled_doesNothing() {
        job(false, 300, 10).run();

        verifyNoInteractions(client, parser, relevanceFilter, newsService);
    }

    // TESTS: if (latest.isEmpty()) { log.warn("no GKG file listed..."); return; }
    @Test
    void run_noFileListed_stopsEarly() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.empty());

        job(true, 300, 10).run();

        verify(client, never()).readLines(any(), any());
        verifyNoInteractions(parser, relevanceFilter, newsService);
    }

    // TESTS: the chunking loop  for (int start = 0; start < toCheck.size(); start += batchSize)
    //        + if (verdicts.get(i)) { accepted.add(toNews(...)); }  + newsService.saveNewArticles(accepted)
    @Test
    void run_sendsArticlesInBatches_andSavesOnlyTheAcceptedOnes() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A));
        givenFile(FILE_A, 1, 2, 3);
        when(relevanceFilter.judgeBatch(anyList()))
                .thenReturn(List.of(true, false),     // batch 1 = articles 1,2 -> keep 1, drop 2
                            List.of(true));           // batch 2 = article 3    -> keep 3

        job(true, 300, 2).run();                      // batchSize = 2

        verify(relevanceFilter, times(2)).judgeBatch(batchCaptor.capture());
        assertThat(batchCaptor.getAllValues().get(0)).extracting(GkgArticle::url)
                .containsExactly(article(1).url(), article(2).url());
        assertThat(batchCaptor.getAllValues().get(1)).extracting(GkgArticle::url)
                .containsExactly(article(3).url());

        verify(newsService).saveNewArticles(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).extracting(News::getUrl)
                .containsExactly(article(1).url(), article(3).url());
    }

    // TESTS: private News toNews(GkgArticle a)  (checked through what is handed to saveNewArticles)
    @Test
    void run_convertsAnAcceptedArticleIntoANewsEntity() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A));
        givenFile(FILE_A, 1);
        when(relevanceFilter.judgeBatch(anyList())).thenReturn(List.of(true));

        job(true, 300, 10).run();

        verify(newsService).saveNewArticles(savedCaptor.capture());
        News saved = savedCaptor.getValue().get(0);
        assertThat(saved.getTitle()).isEqualTo("Headline 1");                         // setTitle(a.title())
        assertThat(saved.getUrl()).isEqualTo("https://example.com/1");                // setUrl(a.url())
        assertThat(saved.getSource()).isEqualTo("example.com");                       // setSource(a.domain())
        assertThat(saved.getDescription()).isEqualTo("Reported by example.com.");     // setDescription(...)
        assertThat(saved.getPublishedAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 7, 0));
    }

    // TESTS: client.readLines(url, line -> parser.parse(line).ifPresent(a -> byUrl.putIfAbsent(a.url(), a)))
    //        -> rejected lines (Optional.empty) are ignored, repeated URLs are judged once
    @Test
    void run_ignoresRejectedLines_andJudgesDuplicateUrlsOnce() throws Exception {
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

        verify(relevanceFilter).judgeBatch(batchCaptor.capture());
        assertThat(batchCaptor.getValue()).extracting(GkgArticle::url)
                .containsExactly(article(1).url());
    }

    // TESTS: Set<String> existing = newsService.findExistingUrls(...);
    //        .filter(a -> !existing.contains(a.url()) ...)
    @Test
    void run_skipsArticlesThatAreAlreadyInTheDatabase() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A));
        givenFile(FILE_A, 1, 2);
        when(newsService.findExistingUrls(any())).thenReturn(Set.of(article(1).url()));
        when(relevanceFilter.judgeBatch(anyList())).thenReturn(List.of(true));

        job(true, 300, 10).run();

        verify(relevanceFilter).judgeBatch(batchCaptor.capture());
        assertThat(batchCaptor.getValue()).extracting(GkgArticle::url)
                .containsExactly(article(2).url());
    }

    // TESTS: List<GkgArticle> toCheck = pending.size() > maxPerRun ? pending.subList(0, maxPerRun) : pending;
    //        + checkedUrls.addAll(checked) so the NEXT run only sees the leftovers (carry-over)
    //        + if (remaining == 0) lastProcessedUrl = url;  so a finished file is not read again
    @Test
    void run_capsArticlesPerRun_carriesTheLeftoversOver_thenStopsReadingAFinishedFile() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A));
        givenFile(FILE_A, 1, 2, 3, 4, 5);
        givenAiRejectsEverything();
        GdeltIngestJob job = job(true, 3, 10);        // maxPerRun = 3

        job.run();                                     // judges 1,2,3   (2 left over)
        job.run();                                     // judges 4,5     (nothing left -> file finished)
        job.run();                                     // same file again: returns before reading it

        verify(relevanceFilter, times(2)).judgeBatch(batchCaptor.capture());
        assertThat(batchCaptor.getAllValues().get(0)).extracting(GkgArticle::url)
                .containsExactly(article(1).url(), article(2).url(), article(3).url());
        assertThat(batchCaptor.getAllValues().get(1)).extracting(GkgArticle::url)
                .containsExactly(article(4).url(), article(5).url());
        verify(client, times(2)).readLines(eq(FILE_A), any());   // run 3 did not read the file
    }

    // TESTS: catch (Exception e) { log.warn("AI filter failed, stopping this run"); aborted = true; break; }
    //        -> batches judged before the failure are still saved, the failed batch is retried next run
    @Test
    void run_aiFailure_savesEarlierBatches_andRetriesOnlyTheFailedOnesNextRun() throws Exception {
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
        assertThat(batchCaptor.getAllValues().get(2)).extracting(GkgArticle::url)
                .containsExactly(article(2).url());                // article 1 was NOT re-judged

        verify(newsService, times(2)).saveNewArticles(savedCaptor.capture());
        assertThat(savedCaptor.getAllValues().get(0)).extracting(News::getUrl)
                .containsExactly(article(1).url());                // saved despite the abort
        assertThat(savedCaptor.getAllValues().get(1)).extracting(News::getUrl)
                .containsExactly(article(2).url());
    }

    // TESTS: if (!url.equals(currentFile)) { checkedUrls.clear(); currentFile = url; }
    //        -> a newer GDELT file starts fresh; leftovers of the old file are abandoned
    @Test
    void run_newFile_forgetsTheOldFilesLeftovers() throws Exception {
        when(client.latestGkgUrl()).thenReturn(Optional.of(FILE_A), Optional.of(FILE_B));
        givenFile(FILE_A, 1, 2);
        givenFile(FILE_B, 1);
        givenAiRejectsEverything();
        GdeltIngestJob job = job(true, 1, 10);         // maxPerRun = 1: article 2 is left over

        job.run();                                      // file A: judges article 1 only
        job.run();                                      // file B: article 1 is judged again

        verify(relevanceFilter, times(2)).judgeBatch(batchCaptor.capture());
        assertThat(batchCaptor.getAllValues()).allSatisfy(batch ->
                assertThat(batch).extracting(GkgArticle::url).containsExactly(article(1).url()));
        // article 2 (left over from file A) is never judged: its file was replaced
    }
}