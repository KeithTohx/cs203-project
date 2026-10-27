package csd.tripsense.chat;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemoryRepository;

/**
 * LOCATION: src/test/java/csd/tripsense/chat/ChatbotServiceImplTest.java
 *
 * Unit tests for ChatbotServiceImpl.deleteAllConversationsFor (added for the delete-account story)
 * Tests that every conversation of one user is deleted, including chats for itineraries that were
 * deleted earlier, and that other users' conversations are left alone
 * Only ChatMemoryRepository is mocked. @InjectMocks builds the service through its constructor and
 * passes null for the other dependencies (chat client, services, object mapper), which this method
 * never uses, so the "private final ChatMemoryRepository chatMemoryRepository;" field must exist
 * Other methods in ChatbotServiceImpl (askChatBot, streamChatBot, history, reset) are not covered here
 * Does not cover whether the real Spring AI repository works on SQLite or joins the database transaction
 *
 * Source statement in deleteAllConversationsFor      -> Test that covers it
 * String prefix = userId + ":"                       -> deleteAllConversationsFor_ShouldNotDeleteUser11_WhenDeletingUser1
 * for (id : findConversationIds())                   -> deleteAllConversationsFor_ShouldDoNothing_WhenNoConversationsExist
 * if (id.startsWith(prefix)) deleteByConversationId  -> deleteAllConversationsFor_ShouldDeleteOnlyThisUsersConversations_WhenOtherUsersHaveConversations
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatbotService Unit Tests")
class ChatbotServiceImplTest {

    @Mock
    private ChatMemoryRepository chatMemoryRepository;

    @InjectMocks
    private ChatbotServiceImpl chatbotService;

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
