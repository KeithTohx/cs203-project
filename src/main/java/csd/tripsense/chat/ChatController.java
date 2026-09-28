package csd.tripsense.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;



@RestController
public class ChatController {

    private final ChatClient chatClient;

    // spring gives us the builder already set up from the ollama settings in application properties
    public ChatController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    // quick way to check the model is reachable, try /chat?prompt=your question
    @GetMapping("/chat")
    public String chat(@RequestParam(defaultValue = "Say hello in one short sentence") String prompt) {
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
}
