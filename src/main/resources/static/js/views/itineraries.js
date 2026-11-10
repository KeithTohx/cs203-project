import { api } from "../api.js";
import {
    dateRange, emptyState, escapeHtml, flagFor, formatDate, hostnameOf, newsTitleOf, safeUrl, showError, sortByStart, timeTag,
} from "../util.js";
import { openChat } from "./chat.js";
import { icon } from "../icons.js";

// group impacts by the news event that caused them, so each event lists the activities it affects
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

// each bubble needs its own id so aria-describedby points at the right popover
let nextPopoverId = 0;

// component: a small bubble naming where the news came from. hovering or focusing it shows more
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

// component: the button that lets the user overrule the model. on a dismissed impact it puts the impact back
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

// component: one news event and what it affects. an impact with no activity applies to the whole itinerary.
// dismissed events are the ones the user marked as not relevant, shown muted so they can be restored
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
                ${isDismissed ? "" : icon("warning")}
                <strong>${escapeHtml(newsTitleOf(event.title))}</strong>
                ${createSourceBubble(event)}
            </div>
            ${wholeItineraryLines}
            ${activitySection}
        </div>
    `;
};

// component: news the user marked as not relevant, closed by default so it stays out of the way
const createDismissedSection = (dismissedEvents) => {
    if (!dismissedEvents.length) return "";

    return `
        <details data-section="dismissed">
            <summary>Marked as not relevant (${dismissedEvents.length})</summary>
            ${dismissedEvents.map(event => createEventCard(event, true)).join("")}
        </details>
    `;
};

// what the backend's Severity and Action values read as on the page
const SEVERITY_LABELS = {
    HIGH: { text: "High impact", tagClass: "tag bad" },
    MEDIUM: { text: "Medium impact", tagClass: "tag warn" },
    LOW: { text: "Low impact", tagClass: "tag ok" },
};
const ACTION_LABELS = {
    REPLACE: "Swap activity",
    RELOCATE: "Change location",
    RESCHEDULE: "Reschedule",
    MONITOR: "Watch for updates",
    CANCEL: "Cancel activity",
};

// component: the place suggested instead, if the recommendation named one
const createSuggestedPlace = recommendation => {
    if (!recommendation.optionName) return "";

    const where = [recommendation.optionArea, recommendation.optionAddress].filter(Boolean).map(escapeHtml).join(" · ");
    return `
        <p class="suggested-place">
            <span class="meta">Instead:</span>
            <strong>${escapeHtml(recommendation.optionName)}</strong>
            ${where ? `<span class="meta">${where}</span>` : ""}
        </p>
    `;
};

// component: one recommendation, for one activity or for the whole trip
const createRecommendation = recommendation => {
    const severity = SEVERITY_LABELS[recommendation.severity] ?? { text: recommendation.severity, tagClass: "tag" };
    const action = ACTION_LABELS[recommendation.action] ?? recommendation.action;
    // cancelling is the most drastic advice, so it stands out like a high impact does
    const actionTagClass = recommendation.action === "CANCEL" ? "tag bad" : "tag";
    const target = recommendation.activityName ?? "The whole itinerary";

    return `
        <li class="advice">
            <div class="advice-head">
                <strong>${escapeHtml(target)}</strong>
                <span class="${severity.tagClass}">${escapeHtml(severity.text)}</span>
                <span class="${actionTagClass}">${escapeHtml(action)}</span>
            </div>
            <p>${escapeHtml(recommendation.advice)}</p>
            ${createSuggestedPlace(recommendation)}
            ${recommendation.newsTitle ? `<p class="meta">Because of: ${escapeHtml(recommendation.newsTitle)}</p>` : ""}
        </li>
    `;
};

// component: the button that asks the AI for impacts without a recommendation.
// while a request is going it stays disabled, even if the card is redrawn meanwhile
const createSuggestButton = (missingCount, hasSome, isSuggesting) => {
    if (isSuggesting) {
        return `
            <button class="small" type="button" data-suggest disabled>Working it out…</button>
            <span class="meta" data-suggest-status role="status">This can take a few seconds for each activity.</span>
        `;
    }

    const label = hasSome ? `Suggest what to do for the rest (${missingCount})` : "Suggest what to do";

    return `
        <button class="small" type="button" data-suggest>${icon("sparkle")}${label}</button>
        <span class="meta" data-suggest-status role="status"></span>
    `;
};

// shared by the card and the summary, so the two always agree
const planFor = (impactedOnly, recommendations) => {
    const currentImpactIds = new Set(impactedOnly.map(impact => impact.id));
    const current = recommendations.filter(recommendation => currentImpactIds.has(recommendation.impactId));
    const recommendedImpactIds = new Set(current.map(recommendation => recommendation.impactId));
    const missingCount = impactedOnly.filter(impact => !recommendedImpactIds.has(impact.id)).length;
    return { current, missingCount };
};

// what the user rejected no longer counts
const stillImpacted = impacts => impacts.filter(impact => impact.impacted && !impact.rejected);

// component: what to do about the news, rejected impacts hide their advice
const createPlanSection = (impactedOnly, recommendations, isPast, isSuggesting) => {
    if (!impactedOnly.length) return "";

    const { current, missingCount } = planFor(impactedOnly, recommendations);

    // a trip that is over has nothing left to change
    const canSuggest = missingCount > 0 && !isPast;
    const suggestButton = canSuggest ? createSuggestButton(missingCount, current.length > 0, isSuggesting) : "";

    // the button sits beside the empty message, or after the list it adds to
    const body = current.length
        ? `
            <ul class="advice-list">${current.map(createRecommendation).join("")}</ul>
            ${suggestButton ? `<div class="suggest-row">${suggestButton}</div>` : ""}
        `
        : `
            <div class="suggest-row">
                <p class="meta">No suggestions yet.</p>
                ${suggestButton}
            </div>
        `;

    return `
        <details data-section="plan" open>
            <summary>Recommended actions (${current.length})</summary>
            ${body}
        </details>
    `;
};

// component: the activities in one itinerary as a timeline, a red dot marks those hit by news.
// editing happens on the itinerary's edit page
const createActivityList = (itineraryId, activities, impactedActivityIds) => {
    const addLink = `<a class="btn small add" href="#/itineraries/${itineraryId}/activities/new">${icon("plus")}Add activity</a>`;
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

// "2 activities", "1 activity". the counts in the summary read as sentences
const countOf = (count, singular, plural = `${singular}s`) => `${count} ${count === 1 ? singular : plural}`;

// what one upcoming trip needs, worked out from the same data its card is drawn from
const tripAttention = details => {
    const impactedOnly = stillImpacted(details.impacts);
    const { current, missingCount } = planFor(impactedOnly, details.recommendations);
    const affectedActivities = new Set(impactedOnly.map(impact => impact.activityId).filter(id => id != null));
    const wholeTripAffected = impactedOnly.some(impact => impact.activityId == null);

    return {
        details,
        isAffected: impactedOnly.length > 0,
        activityCount: affectedActivities.size,
        wholeTripAffected,
        suggestionCount: current.length,
        waitingCount: missingCount,
    };
};

// "2 activities and the whole trip affected · 1 waiting for a suggestion"
const describeTrip = trip => {
    const hit = [];
    if (trip.activityCount) hit.push(countOf(trip.activityCount, "activity", "activities"));
    if (trip.wholeTripAffected) hit.push("the whole trip");

    const parts = [`${hit.join(" and ")} affected`];
    if (trip.suggestionCount) parts.push(`${countOf(trip.suggestionCount, "suggestion")} ready`);
    if (trip.waitingCount) parts.push(`${trip.waitingCount} waiting for a suggestion`);
    return parts.join(" · ");
};

// component: what needs attention across the upcoming trips
const createTripSummary = detailsList => {
    const upcoming = detailsList.filter(details => !details.failed && !isTripOver(details.itinerary));
    if (!upcoming.length) return "";

    const trips = upcoming.map(tripAttention);
    const affected = trips.filter(trip => trip.isAffected);

    if (!affected.length) {
        const message = upcoming.length === 1
            ? "Your upcoming trip is not affected by the news right now."
            : `None of your ${upcoming.length} upcoming trips are affected by the news right now.`;
        return `
            <section class="card trip-summary" aria-labelledby="trip-summary-title">
                <h2 id="trip-summary-title">
                    At a glance
                    <span class="tag ok">All clear</span>
                </h2>
                <p class="meta">${message}</p>
            </section>
        `;
    }

    const suggestionTotal = affected.reduce((total, trip) => total + trip.suggestionCount, 0);
    const waitingTotal = affected.reduce((total, trip) => total + trip.waitingCount, 0);
    // how many trips are hit goes in the red tag, the rest of the totals sit quietly under the heading
    const affectedLabel = `${affected.length} of ${countOf(upcoming.length, "upcoming trip")} affected`;
    const totals = [`${countOf(suggestionTotal, "suggestion")} ready`];
    if (waitingTotal) totals.push(`${waitingTotal} waiting for a suggestion`);

    // soonest trip first, the same order as the cards below
    const tripLines = affected.map(trip => `
        <li>
            <button class="link-button" type="button" data-jump="${Number(trip.details.itinerary.id)}">
                ${escapeHtml(trip.details.itinerary.name)}
            </button>
            <span class="meta">${escapeHtml(describeTrip(trip))}</span>
        </li>
    `).join("");

    return `
        <section class="card trip-summary" aria-labelledby="trip-summary-title">
            <h2 id="trip-summary-title">
                At a glance
                <span class="tag bad">${affectedLabel}</span>
            </h2>
            <p class="meta">${totals.join(" · ")}</p>
            <ul>
                ${tripLines}
            </ul>
        </section>
    `;
};

// "Happening now", "Tomorrow", "In 3 days", "In 6 weeks", counted in whole calendar days.
// empty when the start date is missing or unreadable, since format(NaN) would throw
const days = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
const countdown = itinerary => {
    const start = new Date(itinerary.dateStart ?? NaN);
    if (Number.isNaN(start.getTime())) return "";

    const today = new Date();
    today.setHours(0, 0, 0, 0);
    start.setHours(0, 0, 0, 0);
    const daysAway = Math.round((start - today) / 86_400_000);

    if (daysAway <= 0) return "Happening now";

    // days for the next two weeks, then weeks up to two months, then months
    let phrase;
    if (daysAway < 14) {
        phrase = days.format(daysAway, "day");
    } else if (daysAway < 60) {
        phrase = days.format(Math.round(daysAway / 7), "week");
    } else {
        phrase = days.format(Math.round(daysAway / 30), "month");
    }
    return phrase.charAt(0).toUpperCase() + phrase.slice(1);
};

const flagOrPin = countryName => {
    const flag = flagFor(countryName);
    return flag ? `<span class="flag" aria-hidden="true">${flag}</span>` : icon("mapPin");
};

// component: the soonest upcoming trip, with how long until it starts
const createNextTrip = detailsList => {
    const next = detailsList
        .filter(details => !isTripOver(details.itinerary))
        .sort((first, second) => new Date(first.itinerary.dateStart) - new Date(second.itinerary.dateStart))[0];
    if (!next) return "";

    const itinerary = next.itinerary;
    const tripCountdown = countdown(itinerary);
    return `
        <section class="card side-card" aria-labelledby="next-trip-title">
            <h2 id="next-trip-title">Next trip</h2>
            ${tripCountdown ? `<p class="next-trip-countdown">${escapeHtml(tripCountdown)}</p>` : ""}
            <p>
                <button class="link-button" type="button" data-jump="${Number(itinerary.id)}">${escapeHtml(itinerary.name)}</button>
            </p>
            <p class="meta trip-meta">
                <span>${flagOrPin(itinerary.countryName)}${escapeHtml(itinerary.countryName)}</span>
                <span>${icon("calendar")}${dateRange(itinerary.dateStart, itinerary.dateEnd)}</span>
            </p>
        </section>
    `;
};

// component: the newest news that still affects an upcoming trip, each story once
const createLatestNews = detailsList => {
    const stories = new Map();
    for (const details of detailsList.filter(item => !item.failed && !isTripOver(item.itinerary))) {
        for (const impact of stillImpacted(details.impacts)) {
            if (stories.has(impact.newsId)) continue;
            stories.set(impact.newsId, { title: impact.newsTitle, publishedAt: impact.newsPublishedAt, trip: details.itinerary });
        }
    }
    const newest = [...stories.values()]
        .sort((first, second) => new Date(second.publishedAt ?? 0) - new Date(first.publishedAt ?? 0))
        .slice(0, 3);
    if (!newest.length) return "";

    const items = newest.map(story => `
        <li>
            <strong>${escapeHtml(newsTitleOf(story.title))}</strong>
            <span class="meta">
                ${timeTag(story.publishedAt)} ·
                <button class="link-button" type="button" data-jump="${Number(story.trip.id)}">${escapeHtml(story.trip.name)}</button>
            </span>
        </li>
    `).join("");

    return `
        <section class="card side-card" aria-labelledby="latest-news-title">
            <h2 id="latest-news-title">Latest news affecting you</h2>
            <ul class="side-news">${items}</ul>
            <a href="#/news">${icon("news")}All news</a>
        </section>
    `;
};

// how many of a trip's admin changes its card lists, the rest are on the alerts page
const ADMIN_CHANGES_SHOWN = 3;

// component: says an admin changed this trip or its activities, and lists what they changed.
// adminChanges are this trip's own, newest first, the same ones the alerts show
const createAdminEditNote = (itinerary, adminChanges = []) => {
    const lastChanged = itinerary.adminEditedAt ?? adminChanges[0]?.createdAt;
    if (!lastChanged) return "";

    const shown = adminChanges.slice(0, ADMIN_CHANGES_SHOWN);
    const changeList = shown.length
        ? `
            <ul class="meta admin-edit-list">
                ${shown.map(change => `<li>${escapeHtml(change.message)} ${timeTag(change.createdAt)}</li>`).join("")}
            </ul>
        `
        : "";
    const linkText = adminChanges.length > shown.length ? "See every change" : "See it in your alerts";

    return `
        <div class="admin-edit-note">
            <p class="meta">
                ${icon("pencil")}Changed by an admin ${timeTag(lastChanged)}.
                <a href="#/alerts">${linkText}</a>
            </p>
            ${changeList}
        </div>
    `;
};

// component: one itinerary card
const createItineraryCard = ({ itinerary, activities, impacts, recommendations, adminChanges, failed, isSuggesting }) => {
    const itineraryId = Number(itinerary.id);
    const isPast = isTripOver(itinerary);
    const tripCountdown = countdown(itinerary);
    // what the user rejected no longer counts as impacted, but is kept in its own section so it can be restored
    const impactedOnly = stillImpacted(impacts);
    const dismissed = impacts.filter(impact => impact.impacted && impact.rejected);
    const impactedActivityIds = new Set(impactedOnly.map(impact => impact.activityId).filter(id => id != null));
    const events = groupByEvent(impactedOnly);
    const dismissedEvents = groupByEvent(dismissed);

    // a past trip says so, an upcoming one shows its countdown, green once it has started
    let timingTag = "";
    if (isPast) {
        timingTag = `<span class="tag">Past</span>`;
    } else if (tripCountdown) {
        const tagClass = tripCountdown === "Happening now" ? "tag ok" : "tag";
        timingTag = `<span class="${tagClass}">${escapeHtml(tripCountdown)}</span>`;
    }

    const body = failed
        ? `<div class="msg err" role="alert">Couldn't load the details of this itinerary.</div>`
        : `
            <h3>Activities</h3>
            ${createActivityList(itineraryId, activities, impactedActivityIds)}
            <details data-section="news"${events.length ? " open" : ""}>
                <summary>News affecting this itinerary (${events.length})</summary>
                ${events.map(event => createEventCard(event)).join("") || `<div class="msg">No news has affected this itinerary.</div>`}
            </details>
            ${createPlanSection(impactedOnly, recommendations, isPast, isSuggesting)}
            ${createDismissedSection(dismissedEvents)}
            <p class="err" data-impact-error role="alert"></p>`;

    return `
        <article class="card ${isPast ? "past" : ""}" data-itinerary="${itineraryId}" tabindex="-1">
            <div class="row">
                <h2>
                    ${escapeHtml(itinerary.name)}
                    ${timingTag}
                    ${impactedOnly.length ? `<span class="tag bad">Impacted</span>` : ""}
                </h2>
                <a class="btn" href="#/itineraries/${itineraryId}/edit" aria-label="Edit ${escapeHtml(itinerary.name)}">${icon("pencil")}Edit</a>
            </div>
            <p class="meta trip-meta">
                <span>${flagOrPin(itinerary.countryName)}${escapeHtml(itinerary.countryName)}</span>
                <span>${icon("calendar")}${dateRange(itinerary.dateStart, itinerary.dateEnd)}</span>
            </p>
            ${createAdminEditNote(itinerary, adminChanges)}
            ${body}
            <p>
                <button class="ghost" data-chat="${itineraryId}" data-chat-name="${escapeHtml(itinerary.name)}" aria-haspopup="dialog">
                    ${icon("chat")}Ask the AI assistant
                </button>
            </p>
        </article>
    `;
};

