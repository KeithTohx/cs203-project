package csd.tripsense.chat;

import java.util.List;

import reactor.core.publisher.Flux;

public interface ChatbotService {
    String askChatBot(Long userId, Long itineraryId, String prompt);

    Flux<String> streamChatBot(Long userId, Long itineraryId, String prompt);

    List<ChatResponse> history(Long userId, Long itineraryId);

    void reset(Long userId, Long itineraryId);
    
    /** Deletes every stored conversation of this user, including ones for itineraries that no longer exist. */
    void deleteAllConversationsFor(Long userId);
}