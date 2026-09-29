package csd.tripsense.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;

    // spring gives us the builder already set up from the ollama settings in application properties
    public ChatServiceImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String ask(String prompt) {
        try {
            return chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception e) {
            // nearly always means ollama is not running on port 11434
            return "Could not reach the model: " + e.getMessage();
        }
    }

    @Override
    public <T> T ask(String prompt, Class<T> type) {
        // no try/catch here, the caller decides what a failed evaluation should look like
        return chatClient.prompt()
                .user(prompt)
                .call()
                .entity(type);
    }
}
