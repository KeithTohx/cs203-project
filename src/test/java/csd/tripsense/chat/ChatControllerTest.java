package csd.tripsense.chat;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.itinerary.ItineraryNotFoundException;

// unit tests for ChatController
// the chatbot service is mocked, so no model is called. the logged in user is put in the security
// context by hand, since standalone MockMvc has no security filters to do it
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatController Unit Tests")
class ChatControllerTest {

    @Mock
    private ChatbotService chatbotService;

    @InjectMocks
    private ChatController controller;

    private MockMvc mockMvc;

    private final CurrentUser traveller = new CurrentUser(7L, "traveller@example.com", "traveller", null, "USER", true);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(traveller, null, traveller.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ===== POST /api/itinerary/{id}/chat =====

    @Test
    @DisplayName("Should send the message as the logged in user and return the reply")
    void chat_ShouldReturnReply_WhenMessageSent() throws Exception {
        // Arrange
        when(chatbotService.askChatBot(7L, 1L, "Is my ski trip affected?"))
                .thenReturn("Yes, the earthquake may close the lifts at Niseko.");

        // Act + Assert
        mockMvc.perform(post("/api/itinerary/1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"Is my ski trip affected?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("assistant"))
                .andExpect(jsonPath("$.content").value("Yes, the earthquake may close the lifts at Niseko."));
    }

    @Test
    @DisplayName("Should ask the default question when the message is blank")
    void chat_ShouldUseDefaultQuestion_WhenMessageBlank() throws Exception {
        // Arrange
        when(chatbotService.askChatBot(7L, 1L, "Explain why this itinerary is impacted")).thenReturn("Because...");

        // Act + Assert
        mockMvc.perform(post("/api/itinerary/1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Because..."));
    }

    @Test
    @DisplayName("Should return 400 and never ask the model when the message is over 200 characters")
    void chat_ShouldReturn400_WhenMessageTooLong() throws Exception {
        // Arrange
        String tooLong = "a".repeat(201);

        // Act + Assert
        mockMvc.perform(post("/api/itinerary/1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());

        verify(chatbotService, never()).askChatBot(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("Should return 404 for an itinerary that is not the user's own")
    void chat_ShouldReturn404_WhenItineraryNotOwned() throws Exception {
        // Arrange: the service answers someone else's itinerary the same way as a missing one
        when(chatbotService.askChatBot(7L, 2L, "Hello")).thenThrow(new ItineraryNotFoundException(2L));

        // Act + Assert
        mockMvc.perform(post("/api/itinerary/2/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"Hello\"}"))
                .andExpect(status().isNotFound());
    }

    // ===== GET /api/itinerary/{id}/chat/history =====

    @Test
    @DisplayName("Should return the conversation oldest first")
    void history_ShouldReturnConversation() throws Exception {
        // Arrange
        when(chatbotService.history(7L, 1L)).thenReturn(List.of(
                new ChatResponse("user", "Is my ski trip affected?"),
                new ChatResponse("assistant", "Yes, the lifts may close.")));

        // Act + Assert
        mockMvc.perform(get("/api/itinerary/1/chat/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[1].role").value("assistant"));
    }

    @Test
    @DisplayName("Should return 404 for the history of someone else's itinerary")
    void history_ShouldReturn404_WhenItineraryNotOwned() throws Exception {
        // Arrange
        when(chatbotService.history(7L, 2L)).thenThrow(new ItineraryNotFoundException(2L));

        // Act + Assert
        mockMvc.perform(get("/api/itinerary/2/chat/history"))
                .andExpect(status().isNotFound());
    }

    // ===== DELETE /api/itinerary/{id}/chat =====

    @Test
    @DisplayName("Should clear the conversation and return 204")
    void reset_ShouldReturn204_AndClearConversation() throws Exception {
        // Act + Assert
        mockMvc.perform(delete("/api/itinerary/1/chat"))
                .andExpect(status().isNoContent());

        verify(chatbotService).reset(7L, 1L);
    }
}
