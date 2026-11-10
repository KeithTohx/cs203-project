package csd.tripsense.chat;

import java.util.ArrayDeque;
import java.util.Deque;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;
    private final int perMinute;

    // when each of the last minute's calls went out, oldest first
    private final Deque<Long> recentCalls = new ArrayDeque<>();

    // spring gives us the builder already set up from whichever provider spring.ai.model.chat picks
    public ChatServiceImpl(ChatClient.Builder chatClientBuilder,
                           @Value("${chat.rate-limit.per-minute:0}") int perMinute) {
        this.chatClient = chatClientBuilder.build();
        this.perMinute = perMinute;
    }

    // every model call in the app comes through here, so one brake covers all of them.
    // a hosted free tier answers 429 when we go too fast, and the evaluation jobs give up
    // after three failures in a row, so it is cheaper to wait than to be refused
    private void waitForSlot() {
        if (perMinute <= 0) return;

        synchronized (recentCalls) {
            while (true) {
                long now = System.currentTimeMillis();
                while (!recentCalls.isEmpty() && now - recentCalls.peekFirst() >= 60_000) {
                    recentCalls.pollFirst();
                }
                if (recentCalls.size() < perMinute) {
                    recentCalls.addLast(now);
                    return;
                }
                // the oldest call leaves the window at this point, so there is a slot then
                long waitMs = 60_000 - (now - recentCalls.peekFirst()) + 50;
                try {
                    recentCalls.wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for a model slot", e);
                }
            }
        }
    }

    @Override
    public String ask(String prompt) {
        try {
            waitForSlot();
            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            // a missing api key, an exhausted quota, or no local model running
            return "Could not reach the model: " + e.getMessage();
        }
    }

    @Override
    public <T> T ask(String prompt, Class<T> type) {
        // no try/catch here, the caller decides what a failed evaluation should look like
        waitForSlot();
        return chatClient.prompt()
                .user(prompt)
                .call()
                .entity(type);
    }
}