// recommendations are extra, so if they fail to load the card still shows, just without them
async function loadRecommendations(itineraryId) {
    try {
        return (await api.itineraryRecommendations(itineraryId)) ?? [];
    } catch (error) {
        if (error.status === 401) throw error;
        return [];
    }
}

// fetches activities, impacts and recommendations for one itinerary. a 401 is rethrown, other errors only affect this card
async function loadDetails(itinerary) {
    try {
        const [activities, impacts, recommendations] = await Promise.all([
            api.itineraryActivities(itinerary.id),
            api.itineraryImpacts(itinerary.id),
            loadRecommendations(itinerary.id),
        ]);
        return { itinerary, activities: activities ?? [], impacts: impacts ?? [], recommendations };
    } catch (error) {
        if (error.status === 401) throw error;
        return { itinerary, activities: [], impacts: [], recommendations: [], failed: true };
    }
}

const section = (title, cards) => cards.length ? `<h2 class="section">${title}</h2>${cards.join("")}` : "";

// redraws one card, leaving the rest of the page and any open chat alone
function redrawCard(card, details, clickedButton) {
    // redrawing resets every section to its default, so note which ones the user had open first.
    // that way restoring several in a row does not mean reopening the list each time
    const openSections = [...card.querySelectorAll("details[data-section]")]
        .filter(section => section.open)
        .map(section => section.dataset.section);
    const clickedSection = clickedButton.closest("details[data-section]")?.dataset.section;

    // suggestions take a while, so the user may be typing in the chat by now and should keep their place there.
    // focus is only moved when it was in this card, or was lost along with the disabled button
    const focused = document.activeElement;
    const focusWasHere = !focused || focused === document.body || card.contains(focused);

    card.outerHTML = createItineraryCard(details);

    // a section that is now empty is not drawn at all, so it simply stays gone
    const redrawnCard = document.querySelector(`[data-itinerary="${details.itinerary.id}"]`);
    for (const name of openSections) {
        const section = redrawnCard.querySelector(`details[data-section="${name}"]`);
        if (section) section.open = true;
    }

    if (!focusWasHere) return;

    // the button that was clicked is gone, so keep keyboard users in the section they were working in,
    // or the news section if that one has just emptied
    const clickedSummary = redrawnCard.querySelector(`details[data-section="${clickedSection}"] > summary`);
    const fallbackSummary = redrawnCard.querySelector("summary");
    (clickedSummary ?? fallbackSummary)?.focus();
}

