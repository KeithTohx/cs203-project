package csd.tripsense.chat;

public record ChatRequest(String message) {
    
    public ChatRequest {
        if (message == null || message.isBlank()) {
            message = "Explain why this itinerary is impacted";
        }
    }
}
