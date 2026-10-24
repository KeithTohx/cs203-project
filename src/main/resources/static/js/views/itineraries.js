import { api } from "../api.js";
import { dateRange, escapeHtml, formatDate, safeUrl, showError, sortByStart, takeFlash } from "../util.js";
import { openChat } from "./chat.js";

// Group impacts by the news event that caused them, so each event lists the activities it affects.
const groupByEvent = (impacts) => {
    const events = new Map();
    for (const impact of impacts) {
        const key = impact.newsId ?? impact.newsTitle;
        if (!events.has(key)) {
            events.set(key, {
                title: impact.newsTitle,
                source: impact.newsSource,
                url: impact.newsUrl,
                description: impact.newsDescription,
                publishedAt: impact.newsPublishedAt,
                impacts: [],
            });
        }
        events.get(key).impacts.push(impact);
    }
    return [...events.values()];
};

// the site name from a link, so gdelt news without a source still gets a label
const hostnameOf = url => {
    try {
        return new URL(url).hostname.replace(/^www\./, "");
    } catch {
        return "";
    }
};

// each bubble needs its own id so aria-describedby points at the right popover
let nextPopoverId = 0;

// Component: a small bubble naming where the news came from. hovering or focusing it shows more
// about the article, and clicking opens the original in a new tab
const createSourceBubble = (event) => {
    const link = safeUrl(event.url);
    const label = event.source || hostnameOf(event.url);
    if (!label) return "";

    nextPopoverId += 1;
    const popoverId = `news-source-${nextPopoverId}`;
    const published = formatDate(event.publishedAt);
    const description = event.description ? escapeHtml(event.description) : "";
    const site = hostnameOf(event.url);

    const popover = `
        <span class="source-popover" id="${popoverId}" role="tooltip">
            <span class="meta">
                ${escapeHtml(label)}${published ? ` · ${published}` : ""}
            </span>
            ${description ? `<span class="source-description">${description}</span>` : ""}
            <span class="meta">
                ${link ? `Opens ${escapeHtml(site)} in a new tab` : "No link available for this article"}
            </span>
        </span>
    `;

    // without a safe link the bubble still shows the info, it just isn't clickable
    if (!link) {
        return `
            <span class="source">
                <span class="source-bubble" tabindex="0" aria-describedby="${popoverId}">
                    ${escapeHtml(label)}
                </span>
                ${popover}
            </span>
        `;
    }

    return `
        <span class="source">
            <a class="source-bubble" href="${link}" target="_blank" rel="noopener noreferrer"
               aria-describedby="${popoverId}">
                ${escapeHtml(label)}
                <span aria-hidden="true">↗</span>
                <span class="sr-only">(opens in a new tab)</span>
            </a>
            ${popover}
        </span>
    `;
};

// Component: the button that lets the user overrule the model. on a dismissed impact it puts the impact back.
// no second click is needed like removing an activity, since a rejection can always be undone
const createRejectButton = (impact, isDismissed) => {
    const target = impact.activityName ?? "the whole itinerary";
    const label = isDismissed ? "Restore" : "Mark as not relevant";
    const ariaLabel = isDismissed
        ? `Restore news impact on ${target}`
        : `Mark news impact on ${target} as not relevant`;

    return `
        <button class="ghost small impact-action" type="button"
                data-impact="${Number(impact.id)}" data-reject="${!isDismissed}"
                aria-label="${escapeHtml(ariaLabel)}">
            ${label}
        </button>
    `;
};

// Component: one news event and what it affects. An impact with no activity applies to the whole itinerary.
// Dismissed events are the ones the user marked as not relevant, shown muted so they can be restored.
const createEventCard = (event, isDismissed = false) => {
    const formatReason = impact => impact.reason ? ` <span class="meta">— ${escapeHtml(impact.reason)}</span>` : "";
    const wholeItineraryImpacts = event.impacts.filter(impact => impact.activityId == null);
    const activityImpacts = event.impacts.filter(impact => impact.activityId != null);

    const wholeItineraryLines = wholeItineraryImpacts.map(impact => `
        <p class="impact-line">
            <span>Affects the whole itinerary${formatReason(impact)}</span>
            ${createRejectButton(impact, isDismissed)}
        </p>
    `).join("");

    const activityLines = activityImpacts.map(impact => `
        <li class="impact-line">
            <span>${escapeHtml(impact.activityName)}${formatReason(impact)}</span>
            ${createRejectButton(impact, isDismissed)}
        </li>
    `).join("");

    const activitySection = activityImpacts.length
        ? `
            <p class="meta">Affected activities:</p>
            <ul>
                ${activityLines}
            </ul>
        `
        : "";

    return `
        <div class="imp ${isDismissed ? "dismissed" : "hit"}">
            <div class="imp-head">
                <strong>${escapeHtml(event.title || "Untitled news")}</strong>
                ${createSourceBubble(event)}
            </div>
            ${wholeItineraryLines}
            ${activitySection}
        </div>
    `;
};

