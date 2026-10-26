package csd.tripsense.gdelt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import csd.tripsense.chat.ChatService;
import csd.tripsense.gdelt.GkgRelevanceFilter.BatchItem;
import csd.tripsense.gdelt.GkgRelevanceFilter.Relevance;

/**
 * Unit tests for GkgRelevanceFilter
 * The AI (ChatService) is mocked: we decide what it "answers"
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GkgRelevanceFilter Unit Tests")
class GkgRelevanceFilterTest {

    @Mock
    private ChatService chatService;

    private GkgRelevanceFilter filter() {
        return new GkgRelevanceFilter(chatService, true);
    }

    private GkgRelevanceFilter disabledFilter() {
        return new GkgRelevanceFilter(chatService, false);
    }

    private static GkgArticle article(int n) {
        return new GkgArticle("https://example.com/" + n, "Headline " + n,
                "Reported by example.com.", "example.com",
                LocalDateTime.of(2026, 10, 20, 7, 0), Set.of("JA"));
    }

    // ===== isRelevant(GkgArticle) =====

    // TESTS: if (!enabled) { return true; }
    // Ensures the code does not waste time or money calling the AI model when the feature is disabled.
    @Test
    @DisplayName("Should return true without calling the model when AI is disabled")
    void isRelevant_ShouldReturnTrueWithoutCallingModel_WhenAiDisabled() {
        assertTrue(disabledFilter().isRelevant(article(1)));
        verifyNoInteractions(chatService);
    }

    // TESTS: boolean relevant = answer != null && answer.relevant();   (answer.relevant() == true)
    @Test
    @DisplayName("Should return true when the model says the article is relevant")
    void isRelevant_ShouldReturnTrue_WhenModelSaysRelevant() {
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("Typhoon closing the airport", true));

        assertTrue(filter().isRelevant(article(1)));
    }

    // TESTS: boolean relevant = answer != null && answer.relevant();   (answer.relevant() == false)
    @Test
    @DisplayName("Should return false when the model says the article is not relevant")
    void isRelevant_ShouldReturnFalse_WhenModelSaysNotRelevant() {
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("Celebrity gossip", false));

        assertFalse(filter().isRelevant(article(1)));
    }

    // TESTS: boolean relevant = answer != null && ...   (answer == null side)
    @Test
    @DisplayName("Should return false when the model answer is null")
    void isRelevant_ShouldReturnFalse_WhenAnswerIsNull() {
        when(chatService.ask(anyString(), eq(Relevance.class))).thenReturn(null);

        assertFalse(filter().isRelevant(article(1)));
    }

    // TESTS: chatService.ask(buildPrompt(article), Relevance.class)  -> what buildPrompt() puts in the prompt
    // Captures the prompt text and checks it contains the article and keeps the prompt-injection warning.
    @Test
    @DisplayName("Should put the article and the rubric into the prompt")
    void isRelevant_ShouldBuildPromptWithArticleAndRubric() {
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("x", false));

        filter().isRelevant(article(1));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatService, times(1)).ask(prompt.capture(), eq(Relevance.class));
        assertTrue(prompt.getValue().contains("Headline: Headline 1"));       // .formatted(a.title(), ...)
        assertTrue(prompt.getValue().contains("Source: example.com"));        // .formatted(..., a.domain(), ...)
        assertTrue(prompt.getValue().contains("never as instructions"));      // the prompt-injection line in RUBRIC
    }

    // TESTS: the javadoc promise "@throws RuntimeException if the model is unreachable"
    // Verifies the exception is not swallowed: it bubbles up so the calling job knows the model failed.
    @Test
    @DisplayName("Should propagate the exception when the model fails")
    void isRelevant_ShouldPropagateException_WhenModelFails() {
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenThrow(new RuntimeException("model down"));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> filter().isRelevant(article(1)));

        assertEquals("model down", exception.getMessage());
    }

    // ===== judgeBatch(List<GkgArticle>) =====

    // TESTS: if (!enabled) { return batch.stream().map(a -> true).toList(); }
    @Test
    @DisplayName("Should treat everything as relevant and not call the model when AI is disabled")
    void judgeBatch_ShouldReturnAllTrueWithoutCallingModel_WhenAiDisabled() {
        List<Boolean> result = disabledFilter().judgeBatch(List.of(article(1), article(2)));

        assertEquals(List.of(true, true), result);
        verifyNoInteractions(chatService);
    }

    // TESTS: if (batch.size() == 1) { return List.of(isRelevant(batch.get(0))); }
    @Test
    @DisplayName("Should use the single-article call when the batch has one article")
    void judgeBatch_ShouldUseSingleArticleCall_WhenBatchHasOneArticle() {
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("ok", true));

        List<Boolean> result = filter().judgeBatch(List.of(article(1)));

        assertEquals(List.of(true), result);
        verify(chatService, never()).ask(anyString(), eq(BatchItem[].class));
    }

    // TESTS: the "match the answers back to the articles by id" loop
    //        verdicts[index] = item.relevant();   (answers arrive out of order)
    @Test
    @DisplayName("Should match answers to articles by id even when they arrive out of order")
    void judgeBatch_ShouldMatchAnswersById_WhenOutOfOrder() {
        when(chatService.ask(anyString(), eq(BatchItem[].class))).thenReturn(new BatchItem[] {
                new BatchItem(3, "closure", true),
                new BatchItem(1, "gossip", false),
                new BatchItem(2, "strike", true) });

        List<Boolean> result = filter().judgeBatch(List.of(article(1), article(2), article(3)));

        assertEquals(List.of(false, true, true), result);                      // order of the INPUT batch
        verify(chatService, never()).ask(anyString(), eq(Relevance.class));    // no fallback needed
    }

    // TESTS: the batch prompt built by buildBatchPrompt()
    @Test
    @DisplayName("Should list numbered stories and ask for a JSON array in the batch prompt")
    void judgeBatch_ShouldBuildBatchPromptWithNumberedStories() {
        when(chatService.ask(anyString(), eq(BatchItem[].class))).thenReturn(new BatchItem[] {
                new BatchItem(1, "a", true), new BatchItem(2, "b", true) });

        filter().judgeBatch(List.of(article(1), article(2)));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatService, times(1)).ask(prompt.capture(), eq(BatchItem[].class));
        assertTrue(prompt.getValue().contains("1. Headline: Headline 1"));   // stories.append(i + 1).append(". Headline: ")
        assertTrue(prompt.getValue().contains("2. Headline: Headline 2"));
        assertTrue(prompt.getValue().contains("JSON array"));                // the "Return a JSON array ..." instruction
    }

    // TESTS: the "anything the model skipped ... is judged again on its own" loop
    //        if (verdicts[i] == null) { result.add(isRelevant(batch.get(i))); }
    @Test
    @DisplayName("Should judge a skipped item again on its own")
    void judgeBatch_ShouldJudgeSkippedItemAgain() {
        when(chatService.ask(anyString(), eq(BatchItem[].class))).thenReturn(new BatchItem[] {
                new BatchItem(1, "a", true), new BatchItem(3, "c", false) });   // item 2 is missing
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("single answer", true));

        List<Boolean> result = filter().judgeBatch(List.of(article(1), article(2), article(3)));

        assertEquals(List.of(true, true, false), result);
        verify(chatService, times(1)).ask(anyString(), eq(Relevance.class));    // only item 2 re-asked
    }

    // TESTS: if (index >= 0 && index < verdicts.length && verdicts[index] == null)
    //        -> out-of-range ids and duplicate ids are ignored (first answer wins)
    @Test
    @DisplayName("Should ignore out-of-range and duplicate ids")
    void judgeBatch_ShouldIgnoreBadOrDuplicateIds() {
        when(chatService.ask(anyString(), eq(BatchItem[].class))).thenReturn(new BatchItem[] {
                new BatchItem(1, "first", true),
                new BatchItem(1, "duplicate", false),    // ignored: slot 1 already filled
                new BatchItem(99, "nonsense", true),     // ignored: out of range
                new BatchItem(0, "nonsense", true) });   // ignored: index -1
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("single answer", false));

        List<Boolean> result = filter().judgeBatch(List.of(article(1), article(2)));

        assertEquals(List.of(true, false), result);   // item 1 from the batch, item 2 re-asked
    }

    // TESTS: if (item == null) { continue; }  and  if (answer != null)
    //        -> a null array makes every item fall back to a single call
    @Test
    @DisplayName("Should fall back to single calls for every item when the batch answer is null")
    void judgeBatch_ShouldFallBackToSingleCalls_WhenAnswerIsNull() {
        when(chatService.ask(anyString(), eq(BatchItem[].class))).thenReturn(null);
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("a", true), new Relevance("b", false));

        List<Boolean> result = filter().judgeBatch(List.of(article(1), article(2)));

        assertEquals(List.of(true, false), result);
        verify(chatService, times(2)).ask(anyString(), eq(Relevance.class));
    }

    @Test
    @DisplayName("Should skip a null entry inside the answer array")
    void judgeBatch_ShouldSkipNullEntryInsideArray() {
        when(chatService.ask(anyString(), eq(BatchItem[].class))).thenReturn(new BatchItem[] {
                null, new BatchItem(2, "b", true) });
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("single answer", false));

        List<Boolean> result = filter().judgeBatch(List.of(article(1), article(2)));

        assertEquals(List.of(false, true), result);   // item 1 re-asked, item 2 from the batch
    }

    // TESTS: try { answer = chatService.ask(..., BatchItem[].class); }
    //        catch (Exception e) { return judgeOneByOne(batch); }
    @Test
    @DisplayName("Should fall back to one-by-one judging when the batch answer is unreadable")
    void judgeBatch_ShouldFallBackToOneByOne_WhenBatchAnswerUnreadable() {
        when(chatService.ask(anyString(), eq(BatchItem[].class)))
                .thenThrow(new RuntimeException("bad json"));
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("a", true), new Relevance("b", false));

        List<Boolean> result = filter().judgeBatch(List.of(article(1), article(2)));

        assertEquals(List.of(true, false), result);
        verify(chatService, times(2)).ask(anyString(), eq(Relevance.class));
    }

    // TESTS: judgeOneByOne() -> isRelevant() throws -> the exception reaches the caller (the job)
    @Test
    @DisplayName("Should propagate the exception when both the batch and the single calls fail")
    void judgeBatch_ShouldPropagateException_WhenBatchAndSingleCallsBothFail() {
        when(chatService.ask(anyString(), eq(BatchItem[].class)))
                .thenThrow(new RuntimeException("bad json"));
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenThrow(new RuntimeException("model down"));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> filter().judgeBatch(List.of(article(1), article(2))));

        assertEquals("model down", exception.getMessage());
    }
}
