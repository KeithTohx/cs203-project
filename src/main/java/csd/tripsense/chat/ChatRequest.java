package csd.tripsense.chat;

import jakarta.validation.constraints.Size;

@Size(max = 200, message = "Message should not be more than 200 characters long")
public record ChatRequest(String message) {
    
    public ChatRequest {
        if (message == null || message.isBlank()) {
            message = "Explain why this itinerary is impacted";
        }
    }
}
