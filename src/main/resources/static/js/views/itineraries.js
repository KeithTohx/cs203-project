import { api } from "../api.js";
import { dateRange, escapeHtml, showError, sortByStart, takeFlash } from "../util.js";
import { openChat } from "./chat.js";

// Group impacts by the news event that caused them, so each event lists the activities it affects.
const groupByEvent = (impacts) => {
    const events = new Map();
    for (const impact of impacts) {
        const key = impact.newsId ?? impact.newsTitle;
        if (!events.has(key)) events.set(key, { title: impact.newsTitle, impacts: [] });
        events.get(key).impacts.push(impact);
    }
    return [...events.values()];
};

// Component: one news event and what it affects. An impact with no activity applies to the whole itinerary.
const createEventCard = (event) => {
    const formatReason = impact => impact.reason ? ` <span class="meta">— ${escapeHtml(impact.reason)}</span>` : "";
    const wholeItineraryImpacts = event.impacts.filter(impact => impact.activityId == null);
    const activityImpacts = event.impacts.filter(impact => impact.activityId != null);

    return `
        <div class="imp hit">
            <strong>${escapeHtml(event.title || "Untitled news")}</strong>
            ${wholeItineraryImpacts.map(impact => `<p>Affects the whole itinerary${formatReason(impact)}</p>`).join("")}
            ${activityImpacts.length ? `<p class="meta">Affected activities:</p>
                <ul>${activityImpacts.map(impact => `<li>${escapeHtml(impact.activityName)}${formatReason(impact)}</li>`).join("")}</ul>` : ""}
        </div>
    `;
};

// Component: the activities in one itinerary as a timeline; a red dot marks those hit by news. Editing happens on the itinerary's edit page.
const createActivityList = (itineraryId, activities, impactedActivityIds) => {
    const addLink = `<a class="btn small add" href="#/itineraries/${itineraryId}/activities/new">+ Add activity</a>`;
    if (!activities.length) return `<p class="meta">No activities planned yet.</p>${addLink}`;
    return `<ol class="timeline">${sortByStart(activities).map(activity => `
        <li${impactedActivityIds.has(activity.id) ? ` class="hit"` : ""}>
            <strong>${escapeHtml(activity.name)}</strong>
            <br><span class="meta">${[escapeHtml(activity.address), dateRange(activity.dateStart, activity.dateEnd)].filter(Boolean).join(" · ")}</span>
        </li>`).join("")}</ol>${addLink}`;
};

// Component: Renders a single itinerary card
const createItineraryCard = ({ itinerary, activities, impacts, failed }) => {
    const itineraryId = Number(itinerary.id);
    const isPast = new Date(itinerary.dateEnd) < Date.now();
    const impactedOnly = impacts.filter(impact => impact.impacted);
    const impactedActivityIds = new Set(impactedOnly.map(impact => impact.activityId).filter(id => id != null));
    const events = groupByEvent(impactedOnly);   // news checked and found not to matter is left out

    const body = failed
        ? `<div class="msg err" role="alert">Couldn't load the details of this itinerary.</div>`
        : `
            <h3>Activities</h3>
            ${createActivityList(itineraryId, activities, impactedActivityIds)}
            <details${events.length ? " open" : ""}>
                <summary>News affecting this itinerary (${events.length})</summary>
                ${events.map(createEventCard).join("") || `<div class="msg">No news has affected this itinerary.</div>`}
            </details>`;

    return `
        <article class="card ${isPast ? "past" : ""}">
            <div class="row">
                <h2>
                    ${escapeHtml(itinerary.name)}
                    <span class="tag">${isPast ? "Past" : "Upcoming"}</span>
                    ${impactedOnly.length ? `<span class="tag bad">Impacted</span>` : ""}
                </h2>
                <a class="btn" href="#/itineraries/${itineraryId}/edit" aria-label="Edit ${escapeHtml(itinerary.name)}">Edit</a>
            </div>
            <p class="meta">${escapeHtml(itinerary.countryName)} · ${dateRange(itinerary.dateStart, itinerary.dateEnd)}</p>
            ${body}
            <p><button class="ghost" data-chat="${itineraryId}" aria-expanded="false">Ask the assistant</button></p>
            <div class="chat-slot"></div>
        </article>
    `;
};

// Fetch activities and impacts for one itinerary. A 401 is rethrown; other errors only affect this card.
async function loadDetails(itinerary) {
    try {
        const [activities, impacts] = await Promise.all([
            api.itineraryActivities(itinerary.id),
            api.itineraryImpacts(itinerary.id),
        ]);
        return { itinerary, activities: activities ?? [], impacts: impacts ?? [] };
    } catch (error) {
        if (error.status === 401) throw error;
        return { itinerary, activities: [], impacts: [], failed: true };
    }
}

const section = (title, cards) => cards.length ? `<h2 class="section">${title}</h2>${cards.join("")}` : "";

export async function showItineraries(root, user, onAuthLost) {
    const flashMessage = takeFlash();
    root.innerHTML = `
        <div class="row page-head">
            <h1>Your itineraries</h1>
            <a class="btn primary" href="#/itineraries/new">+ New itinerary</a>
        </div>
        ${flashMessage ? `<p class="ok" role="status">${escapeHtml(flashMessage)}</p>` : ""}
        <div id="itinerary-list" class="msg">Loading…</div>
    `;

    const itineraryListContainer = root.querySelector("#itinerary-list");

    try {
        // the backend sends an empty body instead of [] when the user has no itineraries
        const itineraries = (await api.itineraries(user.id)) ?? [];
        const itinerariesWithDetails = await Promise.all(itineraries.map(loadDetails));

        const now = Date.now();
        const startTime = details => new Date(details.itinerary.dateStart);
        const upcoming = itinerariesWithDetails.filter(details => new Date(details.itinerary.dateEnd) >= now)
            .sort((first, second) => startTime(first) - startTime(second));
        const past = itinerariesWithDetails.filter(details => new Date(details.itinerary.dateEnd) < now)
            .sort((first, second) => startTime(second) - startTime(first));   // most recent first

        itineraryListContainer.className = "";
        itineraryListContainer.innerHTML = itinerariesWithDetails.length
            ? section("Upcoming", upcoming.map(createItineraryCard)) + section("Past", past.map(createItineraryCard))
            : `<div class="msg">You have no itineraries yet. <a href="#/itineraries/new">Create your first one</a></div>`;
    } catch (error) {
        return showError(itineraryListContainer, error, onAuthLost);
    }

    // Event delegation: the assistant button opens the chat, and closes it again
    itineraryListContainer.addEventListener("click", (event) => {
        const chatButton = event.target.closest("[data-chat]");
        if (!chatButton) return;

        const cardElement = chatButton.closest(".card");
        const chatSlot = cardElement ? cardElement.querySelector(".chat-slot") : null;
        const itineraryId = chatButton.dataset.chat;
        if (!chatSlot || !itineraryId) return;

        const isOpen = chatSlot.firstChild !== null;
        if (isOpen) chatSlot.innerHTML = "";
        else openChat(chatSlot, itineraryId, onAuthLost);

        chatButton.textContent = isOpen ? "Ask the assistant" : "Close assistant";
        chatButton.setAttribute("aria-expanded", String(!isOpen));
    });
}
