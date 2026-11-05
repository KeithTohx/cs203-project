package csd.tripsense.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import tools.jackson.databind.json.JsonMapper;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityService;
import csd.tripsense.country.Country;
import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactService;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryNotFoundException;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.news.News;
import csd.tripsense.user.preference.PreferenceService;
import csd.tripsense.user.preference.UserPreference;
import reactor.core.publisher.Flux;

// unit tests for ChatbotServiceImpl, the chatbot on the itinerary page.
// our own services are Mockito mocks. ChatClient is a library class, so it is not mocked: it is the
// real one, set up like ChatConfig does, around a fake model that records the prompt it was sent and
// gives a fixed answer. chat memory is the real Spring AI one with an in-memory store, so conversation
// ids and history are tested against real behaviour. no model is ever called
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatbotService Unit Tests")
class ChatbotServiceImplTest {

    private static final Long USER_ID = 7L;
    private static final Long ITINERARY_ID = 3L;

    @Mock
    private ItineraryService itineraryService;

    @Mock
    private ActivityService activityService;

    @Mock
    private ImpactService impactService;

    @Mock
    private PreferenceService preferenceService;

    @Mock
    private ChatMemoryRepository chatMemoryRepository;

    private FakeModel model;
    private ChatMemory chatMemory;
    private ChatbotServiceImpl chatbotService;

    private Itinerary skiTrip;
    private Activity skiDay;

    // stands in for the ai model: remembers the last prompt and always gives the same answer
    private static class FakeModel implements ChatModel {
        private Prompt lastPrompt;

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            lastPrompt = prompt;
            return reply("The lifts may be closed, so check with the resort.");
        }

        @Override
        public Flux<org.springframework.ai.chat.model.ChatResponse> stream(Prompt prompt) {
            lastPrompt = prompt;
            return Flux.just(reply("The lifts "), reply("may be closed."));
        }

