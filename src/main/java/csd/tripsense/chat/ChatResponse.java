package csd.tripsense.chat;

public record ChatResponse(String reply) {
    public static ChatResponse of(String reply) {
        return new ChatResponse(reply);
    }
}