// Component: news the user marked as not relevant, closed by default so it stays out of the way
const createDismissedSection = (dismissedEvents) => {
    if (!dismissedEvents.length) return "";

    return `
        <details>
            <summary>Marked as not relevant (${dismissedEvents.length})</summary>
            ${dismissedEvents.map(event => createEventCard(event, true)).join("")}
        </details>
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

// dateEnd is stored as midnight, so a trip only counts as past once its last day is over
const isTripOver = itinerary => {
    const endOfLastDay = new Date(itinerary.dateEnd);
    endOfLastDay.setHours(23, 59, 59, 999);
    return endOfLastDay < Date.now();
};

// Component: Renders a single itinerary card
const createItineraryCard = ({ itinerary, activities, impacts, failed }) => {
    const itineraryId = Number(itinerary.id);
    const isPast = isTripOver(itinerary);
    // news checked and found not to matter is left out. what the user rejected no longer counts
    // as impacted, but is kept in its own section so it can be restored
    const impactedOnly = impacts.filter(impact => impact.impacted && !impact.rejected);
    const dismissed = impacts.filter(impact => impact.impacted && impact.rejected);
    const impactedActivityIds = new Set(impactedOnly.map(impact => impact.activityId).filter(id => id != null));
    const events = groupByEvent(impactedOnly);
    const dismissedEvents = groupByEvent(dismissed);

    const body = failed
        ? `<div class="msg err" role="alert">Couldn't load the details of this itinerary.</div>`
        : `
            <h3>Activities</h3>
            ${createActivityList(itineraryId, activities, impactedActivityIds)}
            <details${events.length ? " open" : ""}>
                <summary>News affecting this itinerary (${events.length})</summary>
                ${events.map(event => createEventCard(event)).join("") || `<div class="msg">No news has affected this itinerary.</div>`}
            </details>
            ${createDismissedSection(dismissedEvents)}
            <p class="err" data-impact-error role="alert"></p>`;

    return `
        <article class="card ${isPast ? "past" : ""}" data-itinerary="${itineraryId}">
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
            <p>
                <button class="ghost" data-chat="${itineraryId}" data-chat-name="${escapeHtml(itinerary.name)}" aria-haspopup="dialog">
                    Ask the AI assistant
                </button>
            </p>
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

// Rejects or restores one impact, then redraws only that itinerary's card so the rest of the page
// (and any open chat) is left alone. the saved impact from the backend replaces the old one
async function toggleRejected(button, detailsById, onAuthLost) {
    const card = button.closest("[data-itinerary]");
    const details = detailsById.get(Number(card.dataset.itinerary));
    const errorMessage = card.querySelector("[data-impact-error]");
    const impactId = Number(button.dataset.impact);
    const rejected = button.dataset.reject === "true";

    button.disabled = true;
    errorMessage.textContent = "";

    try {
        const saved = await api.setImpactRejected(impactId, rejected);
        details.impacts = details.impacts.map(impact => impact.id === impactId ? saved : impact);
    } catch (error) {
        button.disabled = false;
        if (error.status === 401) return onAuthLost();
        errorMessage.textContent = error.message;
        return;
    }

    card.outerHTML = createItineraryCard(details);

    // the button that was clicked is gone, so keep keyboard users in the same card instead of at the top of the page
    const redrawnCard = document.querySelector(`[data-itinerary="${details.itinerary.id}"]`);
    redrawnCard?.querySelector("summary")?.focus();
}

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
    const detailsById = new Map();   // itinerary id -> what its card was drawn from, so one card can be redrawn

    try {
        // the backend sends an empty body instead of [] when the user has no itineraries
        const itineraries = (await api.itineraries(user.id)) ?? [];
        const itinerariesWithDetails = await Promise.all(itineraries.map(loadDetails));
        itinerariesWithDetails.forEach(details => detailsById.set(Number(details.itinerary.id), details));

        const startTime = details => new Date(details.itinerary.dateStart);
        const upcoming = itinerariesWithDetails.filter(details => !isTripOver(details.itinerary))
            .sort((first, second) => startTime(first) - startTime(second));
        const past = itinerariesWithDetails.filter(details => isTripOver(details.itinerary))
            .sort((first, second) => startTime(second) - startTime(first));   // most recent first

        itineraryListContainer.className = "";
        itineraryListContainer.innerHTML = itinerariesWithDetails.length
            ? section("Upcoming", upcoming.map(createItineraryCard)) + section("Past", past.map(createItineraryCard))
            : `<div class="msg">You have no itineraries yet. <a href="#/itineraries/new">Create your first one</a></div>`;
    } catch (error) {
        return showError(itineraryListContainer, error, onAuthLost);
    }

    // Event delegation: an assistant button opens the side panel for that itinerary,
    // and a "Not relevant" or "Restore" button changes one impact
    itineraryListContainer.addEventListener("click", (event) => {
        const impactButton = event.target.closest("[data-impact]");
        if (impactButton) return toggleRejected(impactButton, detailsById, onAuthLost);

        const chatButton = event.target.closest("[data-chat]");
        if (!chatButton) return;

        const itinerary = {
            id: Number(chatButton.dataset.chat),
            name: chatButton.dataset.chatName,   // the browser decodes the escaped attribute back to plain text
        };
        openChat(itinerary, onAuthLost);
    });
}
