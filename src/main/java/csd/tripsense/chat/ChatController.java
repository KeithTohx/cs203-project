package csd.tripsense.chat;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/itinerary/{itineraryId}/chatbot")
@RequiredArgsConstructor
public class ChatController {

    private final ChatbotService chatbotService;

    @PostMapping
    public String chat(@PathVariable Long itineraryId, @RequestBody ChatRequest request) {
        return chatbotService.askChatBot(currentUserId(), itineraryId, request.message());
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@PathVariable Long itineraryId, @RequestBody ChatRequest request) {
        return chatbotService.streamChatBot(currentUserId(), itineraryId, request.message());
    }

    @GetMapping("/history")
    public List<ChatMessageDto> history(@PathVariable Long itineraryId) {
        return chatbotService.history(currentUserId(), itineraryId);
    }

    @DeleteMapping
    public ResponseEntity<Void> reset(@PathVariable Long itineraryId) {
        chatbotService.reset(currentUserId(), itineraryId);
        return ResponseEntity.noContent().build();
    }

    private Long currentUserId() {
        // current default value for testing
        // to be changed
        return 1L;
    }
}