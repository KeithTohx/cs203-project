package csd.tripsense.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    // a refused model is left alone for this long rather than asked again on every call
    private static final Duration RESTED = Duration.ofMinutes(30);
    // a per min refusal clears in secs so it only sits out a min
    private static final Duration SHORT_REST = Duration.ofMinutes(1);

    private final ChatClient chatClient;
    private final int perMinute;

    // best first. empty means use whatever the provider is already configured with
    private final List<String> models;

    // model -> when it is worth trying again, for the ones that ran out of budget
    private final Map<String, Instant> restingUntil = new ConcurrentHashMap<>();

    // when each of the last minute's calls went out, oldest first
    private final Deque<Long> recentCalls = new ArrayDeque<>();

    // spring gives us the builder already set up from whichever provider spring.ai.model.chat picks
    public ChatServiceImpl(ChatClient.Builder chatClientBuilder,
                           @Value("${chat.rate-limit.per-minute:0}") int perMinute,
                           @Value("${chat.models:}") String models) {
        this.chatClient = chatClientBuilder.build();
        this.perMinute = perMinute;
        this.models = models.isBlank()
                ? List.of()
                : Arrays.stream(models.split("\\s*,\\s*")).filter(name -> !name.isBlank()).toList();
    }

    // groq meters each model on its own, so a refusal moves on to the next one
    // anything else is a real failure and is rethrown
    private static boolean outOfBudget(Exception error) {
        String message = String.valueOf(error.getMessage()).toLowerCase();
        return message.contains("429") || message.contains("rate limit");
    }

    // groq says which limit it hit, only a per day one means done for today
    static Duration restFor(Exception error) {
        return String.valueOf(error.getMessage()).toLowerCase().contains("per day") ? RESTED : SHORT_REST;
    }

    // every model is resting, if the first is back within a min its cheaper to wait than fail the job
    private boolean waitForFirstBack() {
        long waitMs = models.stream().map(restingUntil::get).filter(Objects::nonNull)
                .mapToLong(until -> Duration.between(Instant.now(), until).toMillis())
                .min().orElse(0);
        if (waitMs > SHORT_REST.toMillis()) return false;
        try {
            Thread.sleep(Math.max(0, waitMs));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean resting(String model) {
        Instant until = restingUntil.get(model);
        return until != null && Instant.now().isBefore(until);
    }

    // runs the call against the first model with budget left, moving down the list on a refusal
    private <T> T withFallback(Function<ChatClient.ChatClientRequestSpec, T> call, String prompt) {
        return runWithFallback(chatClient.prompt().user(prompt), call);
    }

    @Override
    public <T> T runWithFallback(ChatClient.ChatClientRequestSpec request,
                                 Function<ChatClient.ChatClientRequestSpec, T> call) {
        if (models.isEmpty()) {
            waitForSlot();
            return call.apply(request);
        }

        Exception last = null;
        // 2nd pass only happens after waiting out a short rest
        for (int pass = 0; pass < 2; pass++) {
            for (String model : models) {
                if (resting(model)) continue;
                waitForSlot();
                try {
                    // options() returns the same request, so each attempt just renames the model
                    return call.apply(request.options(ChatOptions.builder().model(model)));
                } catch (Exception e) {
                    if (!outOfBudget(e)) throw e;
                    Duration rest = restFor(e);
                    log.warn("Model {} refused, resting it for {}s: {}", model, rest.toSeconds(), e.getMessage());
                    restingUntil.put(model, Instant.now().plus(rest));
                    last = e;
                }
            }
            if (!waitForFirstBack()) break;
        }
        throw new IllegalStateException("Every model is out of budget", last);
    }

    @Override
    public Optional<String> firstAvailableModel() {
        return models.stream().filter(model -> !resting(model)).findFirst();
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
            return withFallback(spec -> spec.call().content(), prompt);
        } catch (Exception e) {
            // a missing api key, every model out of budget, or no local model running
            return "Could not reach the model: " + e.getMessage();
        }
    }

    @Override
    public <T> T ask(String prompt, Class<T> type) {
        // no try/catch here, the caller decides what a failed evaluation should look like
        return withFallback(spec -> spec.call().entity(type), prompt);
    }
}
