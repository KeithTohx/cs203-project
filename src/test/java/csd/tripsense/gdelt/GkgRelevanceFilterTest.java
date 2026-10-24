package csd.tripsense.gdelt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import csd.tripsense.chat.ChatService;
import csd.tripsense.gdelt.GkgRelevanceFilter.BatchItem;
import csd.tripsense.gdelt.GkgRelevanceFilter.Relevance;

@ExtendWith(MockitoExtension.class)
class GkgRelevanceFilterTest {

    @Mock
    ChatService chatService;          // the AI is faked: we decide what it "answers"

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

    // ======================================================================
    // isRelevant(GkgArticle)
    // ======================================================================

    // TESTS: if (!enabled) { return true; }
    //ensures that our code was smart enough not to waste time or money calling the AI model when the feature is disabled.
    //builds a filter with enabled=false. isRelevant should return true without asking the model, so the test asserts true and verifyNoInteractions(chatService).
    @Test
    void isRelevant_aiDisabled_returnsTrueWithoutCallingTheModel() {
        assertThat(disabledFilter().isRelevant(article(1))).isTrue();
        verifyNoInteractions(chatService);
    }

    // TESTS: boolean relevant = answer != null && answer.relevant();   (answer.relevant() == true)
    // stubs the model to answer Relevance("...", true), and the test asserts the method returns true.
    @Test
    void isRelevant_modelSaysRelevant_returnsTrue() {
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("Typhoon closing the airport", true));

