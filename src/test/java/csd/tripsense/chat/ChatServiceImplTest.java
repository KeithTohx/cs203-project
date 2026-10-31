package csd.tripsense.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

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
        chatService = new ChatServiceImpl(builder);
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
}
