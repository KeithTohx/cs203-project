package csd.tripsense.chat;

// whether the assistant is still writing an answer in this conversation. a page that was reloaded
// mid answer uses it to show the answer is coming rather than a question with no reply
public record ChatStatus(boolean answering) {
}
