package csd.tripsense.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

// unit tests for ChatServiceImpl, the one class that talks to the model
// ChatClient is used as a chain (prompt().user().call().content()), so it is mocked with deep stubs,
// which lets one when(...) set up the whole chain. no model is ever called
@DisplayName("ChatService Unit Tests")
class ChatServiceImplTest {

    private ChatClient chatClient;
    private ChatServiceImpl chatService;

    // the shape a caller might ask the model to answer in
    record Verdict(boolean impacted, String reason) {
    }

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        // 0 turns the rate limiter off, so the tests do not wait
        chatService = new ChatServiceImpl(builder, 0, "");
    }

    // ===== FALLING BACK TO ANOTHER MODEL =====

    private ChatServiceImpl withModels(String models) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        return new ChatServiceImpl(builder, 0, models);
    }

    // with a model list the service names the model, so the stub has to go through options()
    private org.mockito.stubbing.OngoingStubbing<String> whenAskedWithAModel(String prompt) {
        return when(chatClient.prompt().user(prompt).options(any(ChatOptions.Builder.class))
                .call().content());
    }

    @Test
    @DisplayName("Should ask the next model when the first has used up its budget")
    void ask_ShouldFallBack_WhenFirstModelIsOutOfBudget() {
        ChatServiceImpl service = withModels("big-model,small-model");
        whenAskedWithAModel("Is Niseko open?")
                .thenThrow(new RuntimeException("429: Rate limit reached on tokens per day (TPD)"))
                .thenReturn("Yes, the lifts are running.");

        String answer = service.ask("Is Niseko open?");

        assertEquals("Yes, the lifts are running.", answer);
    }

    @Test
    @DisplayName("Should say so when every model has used up its budget")
    void ask_ShouldReportFailure_WhenEveryModelIsOutOfBudget() {
        ChatServiceImpl service = withModels("big-model,small-model");
        whenAskedWithAModel("Is Niseko open?")
                .thenThrow(new RuntimeException("429: Rate limit reached on tokens per day (TPD)"));

        String answer = service.ask("Is Niseko open?");

        assertTrue(answer.startsWith("Could not reach the model"));
    }

    @Test
    @DisplayName("Should only rest a model for the day when groq says the daily limit is gone")
    void restFor_ShouldBeShort_ForAPerMinuteLimit() {
        var perMinute = new RuntimeException("429: Rate limit reached on tokens per minute (TPM)");
        var perDay = new RuntimeException("429: Rate limit reached on tokens per day (TPD)");

        assertEquals(java.time.Duration.ofMinutes(1), ChatServiceImpl.restFor(perMinute));
        assertEquals(java.time.Duration.ofMinutes(30), ChatServiceImpl.restFor(perDay));
    }

    @Test
    @DisplayName("Should not try another model when the failure is not about budget")
    void ask_ShouldNotFallBack_WhenTheFailureIsSomethingElse() {
        ChatServiceImpl service = withModels("big-model,small-model");
        whenAskedWithAModel("Is Niseko open?")
                .thenThrow(new RuntimeException("connection refused"))
                .thenReturn("should never be reached");

        String answer = service.ask("Is Niseko open?");

        // a broken connection is not fixed by asking a different model
        assertTrue(answer.contains("connection refused"));
    }

    // ===== PLAIN TEXT ANSWERS =====

    @Test
    @DisplayName("Should send the prompt and return the model's text")
    void ask_ShouldReturnModelText() {
        // Arrange
        when(chatClient.prompt().user("Is Niseko open?").call().content()).thenReturn("Yes, the lifts are running.");

        // Act
        String answer = chatService.ask("Is Niseko open?");

        // Assert
        assertEquals("Yes, the lifts are running.", answer);
    }

    @Test
    @DisplayName("Should answer with a message instead of throwing when the model is unreachable")
    void ask_ShouldReturnMessage_WhenModelFails() {
        // Arrange
        when(chatClient.prompt().user("Is Niseko open?").call().content())
                .thenThrow(new RuntimeException("Connection refused"));

        // Act
        String answer = chatService.ask("Is Niseko open?");

        // Assert
        assertEquals("Could not reach the model: Connection refused", answer);
    }

    // ===== STRUCTURED ANSWERS =====

    @Test
    @DisplayName("Should return the model's answer in the requested shape")
    void askForType_ShouldReturnEntity() {
        // Arrange
        Verdict expected = new Verdict(true, "Lifts closed after the earthquake");
        when(chatClient.prompt().user("Judge this").call().entity(Verdict.class)).thenReturn(expected);

        // Act
        Verdict actual = chatService.ask("Judge this", Verdict.class);

        // Assert
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Should let the error through when the model fails, so the caller can fall back")
    void askForType_ShouldThrow_WhenModelFails() {
        // Arrange
        when(chatClient.prompt().user("Judge this").call().entity(Verdict.class))
                .thenThrow(new RuntimeException("Connection refused"));

        // Act + Assert
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> chatService.ask("Judge this", Verdict.class));
        assertEquals("Connection refused", error.getMessage());
    }

    @Test
    @DisplayName("Should let calls straight through when the rate limit is off")
    void ask_ShouldNotWait_WhenRateLimitIsZero() {
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn("ok");
        ChatServiceImpl noLimit = new ChatServiceImpl(builderFor(chatClient), 0, "");

        long start = System.currentTimeMillis();
        for (int call = 0; call < 20; call++) {
            noLimit.ask("anything");
        }

        assertTrue(System.currentTimeMillis() - start < 1000, "no limit should mean no waiting");
    }

    @Test
    @DisplayName("Should allow up to the limit without waiting, so a small run is not slowed down")
    void ask_ShouldNotWait_UntilTheLimitIsReached() {
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn("ok");
        ChatServiceImpl limited = new ChatServiceImpl(builderFor(chatClient), 5, "");

        long start = System.currentTimeMillis();
        for (int call = 0; call < 5; call++) {
            limited.ask("anything");
        }

        // the sixth would block for the rest of the minute, so it is deliberately not made here
        assertTrue(System.currentTimeMillis() - start < 1000, "the first five should go straight out");
    }

    private static ChatClient.Builder builderFor(ChatClient client) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(client);
        return builder;
    }
}