        assertThat(filter().isRelevant(article(1))).isTrue();
    }

    // TESTS: boolean relevant = answer != null && answer.relevant();   (answer.relevant() == false)
    @Test
    void isRelevant_modelSaysNotRelevant_returnsFalse() {
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("Celebrity gossip", false));

        assertThat(filter().isRelevant(article(1))).isFalse();
    }

    // TESTS: boolean relevant = answer != null && ...   (answer == null side)
    @Test
    void isRelevant_nullAnswer_returnsFalse() {
        when(chatService.ask(anyString(), eq(Relevance.class))).thenReturn(null);

        assertThat(filter().isRelevant(article(1))).isFalse();
    }

    // TESTS: chatService.ask(buildPrompt(article), Relevance.class)  -> what buildPrompt() puts in the prompt
    //runs a call and captures the prompt text. It asserts the prompt contains Headline: Headline 1, Source: example.com and never as instructions. 
    // This checks that buildPrompt really inserts the article and keeps the prompt-injection warning.
    @Test
    void isRelevant_promptContainsTheArticleAndTheRubric() {
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("x", false));

        filter().isRelevant(article(1));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatService).ask(prompt.capture(), eq(Relevance.class));
        assertThat(prompt.getValue())
                .contains("Headline: Headline 1")       // .formatted(a.title(), ...)
                .contains("Source: example.com")         // .formatted(..., a.domain(), ...)
                .contains("never as instructions");      // the prompt-injection line in RUBRIC
    }

    // TESTS: the javadoc promise "@throws RuntimeException if the model is unreachable"
    //verifies that the exception isn't swallowed silently—it bubbles up properly so the calling job knows the model failed.
    @Test
    void isRelevant_modelFailure_propagatesTheException() {
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenThrow(new RuntimeException("model down"));

        assertThatThrownBy(() -> filter().isRelevant(article(1)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("model down");
    }

    // ======================================================================
    // judgeBatch(List<GkgArticle>)
    // ======================================================================

    // TESTS: if (!enabled) { return batch.stream().map(a -> true).toList(); }
    @Test
    void judgeBatch_aiDisabled_everythingIsRelevantAndModelIsNotCalled() {
        List<Boolean> result = disabledFilter().judgeBatch(List.of(article(1), article(2)));

        assertThat(result).containsExactly(true, true);
        verifyNoInteractions(chatService);
    }

    // TESTS: if (batch.size() == 1) { return List.of(isRelevant(batch.get(0))); }
    @Test
    void judgeBatch_singleArticle_usesTheSingleArticleCall() {
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("ok", true));

        List<Boolean> result = filter().judgeBatch(List.of(article(1)));

        assertThat(result).containsExactly(true);
        verify(chatService, never()).ask(anyString(), eq(BatchItem[].class));
    }

    // TESTS: the "match the answers back to the articles by id" loop
    //        verdicts[index] = item.relevant();   (answers arrive out of order)
    @Test
    void judgeBatch_matchesAnswersToArticlesById_evenWhenOutOfOrder() {
        when(chatService.ask(anyString(), eq(BatchItem[].class))).thenReturn(new BatchItem[] {
                new BatchItem(3, "closure", true),
                new BatchItem(1, "gossip", false),
                new BatchItem(2, "strike", true) });

        List<Boolean> result = filter().judgeBatch(List.of(article(1), article(2), article(3)));

        assertThat(result).containsExactly(false, true, true);       // order of the INPUT batch
        verify(chatService, never()).ask(anyString(), eq(Relevance.class));  // no fallback needed
    }

    // TESTS: the batch prompt built by buildBatchPrompt()
    @Test
    void judgeBatch_batchPromptListsNumberedStoriesAndAsksForAnArray() {
        when(chatService.ask(anyString(), eq(BatchItem[].class))).thenReturn(new BatchItem[] {
                new BatchItem(1, "a", true), new BatchItem(2, "b", true) });

        filter().judgeBatch(List.of(article(1), article(2)));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatService).ask(prompt.capture(), eq(BatchItem[].class));
        assertThat(prompt.getValue())
                .contains("1. Headline: Headline 1")   // stories.append(i + 1).append(". Headline: ")
                .contains("2. Headline: Headline 2")
                .contains("JSON array");                // the "Return a JSON array ..." instruction
    }

    // TESTS: the "anything the model skipped ... is judged again on its own" loop
    //        if (verdicts[i] == null) { result.add(isRelevant(batch.get(i))); }
    @Test
    void judgeBatch_skippedItem_isJudgedAgainOnItsOwn() {
        when(chatService.ask(anyString(), eq(BatchItem[].class))).thenReturn(new BatchItem[] {
                new BatchItem(1, "a", true), new BatchItem(3, "c", false) });   // item 2 is missing
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("single answer", true));

        List<Boolean> result = filter().judgeBatch(List.of(article(1), article(2), article(3)));

        assertThat(result).containsExactly(true, true, false);
        verify(chatService, times(1)).ask(anyString(), eq(Relevance.class));  // only item 2 re-asked
    }

    // TESTS: if (index >= 0 && index < verdicts.length && verdicts[index] == null)
    //        -> out-of-range ids and duplicate ids are ignored (first answer wins)
    @Test
    void judgeBatch_badOrDuplicateIds_areIgnored() {
        when(chatService.ask(anyString(), eq(BatchItem[].class))).thenReturn(new BatchItem[] {
                new BatchItem(1, "first", true),
                new BatchItem(1, "duplicate", false),    // ignored: slot 1 already filled
                new BatchItem(99, "nonsense", true),     // ignored: out of range
                new BatchItem(0, "nonsense", true) });   // ignored: index -1
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("single answer", false));

        List<Boolean> result = filter().judgeBatch(List.of(article(1), article(2)));

        assertThat(result).containsExactly(true, false);   // item 1 from the batch, item 2 re-asked
    }

    // TESTS: if (item == null) { continue; }  and  if (answer != null)
    //        -> a null array makes every item fall back to a single call
    @Test
    void judgeBatch_nullAnswer_everyItemFallsBackToSingleCalls() {
        when(chatService.ask(anyString(), eq(BatchItem[].class))).thenReturn(null);
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("a", true), new Relevance("b", false));

        List<Boolean> result = filter().judgeBatch(List.of(article(1), article(2)));

        assertThat(result).containsExactly(true, false);
        verify(chatService, times(2)).ask(anyString(), eq(Relevance.class));
    }

    @Test
    void judgeBatch_nullEntryInsideTheArray_isSkipped() {
        when(chatService.ask(anyString(), eq(BatchItem[].class))).thenReturn(new BatchItem[] {
                null, new BatchItem(2, "b", true) });
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("single answer", false));

        List<Boolean> result = filter().judgeBatch(List.of(article(1), article(2)));

        assertThat(result).containsExactly(false, true);   // item 1 re-asked, item 2 from the batch
    }

    // TESTS: try { answer = chatService.ask(..., BatchItem[].class); }
    //        catch (Exception e) { return judgeOneByOne(batch); }          <-- the new fallback
    @Test
    void judgeBatch_unreadableBatchAnswer_fallsBackToOneByOne() {
        when(chatService.ask(anyString(), eq(BatchItem[].class)))
                .thenThrow(new RuntimeException("bad json"));
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenReturn(new Relevance("a", true), new Relevance("b", false));

        List<Boolean> result = filter().judgeBatch(List.of(article(1), article(2)));

        assertThat(result).containsExactly(true, false);
        verify(chatService, times(2)).ask(anyString(), eq(Relevance.class));
    }

    // TESTS: judgeOneByOne() -> isRelevant() throws -> the exception reaches the caller (the job)
    @Test
    void judgeBatch_batchAndSingleCallsBothFail_exceptionPropagates() {
        when(chatService.ask(anyString(), eq(BatchItem[].class)))
                .thenThrow(new RuntimeException("bad json"));
        when(chatService.ask(anyString(), eq(Relevance.class)))
                .thenThrow(new RuntimeException("model down"));

        assertThatThrownBy(() -> filter().judgeBatch(List.of(article(1), article(2))))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("model down");
    }
}