// the card as it is on the page now. another action on it may have redrawn it while this one was waiting.
// null once the user has left the page, even if they have since opened a new itineraries page
const currentCardFor = (list, itineraryId) => {
    if (!list.isConnected) return null;
    return list.querySelector(`[data-itinerary="${itineraryId}"]`);
};

// rejects or restores one impact
async function toggleRejected(button, detailsById, onAuthLost, onChange) {
    const list = button.closest("#itinerary-list");
    const itineraryId = Number(button.closest("[data-itinerary]").dataset.itinerary);
    const details = detailsById.get(itineraryId);
    const impactId = Number(button.dataset.impact);
    const rejected = button.dataset.reject === "true";

    button.disabled = true;
    currentCardFor(list, itineraryId).querySelector("[data-impact-error]").textContent = "";

    try {
        const saved = await api.setImpactRejected(impactId, rejected);
        details.impacts = details.impacts.map(impact => impact.id === impactId ? saved : impact);
    } catch (error) {
        button.disabled = false;
        if (error.status === 401) return onAuthLost();

        const card = currentCardFor(list, itineraryId);
        if (card) card.querySelector("[data-impact-error]").textContent = error.message;
        return;
    }

    const card = currentCardFor(list, itineraryId);
    if (!card) return;
    redrawCard(card, details, button);
    onChange();
}

