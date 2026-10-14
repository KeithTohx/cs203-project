package csd.tripsense.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository) {   // JDBC repo is auto-configured
        return MessageWindowChatMemory.builder()
            .chatMemoryRepository(repository)
            .maxMessages(20)
            .build();
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder, ChatMemory memory) {
        return builder
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
            .build();
    }
}