        private static org.springframework.ai.chat.model.ChatResponse reply(String text) {
            return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new Generation(new AssistantMessage(text))));
        }
    }

    @BeforeEach
    void setUp() {
        model = new FakeModel();
        chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .build();
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
        chatbotService = new ChatbotServiceImpl(chatClient, chatMemory, itineraryService, activityService,
                impactService, preferenceService, JsonMapper.builder().build(), chatMemoryRepository);

        Country japan = new Country();
        japan.setName("Japan");

        skiTrip = new Itinerary();
        skiTrip.setId(ITINERARY_ID);
        skiTrip.setName("Japan Ski Trip");
        skiTrip.setCountry(japan);

        skiDay = new Activity();
        skiDay.setName("Ski at Niseko");
        skiDay.setAddress("Niseko, Hokkaido, Japan");
        skiDay.setDateStart(LocalDateTime.of(2026, 12, 16, 9, 0));
    }

    // ---------------------------------------------------------------- helpers

    // the traveller's own trip, with the given impacts and how many news items were checked against it
    private void givenTrip(List<Impact> impacts, long newsEvaluated) {
        when(itineraryService.getItineraryByIdAndUserId(ITINERARY_ID, USER_ID)).thenReturn(skiTrip);
        when(activityService.getActivitiesByItinerary(ITINERARY_ID)).thenReturn(List.of(skiDay));
        when(preferenceService.getPreferences(USER_ID)).thenReturn(new UserPreference(null));
        when(impactService.findImpacted(USER_ID, ITINERARY_ID)).thenReturn(impacts);
        when(impactService.countByItineraryIdAndUserId(ITINERARY_ID, USER_ID)).thenReturn(newsEvaluated);
    }

    private Impact impactFrom(String headline) {
        News news = new News();
        news.setTitle(headline);
        news.setSource("NHK");
        news.setDescription("Ski resorts are suspending lifts while they are inspected.");

        Impact impact = new Impact();
        impact.setNews(news);
        impact.setActivity(skiDay);
        impact.setReason("Lifts may stay shut after the earthquake");
        return impact;
    }

    private String systemPromptSent() {
        return model.lastPrompt.getSystemMessage().getText();
    }

    // ===== ASKING THE CHATBOT =====

    @Test
    @DisplayName("Should send the traveller's question to the model and return its answer")
    void askChatBot_ShouldReturnModelAnswer_WhenAsked() {
        // Arrange
        givenTrip(List.of(), 0);

        // Act
        String answer = chatbotService.askChatBot(USER_ID, ITINERARY_ID, "Can I still ski on Tuesday?");

        // Assert
        assertEquals("The lifts may be closed, so check with the resort.", answer);
        assertEquals("Can I still ski on Tuesday?", model.lastPrompt.getUserMessage().getText());
    }

    @Test
    @DisplayName("Should give the model the trip, its impacts and the traveller's preferences")
    void askChatBot_ShouldPutTripImpactsAndPreferencesInPrompt_WhenImpactsExist() {
        // Arrange
        givenTrip(List.of(impactFrom("Earthquake near Hokkaido")), 1);

        // Act
        chatbotService.askChatBot(USER_ID, ITINERARY_ID, "Is my trip affected?");

        // Assert
        String system = systemPromptSent();
        assertTrue(system.contains("Japan Ski Trip"));
        assertTrue(system.contains("Ski at Niseko"));
        assertTrue(system.contains("Earthquake near Hokkaido"));
        assertTrue(system.contains("Lifts may stay shut after the earthquake"));
        assertTrue(system.contains("Explain the impacts listed below"));
        assertTrue(system.contains("Traveller Preferences"));
    }

    @Test
    @DisplayName("Should tell the model nothing has been checked yet when no news was evaluated")
    void askChatBot_ShouldSayNothingEvaluatedYet_WhenNoNewsWasChecked() {
        // Arrange
        givenTrip(List.of(), 0);

        // Act
        chatbotService.askChatBot(USER_ID, ITINERARY_ID, "Is my trip affected?");

        // Assert - otherwise the model would tell the traveller the trip is fine before anything was checked
        assertTrue(systemPromptSent().contains("No news has been evaluated yet"));
    }

    @Test
    @DisplayName("Should tell the model nothing affects the trip when news was checked and none applies")
    void askChatBot_ShouldSayNothingAffectsTrip_WhenNewsCheckedWithNoImpacts() {
        // Arrange
        givenTrip(List.of(), 5);

        // Act
        chatbotService.askChatBot(USER_ID, ITINERARY_ID, "Is my trip affected?");

        // Assert
        assertTrue(systemPromptSent().contains("nothing currently affects this itinerary"));
    }

    @Test
    @DisplayName("Should pass at most ten impacts to the model")
    void askChatBot_ShouldSendOnlyTenImpacts_WhenThereAreEleven() {
        // Arrange - zero padded so "headline-01" is not part of "headline-10"
        List<Impact> impacts = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            impacts.add(impactFrom("headline-%02d".formatted(i)));
        }
        givenTrip(impacts, 11);

        // Act
        chatbotService.askChatBot(USER_ID, ITINERARY_ID, "What is happening?");

        // Assert
        String system = systemPromptSent();
        assertTrue(system.contains("headline-10"));
        assertFalse(system.contains("headline-11"));
    }

    @Test
    @DisplayName("Should not reach the model when the itinerary is not the traveller's")
    void askChatBot_ShouldThrowNotFound_WhenItineraryIsNotTheUsers() {
        // Arrange
        when(itineraryService.getItineraryByIdAndUserId(ITINERARY_ID, 8L))
                .thenThrow(new ItineraryNotFoundException(ITINERARY_ID));

        // Act
        assertThrows(ItineraryNotFoundException.class,
                () -> chatbotService.askChatBot(8L, ITINERARY_ID, "Show me someone else's trip"));

        // Assert
        assertNull(model.lastPrompt);
        verifyNoInteractions(activityService, impactService, preferenceService);
    }

    @Test
    @DisplayName("Should remember the conversation for this traveller and this itinerary only")
    void askChatBot_ShouldStoreConversationUnderUserAndItinerary_WhenAnswered() {
        // Arrange
        givenTrip(List.of(), 0);

        // Act
        chatbotService.askChatBot(USER_ID, ITINERARY_ID, "Can I still ski on Tuesday?");

        // Assert - the question and the answer are kept, and another trip's chat is untouched
        assertEquals(2, chatMemory.get("7:3").size());
        assertTrue(chatMemory.get("7:4").isEmpty());
    }

    @Test
    @DisplayName("Should stream the model's answer piece by piece")
    void streamChatBot_ShouldReturnAnswerInPieces_WhenAsked() {
        // Arrange
        givenTrip(List.of(), 0);

        // Act
        List<String> pieces = chatbotService.streamChatBot(USER_ID, ITINERARY_ID, "Can I still ski?")
                .collectList()
                .block();

        // Assert
        assertEquals(List.of("The lifts ", "may be closed."), pieces);
    }

    // ===== HISTORY AND RESET =====

    @Test
    @DisplayName("Should return earlier messages oldest first, labelled user or assistant")
    void history_ShouldReturnMessagesInOrder_WhenConversationExists() {
        // Arrange
        when(itineraryService.getItineraryByIdAndUserId(ITINERARY_ID, USER_ID)).thenReturn(skiTrip);
        chatMemory.add("7:3", List.of(new UserMessage("Can I ski?"), new AssistantMessage("The lifts may be closed.")));

        // Act
        List<ChatResponse> history = chatbotService.history(USER_ID, ITINERARY_ID);

        // Assert
        assertEquals(List.of(new ChatResponse("user", "Can I ski?"),
                new ChatResponse("assistant", "The lifts may be closed.")), history);
    }

    @Test
    @DisplayName("Should not show the conversation when the itinerary is not the traveller's")
    void history_ShouldThrowNotFound_WhenItineraryIsNotTheUsers() {
        // Arrange - the chat exists, but user 8 does not own trip 3
        chatMemory.add("8:3", List.of(new UserMessage("private question")));
        when(itineraryService.getItineraryByIdAndUserId(ITINERARY_ID, 8L))
                .thenThrow(new ItineraryNotFoundException(ITINERARY_ID));

        // Act + Assert
        assertThrows(ItineraryNotFoundException.class, () -> chatbotService.history(8L, ITINERARY_ID));
    }

    @Test
    @DisplayName("Should clear only this itinerary's conversation")
    void reset_ShouldClearOnlyThisConversation_WhenTravellerHasSeveralTrips() {
        // Arrange
        when(itineraryService.getItineraryByIdAndUserId(ITINERARY_ID, USER_ID)).thenReturn(skiTrip);
        chatMemory.add("7:3", List.of(new UserMessage("about the ski trip")));
        chatMemory.add("7:4", List.of(new UserMessage("about the beach trip")));

        // Act
        chatbotService.reset(USER_ID, ITINERARY_ID);

        // Assert
        assertTrue(chatMemory.get("7:3").isEmpty());
        assertEquals(1, chatMemory.get("7:4").size());
    }

    // ===== DELETE CONVERSATIONS TESTS =====

    @Test
    @DisplayName("Should delete only this user's conversations, including ones for deleted itineraries")
    void deleteAllConversationsFor_ShouldDeleteOnlyThisUsersConversations_WhenOtherUsersHaveConversations() {
        // Arrange - "4:99" stands for a chat about an itinerary that no longer exists
        when(chatMemoryRepository.findConversationIds())
            .thenReturn(List.of("4:4", "4:99", "44:1", "5:4"));

        // Act
        chatbotService.deleteAllConversationsFor(4L);

        // Assert
        verify(chatMemoryRepository, times(1)).deleteByConversationId("4:4");
        verify(chatMemoryRepository, times(1)).deleteByConversationId("4:99");
        verify(chatMemoryRepository, never()).deleteByConversationId("44:1");
        verify(chatMemoryRepository, never()).deleteByConversationId("5:4");
    }

    @Test
    @DisplayName("Should not delete user 11's conversations when deleting user 1")
    void deleteAllConversationsFor_ShouldNotDeleteUser11_WhenDeletingUser1() {
        // Arrange - the colon in the prefix is what stops "1:" matching "11:"
        when(chatMemoryRepository.findConversationIds())
            .thenReturn(List.of("1:1", "11:2", "111:3"));

        // Act
        chatbotService.deleteAllConversationsFor(1L);

        // Assert
        verify(chatMemoryRepository, times(1)).deleteByConversationId("1:1");
        verify(chatMemoryRepository, never()).deleteByConversationId("11:2");
        verify(chatMemoryRepository, never()).deleteByConversationId("111:3");
    }

    @Test
    @DisplayName("Should do nothing when no conversations exist")
    void deleteAllConversationsFor_ShouldDoNothing_WhenNoConversationsExist() {
        // Arrange
        when(chatMemoryRepository.findConversationIds()).thenReturn(Collections.emptyList());

        // Act
        chatbotService.deleteAllConversationsFor(4L);

        // Assert
        verify(chatMemoryRepository, times(1)).findConversationIds();
        verify(chatMemoryRepository, never()).deleteByConversationId(anyString());
    }
}