// asks the AI for impacts without a recommendation, which takes a few seconds each
async function suggestPlans(button, detailsById, onAuthLost, onChange) {
    const list = button.closest("#itinerary-list");
    const itineraryId = Number(button.closest("[data-itinerary]").dataset.itinerary);
    const details = detailsById.get(itineraryId);
    const card = currentCardFor(list, itineraryId);

    // kept on the details rather than only on this button, so a redraw meanwhile keeps it disabled
    details.isSuggesting = true;
    button.disabled = true;
    button.textContent = "Working it out…";
    card.querySelector("[data-suggest-status]").textContent = "This can take a few seconds for each activity.";
    card.querySelector("[data-impact-error]").textContent = "";

    let generated;
    let failure = null;
    try {
        generated = (await api.generateRecommendations(details.itinerary.id)) ?? [];
    } catch (error) {
        failure = error;
    }
    details.isSuggesting = false;

    if (failure?.status === 401) return onAuthLost();

    if (!failure) {
        // generate only sends the current ones, so keep stored ones for rejected impacts in case they are restored
        const byId = new Map(details.recommendations.map(recommendation => [recommendation.id, recommendation]));
        for (const recommendation of generated) byId.set(recommendation.id, recommendation);
        details.recommendations = [...byId.values()];
    }

    // generating is slow, so the user may well have left the page by now
    const cardNow = currentCardFor(list, itineraryId);
    if (!cardNow) return;
    redrawCard(cardNow, details, button);

    if (failure) {
        currentCardFor(list, itineraryId).querySelector("[data-impact-error]").textContent = failure.message;
        return;
    }
    onChange();
}

