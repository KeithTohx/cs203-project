// The AI assistant: one side panel for the whole app, opened from an itinerary card.
// Conversations are stored by the backend per user and itinerary, so opening the panel
// loads the earlier messages instead of starting blank.
import { api } from "../api.js";
import { confirmTwice, escapeHtml } from "../util.js";

// same limit as the backend's ChatRequest
const MAX_MESSAGE_LENGTH = 200;
// the counter only appears once the message gets close to the limit
const SHOW_COUNTER_FROM = 160;

const SUGGESTIONS = [
    "Is my trip affected by any news?",
    "Which activities should I double-check?",
    "Summarise my itinerary",
];

// created the first time the assistant is opened, then reused for every itinerary
let panel = null;
// which itinerary the panel is showing. a reply that arrives after switching trips is dropped
let currentItineraryId = null;
let isWaiting = false;
let handleAuthLost = () => { };

const elements = {};

function createPanel() {
    const suggestionButtons = SUGGESTIONS
        .map(text => `<button type="button" class="chat-suggestion">${escapeHtml(text)}</button>`)
        .join("");

    document.body.insertAdjacentHTML("beforeend", `
        <dialog class="chat-panel" aria-labelledby="chat-title">
            <div class="chat-panel-body">
                <header class="chat-header">
                    <div>
                        <h2 id="chat-title">AI assistant</h2>
                        <p class="meta" id="chat-trip"></p>
                    </div>
                    <div class="chat-header-actions">
                        <button type="button" class="ghost danger small" id="chat-clear">Clear chat</button>
                        <button type="button" class="ghost icon-button" id="chat-close" aria-label="Close AI assistant">×</button>
                    </div>
                </header>

                <div class="chat-log" aria-live="polite"></div>

                <div class="chat-empty" id="chat-empty" hidden>
                    <p class="chat-empty-title">How can I help with this trip?</p>
                    <p class="meta">I can explain news that may affect your plans.</p>
                    <div class="chat-suggestions">
                        ${suggestionButtons}
                    </div>
                </div>

                <form class="chat-composer" id="chat-form">
                    <label for="chat-question" class="sr-only">Message the AI assistant</label>
                    <textarea id="chat-question" rows="1" maxlength="${MAX_MESSAGE_LENGTH}"
                        placeholder="Ask about this trip…" autocomplete="off"></textarea>
                    <button type="submit" class="chat-send" aria-label="Send message">
                        <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                            <path d="M12 19V5M5 12l7-7 7 7" fill="none" stroke="currentColor" stroke-width="2.5"
                                stroke-linecap="round" stroke-linejoin="round" />
                        </svg>
                    </button>
                </form>
                <p class="meta chat-footnote">
                    <span>AI answers can be wrong. Check details before changing plans.</span>
                    <span id="chat-counter" hidden></span>
                </p>
            </div>
        </dialog>
    `);

    const dialog = document.body.lastElementChild;
    elements.trip = dialog.querySelector("#chat-trip");
    elements.log = dialog.querySelector(".chat-log");
    elements.emptyState = dialog.querySelector("#chat-empty");
    elements.form = dialog.querySelector("#chat-form");
    elements.input = dialog.querySelector("#chat-question");
    elements.sendButton = elements.form.querySelector(".chat-send");
    elements.clearButton = dialog.querySelector("#chat-clear");
    elements.counter = dialog.querySelector("#chat-counter");

    dialog.querySelector("#chat-close").onclick = () => dialog.close();

    // a click on the dimmed area outside the panel lands on the dialog itself, not its content
    dialog.addEventListener("click", event => {
        if (event.target === dialog) dialog.close();
    });

    // the panel belongs to the itineraries page, so it closes when the page changes
    addEventListener("hashchange", () => {
        if (dialog.open) dialog.close();
    });

    elements.form.onsubmit = event => {
        event.preventDefault();
        sendQuestion(elements.input.value);
    };

    // enter sends, shift+enter starts a new line, like most chat apps
    elements.input.addEventListener("keydown", event => {
        const isPlainEnter = event.key === "Enter" && !event.shiftKey && !event.isComposing;
        if (!isPlainEnter) return;

        event.preventDefault();
        elements.form.requestSubmit();
    });
    elements.input.addEventListener("input", updateComposer);

    for (const suggestion of dialog.querySelectorAll(".chat-suggestion")) {
        suggestion.addEventListener("click", () => sendQuestion(suggestion.textContent));
    }

    elements.resetClearButton = confirmTwice(elements.clearButton, {
        confirmText: "Clear chat?",
        onConfirm: clearConversation,
    });

    return dialog;
}

