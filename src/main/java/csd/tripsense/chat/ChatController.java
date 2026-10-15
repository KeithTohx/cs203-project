package csd.tripsense.chat;

import java.util.List;
import csd.tripsense.auth.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/itinerary/{itineraryId}/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatbotService chatbotService;

    // "me" is the logged in user from the session (from SecurityConfig 401 if not logged in)

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@PathVariable Long itineraryId, @Valid @RequestBody ChatRequest request,
                             @AuthenticationPrincipal CurrentUser me) {
        String reply = chatbotService.askChatBot(me.getId(), itineraryId, request.message());
        return new ChatResponse("assistant", reply);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@PathVariable Long itineraryId, @Valid @RequestBody ChatRequest request,
                               @AuthenticationPrincipal CurrentUser me) {
        return chatbotService.streamChatBot(me.getId(), itineraryId, request.message());
    }

    @GetMapping("/history")
    public List<ChatResponse> history(@PathVariable Long itineraryId, @AuthenticationPrincipal CurrentUser me) {
        return chatbotService.history(me.getId(), itineraryId);
    }

    @DeleteMapping
    public ResponseEntity<Void> reset(@PathVariable Long itineraryId, @AuthenticationPrincipal CurrentUser me) {
        chatbotService.reset(me.getId(), itineraryId);
        return ResponseEntity.noContent().build();
    }
}