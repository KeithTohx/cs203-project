package csd.tripsense.chat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemoryRepository;

/**
 * Unit tests for ChatbotServiceImpl.deleteAllConversationsFor.
 *
 * Only ChatMemoryRepository is mocked. @InjectMocks uses the (Lombok) constructor and passes null for
 * the other dependencies (ChatClient, ChatMemory, services, ObjectMapper), which this method never touches.
 * This requires the "private final ChatMemoryRepository chatMemoryRepository;" field to exist.
 *
 * Map of source statements -> tests that cover them
 * ---------------------------------------------------------------------------------------------
 * String prefix = userId + ":";                                   | deleteAllConversationsFor_deletesOnlyThisUsersConversations
 *                                                                 | deleteAllConversationsFor_colonPreventsUser1MatchingUser11
 * for (id : chatMemoryRepository.findConversationIds())           | deleteAllConversationsFor_noConversations_deletesNothing
 * if (id.startsWith(prefix)) deleteByConversationId(id)           | all three tests
 * ---------------------------------------------------------------------------------------------
 */
@ExtendWith(MockitoExtension.class)
class ChatbotServiceImplDeleteTest {

    @Mock private ChatMemoryRepository chatMemoryRepository;

    @InjectMocks private ChatbotServiceImpl service;

    /**
     * TESTS: the loop + startsWith(prefix) filter. User 4's chats (including one for an itinerary that no
     * longer exists, "4:99") are deleted; other users' chats are not.
     */
    @Test
    void deleteAllConversationsFor_deletesOnlyThisUsersConversations() {
        when(chatMemoryRepository.findConversationIds())
                .thenReturn(List.of("4:4", "4:99", "44:1", "5:4"));                // feeds the for-loop

        service.deleteAllConversationsFor(4L);

        verify(chatMemoryRepository).deleteByConversationId("4:4");                // TESTS: match -> deleted
        verify(chatMemoryRepository).deleteByConversationId("4:99");               // TESTS: orphaned chat also deleted
        verify(chatMemoryRepository, never()).deleteByConversationId("44:1");      // TESTS: other user kept
        verify(chatMemoryRepository, never()).deleteByConversationId("5:4");       // TESTS: other user kept
    }

    /**
     * TESTS: the colon in prefix = userId + ":". Without it, user 1 would also match user 11's chats.
     */
    @Test
    void deleteAllConversationsFor_colonPreventsUser1MatchingUser11() {
        when(chatMemoryRepository.findConversationIds())
                .thenReturn(List.of("1:1", "11:2", "111:3"));

        service.deleteAllConversationsFor(1L);

        verify(chatMemoryRepository).deleteByConversationId("1:1");                // TESTS: own chat deleted
        verify(chatMemoryRepository, never()).deleteByConversationId("11:2");      // TESTS: "1:" must not match "11:"
        verify(chatMemoryRepository, never()).deleteByConversationId("111:3");
    }

    /**
     * TESTS: empty table -> the loop body never runs and nothing is deleted.
     */
    @Test
    void deleteAllConversationsFor_noConversations_deletesNothing() {
        when(chatMemoryRepository.findConversationIds()).thenReturn(List.of());

        service.deleteAllConversationsFor(4L);

        verify(chatMemoryRepository, never()).deleteByConversationId(anyString());
    }
}