// scrolls to one trip's card and moves focus there, for keyboard and screen reader users
function showCard(container, itineraryId) {
    const card = container.querySelector(`[data-itinerary="${Number(itineraryId)}"]`);
    card?.scrollIntoView({ behavior: "smooth", block: "start" });
    card?.focus({ preventScroll: true });
    return card;
}

// set when the page opens for one trip, eg from an alert
export async function showItineraries(root, user, onAuthLost, tripId = null) {
    root.innerHTML = `
        <div class="row page-head">
            <h1>Your itineraries</h1>
            <a class="btn primary" href="#/itineraries/new">${icon("plus")}New itinerary</a>
        </div>
        <div class="dashboard">
            <div class="dashboard-main">
                <div id="itinerary-list" class="msg">Loading…</div>
            </div>
            <aside class="dashboard-side" aria-label="Trip overview">
                <div id="next-trip" class="side-extra"></div>
                <div id="trip-summary" aria-live="polite"></div>
                <div id="latest-news" class="side-extra"></div>
            </aside>
        </div>
    `;

    const itineraryListContainer = root.querySelector("#itinerary-list");
    const sidebar = root.querySelector(".dashboard-side");
    const summaryContainer = root.querySelector("#trip-summary");
    const detailsById = new Map();   // itinerary id -> what its card was drawn from, so one card can be redrawn

    // redrawn after a card changes, so rejecting news or getting suggestions updates the counts too
    const drawSummary = () => {
        const detailsList = [...detailsById.values()];
        root.querySelector("#next-trip").innerHTML = createNextTrip(detailsList);
        summaryContainer.innerHTML = createTripSummary(detailsList);
        root.querySelector("#latest-news").innerHTML = createLatestNews(detailsList);
    };

    try {
        // the backend sends an empty body instead of [] when the user has no itineraries.
        // admin changes are only extra, so a failed load just leaves the cards without them
        const [itineraries, adminChangePage] = await Promise.all([
            api.itineraries(user.id),
            api.adminChanges().catch(error => {
                if (error.status === 401) throw error;
                return null;
            }),
        ]);
        const allAdminChanges = adminChangePage?.content ?? [];
        const changesOn = itinerary => allAdminChanges.filter(change => change.itineraryExists
            && Number(change.itineraryId) === Number(itinerary.id));

        const itinerariesWithDetails = await Promise.all((itineraries ?? []).map(async itinerary => {
            const details = await loadDetails(itinerary);
            return { ...details, adminChanges: changesOn(itinerary) };
        }));
        itinerariesWithDetails.forEach(details => detailsById.set(Number(details.itinerary.id), details));

        const startTime = details => new Date(details.itinerary.dateStart);
        const upcoming = itinerariesWithDetails.filter(details => !isTripOver(details.itinerary))
            .sort((first, second) => startTime(first) - startTime(second));
        const past = itinerariesWithDetails.filter(details => isTripOver(details.itinerary))
            .sort((first, second) => startTime(second) - startTime(first));   // most recent first

        itineraryListContainer.className = "";
        itineraryListContainer.innerHTML = itinerariesWithDetails.length
            ? section("Upcoming", upcoming.map(createItineraryCard)) + section("Past", past.map(createItineraryCard))
            : emptyState({
                iconHtml: icon("map"),
                title: "No trips yet",
                text: "Plan a trip and TripSense will watch the news for anything that could affect it.",
                action: `<a class="btn primary" href="#/itineraries/new">${icon("plus")}Plan your first trip</a>`,
            });
        drawSummary();
    } catch (error) {
        return showError(itineraryListContainer, error, onAuthLost);
    }

    // event delegation: an assistant button opens the side panel for that itinerary,
    // a "Not relevant" or "Restore" button changes one impact, and "Suggest what to do" asks the AI
    itineraryListContainer.addEventListener("click", (event) => {
        const impactButton = event.target.closest("[data-impact]");
        if (impactButton) return toggleRejected(impactButton, detailsById, onAuthLost, drawSummary);

        const suggestButton = event.target.closest("[data-suggest]");
        if (suggestButton) return suggestPlans(suggestButton, detailsById, onAuthLost, drawSummary);

        const chatButton = event.target.closest("[data-chat]");
        if (!chatButton) return;

        const itinerary = {
            id: Number(chatButton.dataset.chat),
            name: chatButton.dataset.chatName,   // the browser decodes the escaped attribute back to plain text
        };
        openChat(itinerary, onAuthLost);
    });

    // a trip name in the sidebar scrolls to its card
    sidebar.addEventListener("click", event => {
        const jumpButton = event.target.closest("[data-jump]");
        if (jumpButton) showCard(itineraryListContainer, jumpButton.dataset.jump);
    });

    // a deleted trip, or someone else's, has no card, so say so
    if (tripId != null && !showCard(itineraryListContainer, tripId)) {
        root.querySelector(".dashboard").insertAdjacentHTML("beforebegin",
            `<p class="err" role="alert">That itinerary could not be found. It may have been deleted.</p>`);
    }
}