// the box grows with the message up to a few lines, then scrolls.
// the send button only works when there is something to send
function updateComposer() {
    const input = elements.input;
    input.style.height = "auto";
    // a closed panel measures everything as 0, so only size the box while it's showing
    if (input.scrollHeight > 0) input.style.height = `${input.scrollHeight}px`;

    const length = input.value.length;
    elements.counter.hidden = length < SHOW_COUNTER_FROM;
    elements.counter.textContent = `${length}/${MAX_MESSAGE_LENGTH}`;

    const hasText = input.value.trim().length > 0;
    elements.sendButton.disabled = isWaiting || !hasText;
}

// replies take a while, so block new questions until the current one is answered
function setWaiting(waiting) {
    isWaiting = waiting;
    elements.input.disabled = waiting;
    elements.clearButton.disabled = waiting;
    for (const suggestion of panel.querySelectorAll(".chat-suggestion")) {
        suggestion.disabled = waiting;
    }
    updateComposer();
}

function scrollToLatest() {
    elements.log.scrollTop = elements.log.scrollHeight;
}

// kind is "user", "assistant" or "error"
function appendMessage(text, kind) {
    elements.log.insertAdjacentHTML("beforeend", `<div class="chat-message ${kind}">${escapeHtml(text)}</div>`);
    elements.emptyState.hidden = true;
    elements.clearButton.hidden = false;
    scrollToLatest();
    return elements.log.lastElementChild;
}

// three bouncing dots while the model writes its answer. screen readers hear the text instead
function appendTypingIndicator() {
    elements.log.insertAdjacentHTML("beforeend", `
        <div class="chat-message assistant typing">
            <span class="typing-dots" aria-hidden="true"><span></span><span></span><span></span></span>
            <span class="sr-only">AI assistant is typing</span>
        </div>
    `);
    elements.emptyState.hidden = true;
    scrollToLatest();
    return elements.log.lastElementChild;
}

function replaceMessage(messageElement, text, kind) {
    messageElement.className = `chat-message ${kind}`;
    messageElement.textContent = text;
}

// nothing to clear in an empty chat, so the button only shows once there are messages
function showEmptyState() {
    elements.log.innerHTML = "";
    elements.emptyState.hidden = false;
    elements.clearButton.hidden = true;
}

function loseSession() {
    panel.close();
    handleAuthLost();
}

async function loadHistory(itineraryId) {
    elements.log.innerHTML = "";
    elements.emptyState.hidden = true;
    elements.clearButton.hidden = true;
    const loadingIndicator = appendTypingIndicator();

    try {
        const messages = (await api.chatHistory(itineraryId)) ?? [];
        if (itineraryId !== currentItineraryId) return;

        // the backend only stores what the user and the assistant said, but skip anything else just in case
        const conversation = messages.filter(message => message.role === "user" || message.role === "assistant");
        if (!conversation.length) return showEmptyState();

        elements.log.innerHTML = "";
        for (const message of conversation) {
            appendMessage(message.content, message.role);
        }
    } catch (error) {
        if (error.status === 401) return loseSession();
        if (itineraryId !== currentItineraryId) return;

        // chatting still works without the old messages, so this isn't a dead end
        replaceMessage(loadingIndicator, "Couldn't load earlier messages.", "error");
    }
}

async function sendQuestion(text) {
    const questionText = text.trim();
    if (!questionText || isWaiting) return;

    const itineraryId = currentItineraryId;
    appendMessage(questionText, "user");
    elements.input.value = "";

    const typingIndicator = appendTypingIndicator();
    setWaiting(true);

    try {
        const response = await api.chat(itineraryId, questionText);
        replaceMessage(typingIndicator, response?.content ?? "No reply.", "assistant");
    } catch (error) {
        if (error.status === 401) return loseSession();

        // a server error here nearly always means the model is down, which the user can't fix
        const isServerError = error.status >= 500;
        const message = isServerError
            ? "The assistant is unavailable right now. Please try again later."
            : "The assistant failed: " + error.message;
        replaceMessage(typingIndicator, message, "error");
    } finally {
        // if the user switched trips while waiting, the new trip's panel has its own state
        if (itineraryId === currentItineraryId) {
            setWaiting(false);
            scrollToLatest();
            elements.input.focus();
        }
    }
}

async function clearConversation() {
    const itineraryId = currentItineraryId;
    elements.resetClearButton();   // the button stays in the panel, so put it back to "Clear chat"

    try {
        await api.resetChat(itineraryId);
        if (itineraryId === currentItineraryId) showEmptyState();
    } catch (error) {
        if (error.status === 401) return loseSession();
        appendMessage("Couldn't clear the chat: " + error.message, "error");
    }
}

// itinerary is { id, name }. opening another itinerary's assistant swaps the conversation
export function openChat(itinerary, onAuthLost) {
    if (!panel) panel = createPanel();

    handleAuthLost = onAuthLost;
    currentItineraryId = itinerary.id;
    elements.trip.textContent = itinerary.name;
    elements.input.value = "";

    // show the panel first, so the message box can measure its height
    if (!panel.open) panel.showModal();
    setWaiting(false);
    elements.input.focus();
    loadHistory(itinerary.id);
}
