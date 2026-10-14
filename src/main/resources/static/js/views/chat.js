import { api } from "../api.js";
import { escapeHtml } from "../util.js";

export function openChat(slot, itineraryId, onAuthLost) {
    if (slot.firstChild) return;

    slot.innerHTML = `
        <div class="chat">
            <p class="meta">AI answers can be wrong. Check details before changing plans.</p>
            <div class="log" aria-live="polite"></div>
            
            <form id="chat-form-${itineraryId}">
                <label for="chat-question-${itineraryId}">Your question</label>
                <input id="chat-question-${itineraryId}" required autocomplete="off">
                <button type="submit">Send</button>
            </form>
        </div>
    `;

    const messageLog = slot.querySelector(".log");
    const questionInput = slot.querySelector(`#chat-question-${itineraryId}`);
    const chatForm = slot.querySelector(`#chat-form-${itineraryId}`);
    const sendButton = chatForm.querySelector("button[type=submit]");

    questionInput.focus();

    // replies take a while, so block new questions until the current one is answered
    const setWaiting = (isWaiting) => {
        questionInput.disabled = isWaiting;
        sendButton.disabled = isWaiting;
    };

    const appendMessage = (text, isUserMessage) => {
        messageLog.insertAdjacentHTML(
            "beforeend",
            `<p class="${isUserMessage ? "me" : ""}">${escapeHtml(text)}</p>`
        );
    };

    chatForm.onsubmit = async (event) => {
        event.preventDefault();

        const questionText = questionInput.value.trim();
        if (!questionText) return;

        appendMessage(questionText, true);
        questionInput.value = "";

        appendMessage("Thinking…", false);
        const thinkingPlaceholder = messageLog.lastElementChild;
        setWaiting(true);

        try {
            const response = await api.chat(Number(itineraryId), questionText);
            thinkingPlaceholder.textContent = response?.content ?? "No reply.";
        } catch (error) {
            if (error.status === 401) return onAuthLost();

            // a server error here nearly always means the model is down, which the user can't fix
            const isServerError = error.status >= 500;
            thinkingPlaceholder.textContent = isServerError
                ? "The assistant is unavailable right now. Please try again later."
                : "The assistant failed: " + error.message;
        } finally {
            setWaiting(false);
            questionInput.focus();
        }
    };
}