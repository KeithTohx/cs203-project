package csd.tripsense.chat;

/**
 * Talks to the AI model.
 *
 * This package knows nothing about itineraries, news or impacts. It only sends a
 * prompt and hands back the answer, so any feature that needs the model can use it.
 */
public interface ChatService {

    /**
     * Sends a prompt and returns the reply as plain text.
     *
     * @param prompt what to ask the model
     * @return the reply, or a readable message if the model could not be reached
     */
    String ask(String prompt);

    /**
     * Sends a prompt and asks the model to answer in the shape of the given type.
     *
     * @param prompt what to ask the model
     * @param type the record or class the answer should be read into
     * @return the filled in object
     * @throws RuntimeException if the model is unreachable or the answer cannot be read
     */
    <T> T ask(String prompt, Class<T> type);
}
