import { api } from "../api.js";
import {
    dateRange, emptyState, escapeHtml, flagFor, formatDate, formatDateTime, hostnameOf, newsTitleOf, safeUrl, showError, sortByStart, timeTag,
} from "../util.js";
import { openChat } from "./chat.js";
import { icon } from "../icons.js";

// group impacts by the news event that caused them, so each event lists the activities it affects
const groupByEvent = (impacts) => {
    const events = new Map();
    for (const impact of impacts) {
        // by headline, not by id: the same story arrives from many outlets as separate rows
        const key = impact.newsTitle ?? impact.newsId;
        if (!events.has(key)) {
            events.set(key, {
                title: impact.newsTitle,
                source: impact.newsSource,
                url: impact.newsUrl,
                description: impact.newsDescription,
                publishedAt: impact.newsPublishedAt,
                copies: 0,
                impacts: [],
                byTarget: new Map(),
            });
        }
        const event = events.get(key);
        event.copies += 1;
        event.impacts.push(impact);
        // one line per affected thing, carrying every copy's id so one click clears them all
        const target = impact.activityId ?? "trip";
        if (!event.byTarget.has(target)) event.byTarget.set(target, { ...impact, ids: [] });
        event.byTarget.get(target).ids.push(impact.id);
    }
    return [...events.values()];
};

// a trip can be hit by hundreds of stories, so long lists come a page at a time
const PAGE = 8;

const pageOf = (items, shown) => items.slice(0, shown ?? PAGE);

// component: the row that asks for the next page, with how much is still hidden
const createMoreButton = (items, shown, kind) => {
    const rest = items.length - (shown ?? PAGE);
    if (rest <= 0) return "";

    return `
        <p class="more-row">
            <button class="ghost small" type="button" data-more="${kind}">Show ${Math.min(rest, PAGE)} more</button>
            <span class="meta">${rest} not shown</span>
        </p>
    `;
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
// rejected carries three states: null is untouched, false is "this affects me", true is
// "not relevant". only a confirmed one is worth asking the model about
const isConfirmed = impact => impact.rejected === false;

const createRejectButton = (impact, isDismissed) => {
    const target = impact.activityName ?? "the whole itinerary";
    const ids = (impact.ids ?? [impact.id]).map(Number).join(",");

    const button = (reject, label, kind, description, pressed = false) => `
        <button class="${kind} small impact-action" type="button"
                data-impact="${ids}" data-reject="${reject}"
                aria-pressed="${pressed}"
                aria-label="${escapeHtml(description)}">${label}</button>
    `;

    const notRelevant = button(true, "Not relevant", "reject", `Mark news impact on ${target} as not relevant`);

    const group = inner => `<span class="impact-actions">${inner}</span>`;

    if (isDismissed) {
        return group(button(false, "Restore", "", `Restore news impact on ${target}`));
    }
    // both states are real buttons, the tick is what says which one is on. a tag here read as a
    // dead button sitting next to a live one
    const confirmed = isConfirmed(impact);
    return group(button(false, confirmed ? "&#10003; Affects me" : "This affects me", "accept",
            `Confirm the news impact on ${target}`, confirmed)
        + notRelevant);
};

// component: one news event and what it affects. an impact with no activity applies to the whole itinerary.
// dismissed events are the ones the user marked as not relevant, shown muted so they can be restored
const createEventCard = (event, isDismissed = false) => {
    // the model's justification runs a few lines, so it folds away and the list stays scannable
    const reason = impact => impact.reason
        ? `<details class="reason"><summary>Why?</summary><p class="meta">${escapeHtml(impact.reason)}</p></details>`
        : "";
    const lines = [...(event.byTarget?.values() ?? event.impacts)];
    const wholeItineraryImpacts = lines.filter(impact => impact.activityId == null);
    const activityImpacts = lines.filter(impact => impact.activityId != null);

    const wholeItineraryLines = wholeItineraryImpacts.map(impact => `
        <div class="impact-line">
            <span>Affects the whole itinerary</span>
            ${createRejectButton(impact, isDismissed)}
        </div>
        ${reason(impact)}
    `).join("");

    const activityLines = activityImpacts.map(impact => `
        <li>
            <div class="impact-line">
                <span>${escapeHtml(impact.activityName)}</span>
                ${createRejectButton(impact, isDismissed)}
            </div>
            ${reason(impact)}
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
                ${event.copies > 1 ? `<span class="meta">+${event.copies - 1} more ${event.copies === 2 ? "report" : "reports"}</span>` : ""}
            </div>
            ${wholeItineraryLines}
            ${activitySection}
        </div>
    `;
};

// component: news the user marked as not relevant, closed by default so it stays out of the way
const createDismissedSection = (dismissedEvents, shown = {}) => {
    if (!dismissedEvents.length) return "";

    return `
        <details data-section="dismissed">
            <summary>Marked as not relevant (${dismissedEvents.length})</summary>
            ${pageOf(dismissedEvents, shown.dismissed).map(event => createEventCard(event, true)).join("")}
            ${createMoreButton(dismissedEvents, shown.dismissed, "dismissed")}
        </details>
    `;
};

// what the backend's Severity and Action values read as on the page
const SEVERITY_LABELS = {
    HIGH: { text: "High", tagClass: "tag bad", rank: "high" },
    MEDIUM: { text: "Medium", tagClass: "tag warn", rank: "medium" },
    LOW: { text: "Low", tagClass: "tag ok", rank: "low" },
};
const ACTION_LABELS = {
    REPLACE: "Swap activity",
    RELOCATE: "Change location",
    RESCHEDULE: "Reschedule",
    MONITOR: "Watch for updates",
    CANCEL: "Cancel activity",
};
// what an accepted decision looks like on the activity itself. cancelling reads as a loss, the
// rest are changes the traveller still has to make
const DECIDED_LABELS = {
    REPLACE: { text: "Swapped", tagClass: "tag bad" },
    RELOCATE: { text: "Moved", tagClass: "tag warn" },
    RESCHEDULE: { text: "Rescheduled", tagClass: "tag warn" },
    MONITOR: { text: "Watching", tagClass: "tag" },
    CANCEL: { text: "Cancelled", tagClass: "tag bad" },
};

// component: the badge on an activity the traveller has accepted advice for. the headline is
// trimmed because it sits on one line beside the activity name
const createAcceptedBadge = accepted => {
    if (!accepted) return "";

    const label = DECIDED_LABELS[accepted.action] ?? { text: "Changed", tagClass: "tag" };
    const story = newsTitleOf(accepted.newsTitle) ?? "";
    const shortStory = story.length > 42 ? story.slice(0, 41).trimEnd() + "…" : story;

    return `
        <span class="accepted-badge" title="${escapeHtml(story)}">
            <span class="${label.tagClass}">${label.text}</span>
            ${shortStory ? `<span class="meta">due to ${escapeHtml(shortStory)}</span>` : ""}
        </span>
    `;
};

// how a decision reads once it has been made. pending has nothing to show
const STATUS_LABELS = {
    ACCEPTED: { text: "Accepted", tagClass: "tag ok", rowClass: "accepted", decidedWord: "Accepted" },
    DISMISSED: { text: "Dismissed", tagClass: "tag", rowClass: "dismissed", decidedWord: "Dismissed" },
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

// component: what the traveller can do about one piece of advice. every decision can be undone,
// so unlike removing an activity none of these asks for a second click
const createAdviceActions = (recommendation, status, isBusy) => {
    const id = Number(recommendation.id);
    const target = recommendation.activityName ?? "the whole itinerary";
    const busy = isBusy ? " disabled" : "";

    const decide = (nextStatus, label, kind) => `
        <button class="small ${kind}" type="button"
                data-decide="${id}" data-status="${nextStatus}"
                aria-label="${escapeHtml(`${label} the advice for ${target}`)}"${busy}>${label}</button>
    `;

    // a decided one only offers the way back, so the row does not fill up with choices already made
    const buttons = status === "ACCEPTED" ? decide("PENDING", "Undo", "ghost")
        : status === "DISMISSED" ? decide("PENDING", "Restore", "ghost")
        : decide("ACCEPTED", "Accept", "accept") + decide("DISMISSED", "Dismiss", "reject") + `
            <button class="retry small" type="button" data-regenerate="${id}"
                    aria-label="${escapeHtml(`Ask for different advice for ${target}`)}"${busy}>
                ${isBusy ? "Working…" : "Try another"}
            </button>
        `;

    return `<div class="advice-actions">${buttons}</div>`;
};

// component: one recommendation, for one activity or for the whole trip
const createRecommendation = (recommendation, busyImpactIds) => {
    const severity = SEVERITY_LABELS[recommendation.severity] ?? { text: recommendation.severity, tagClass: "tag", rank: "low" };
    const action = ACTION_LABELS[recommendation.action] ?? recommendation.action;
    const status = recommendation.status ?? "PENDING";
    const decided = STATUS_LABELS[status];
    // the heading is the thing to do. the story is what prompted it, so it reads underneath
    const headline = newsTitleOf(recommendation.newsTitle);

    return `
        <li class="advice sev-${severity.rank} ${decided?.rowClass ?? ""}">
            <div class="advice-top">
                <div class="advice-main">
                    <p class="advice-headline ${recommendation.action === "CANCEL" ? "drastic" : ""}">${escapeHtml(action)}</p>
                    <p class="advice-tags">
                        <span class="${severity.tagClass}">${escapeHtml(severity.text)}</span>
                        ${decided ? `<span class="${decided.tagClass}">${decided.text}</span>` : ""}
                        ${recommendation.stale ? `<span class="tag warn">Outdated</span>` : ""}
                    </p>
                    <p class="advice-body">${escapeHtml(recommendation.advice)}</p>
                    <p class="meta advice-source">Prompted by: ${escapeHtml(headline)}</p>
                    ${createSuggestedPlace(recommendation)}
                    ${decided && recommendation.decidedAt
                        ? `<p class="meta">${decided.decidedWord} on ${escapeHtml(formatDateTime(recommendation.decidedAt))}</p>`
                        : ""}
                </div>
                ${createAdviceActions(recommendation, status, busyImpactIds.has(recommendation.impactId))}
            </div>
        </li>
    `;
};

// every recommendation hangs off one impact, so this is how a story finds its own advice
const adviceByImpact = current => {
    const byImpact = new Map();
    for (const recommendation of current) {
        if (!byImpact.has(recommendation.impactId)) byImpact.set(recommendation.impactId, []);
        byImpact.get(recommendation.impactId).push(recommendation);
    }
    return byImpact;
};

// component: one news story and whatever advice came out of it, so the pair always reads together
const createNewsRow = (event, impact, advice, busyImpactIds) => `
    <li>
        <div class="impact-line">
            <span class="activity-news-head">
                ${icon("warning")}<strong>${escapeHtml(newsTitleOf(event.title))}</strong>
                ${createSourceBubble(event)}
                ${impact.ids.length > 1 ? `<span class="meta">+${impact.ids.length - 1} more</span>` : ""}
            </span>
            ${createRejectButton(impact, false)}
        </div>
        ${impact.reason
            ? `<details class="reason"><summary>Why?</summary><p class="meta">${escapeHtml(impact.reason)}</p></details>`
            : ""}
        ${isConfirmed(impact) && advice.length
            ? `<ul class="advice-list paired">${advice.map(recommendation =>
                    createRecommendation(recommendation, busyImpactIds)).join("")}</ul>`
            : ""}
    </li>`;

// component: the news aimed at one activity, or at the trip as a whole. closed by default,
// since a long trip collects dozens of stories and the timeline has to stay readable
const createNewsAccordion = (events, targetKey, byImpact, busyImpactIds, label, shown = {},
                             isPast = false, isSuggesting = false) => {
    const rows = events
        .map(event => ({ event, impact: event.byTarget.get(targetKey) }))
        .filter(({ impact }) => impact);
    if (!rows.length) return "";

    // every copy of a story shares one decision, so advice can hang off any of their ids.
    // advice on news nobody has confirmed stays hidden, so it is not counted either
    const adviceFor = impact => isConfirmed(impact)
        ? impact.ids.flatMap(id => byImpact.get(id) ?? [])
        : [];
    const adviceCount = rows.reduce((total, row) => total + adviceFor(row.impact).length, 0);
    // confirmed here but nothing suggested for it yet. one button covers all of them, since
    // asking is one call for the whole trip anyway
    const waiting = rows.filter(row => isConfirmed(row.impact) && !adviceFor(row.impact).length).length;
    const kind = `news-${targetKey}`;

    return `
        <details class="news-accordion" data-section="${kind}">
            <summary>
                <span class="group-label">${label} (${rows.length})</span>
                ${adviceCount ? `<span class="tag ok">${adviceCount} suggested</span>` : ""}
                ${waiting ? `<span class="tag warn">${waiting} to advise</span>` : ""}
            </summary>
            ${waiting && !isPast ? `<p class="suggest-row">${createSuggestButton(waiting, 0, false, isSuggesting)}</p>` : ""}
            <ul class="activity-news">${pageOf(rows, shown[kind]).map(({ event, impact }) =>
                createNewsRow(event, impact, adviceFor(impact), busyImpactIds)).join("")}</ul>
            ${createMoreButton(rows, shown[kind], kind)}
        </details>
    `;
};

// component: the button that asks the AI for impacts without a recommendation.
// while a request is going it stays disabled, even if the card is redrawn meanwhile
const createSuggestButton = (missingCount, staleCount, hasSome, isSuggesting) => {
    if (isSuggesting) {
        return `
            <button class="small" type="button" data-suggest disabled>Working it out…</button>
            <span class="meta" data-suggest-status role="status">This can take a few seconds for each activity.</span>
        `;
    }

    // the same call redoes outdated advice, so when nothing is missing say that is the job it is doing
    const label = missingCount === 0 ? `Update outdated advice (${staleCount})`
        : hasSome ? `Suggest what to do for the rest (${missingCount})`
        : "Suggest what to do";

    return `
        <button class="small" type="button" data-suggest>${icon("sparkle")}${label}</button>
        <span class="meta" data-suggest-status role="status"></span>
    `;
};

// shared by the card and the summary, so the two always agree
const planFor = (impactedOnly, recommendations) => {
    const currentImpactIds = new Set(impactedOnly.map(impact => impact.id));
    const mine = recommendations.filter(recommendation => currentImpactIds.has(recommendation.impactId));
    // dismissed advice is kept, just out of the main list until it is restored
    const current = mine.filter(recommendation => recommendation.status !== "DISMISSED");
    const dismissed = mine.filter(recommendation => recommendation.status === "DISMISSED");
    // counts dismissed ones as answered, since generating again would only hand back the same row.
    // the way to new advice there is to restore it and ask for another.
    // only impacts the traveller confirmed count: asking about news they have not looked at
    // is what made one press of the button cost hundreds of model calls
    const recommendedImpactIds = new Set(mine.map(recommendation => recommendation.impactId));
    const missingCount = impactedOnly
            .filter(impact => isConfirmed(impact) && !recommendedImpactIds.has(impact.id)).length;
    // written before the traveller last changed their preferences, so it may no longer fit
    const staleCount = current.filter(recommendation => recommendation.stale).length;
    return { current, dismissed, missingCount, staleCount };
};

// what the user rejected no longer counts
const stillImpacted = impacts => impacts.filter(impact => impact.impacted && !impact.rejected);

// one group per thing that is affected: the trip itself, then each activity.
// worst severity first inside a group, and the busiest group first
const WORST_FIRST = { HIGH: 0, MEDIUM: 1, LOW: 2 };

const groupByTarget = recommendations => {
    const groups = new Map();
    for (const recommendation of recommendations) {
        const key = recommendation.activityId ?? "trip";
        if (!groups.has(key)) {
            groups.set(key, { name: recommendation.activityName ?? "The whole trip", items: [] });
        }
        groups.get(key).items.push(recommendation);
    }
    for (const group of groups.values()) {
        group.items.sort((first, second) =>
            (WORST_FIRST[first.severity] ?? 3) - (WORST_FIRST[second.severity] ?? 3));
        group.worst = group.items[0]?.severity ?? "LOW";
    }
    // the trip-wide group always leads, the rest follow by how much is waiting
    return [...groups.entries()]
        .sort(([keyA, a], [keyB, b]) =>
            (keyA === "trip" ? -1 : keyB === "trip" ? 1 : 0) || b.items.length - a.items.length)
        .map(([, group]) => group);
};

// component: every decision the traveller has made on this trip, newest first.
// built from what the card already holds, so opening it costs nothing
const createDecisionLog = recommendations => {
    const decided = recommendations
        .filter(recommendation => recommendation.decidedAt)
        .sort((first, second) => new Date(second.decidedAt) - new Date(first.decidedAt));

    if (!decided.length) return `<p class="meta">You have not accepted or dismissed anything yet.</p>`;

    return `<ol class="decision-log">${decided.map(recommendation => {
        const label = STATUS_LABELS[recommendation.status];
        return `
            <li>
                <span class="${label?.tagClass ?? "tag"}">${label?.decidedWord ?? recommendation.status}</span>
                <span>${escapeHtml(newsTitleOf(recommendation.newsTitle))}</span>
                <span class="meta">${escapeHtml(recommendation.activityName ?? "The whole trip")}
                    · ${escapeHtml(formatDateTime(recommendation.decidedAt))}</span>
            </li>`;
    }).join("")}</ol>`;
};

// component: the button that asks the model for advice. the advice itself is drawn with the
// news it came from, so this section only counts it
const createPlanSection = (impactedOnly, recommendations, isPast, isSuggesting, busyImpactIds, shown = {}) => {
    if (!impactedOnly.length) return "";

    const { current, dismissed, staleCount } = planFor(impactedOnly, recommendations);

    // a trip that is over has nothing left to change. what is merely missing is asked for on the
    // activity it belongs to, so only outdated advice is left for the card to offer
    const canSuggest = staleCount > 0 && !isPast;
    // 0 missing on purpose: anything missing is asked for on its own activity, so this button
    // only ever means redo the outdated ones
    const suggestButton = canSuggest ? createSuggestButton(0, staleCount, current.length > 0, isSuggesting) : "";

    return `
        <section class="plan">
            <div class="row plan-head">
                <h3>Suggestions</h3>
                ${suggestButton ? `<span class="suggest-row">${suggestButton}</span>` : ""}
            </div>
            <p class="meta">${current.length
                ? `${current.length} ${current.length === 1 ? "suggestion sits" : "suggestions sit"} under the news story it came from.`
                : "Confirm the news that affects you, then ask for advice."}</p>
        </section>
        ${createDismissedAdviceSection(dismissed, busyImpactIds, shown)}
    `;
};

// component: advice the traveller turned down, closed by default so it stays out of the way
const createDismissedAdviceSection = (dismissed, busyImpactIds, shown = {}) => {
    if (!dismissed.length) return "";

    const kind = "dismissed-advice";
    return `
        <details data-section="dismissed-advice">
            <summary>Dismissed advice (${dismissed.length})</summary>
            <ul class="advice-list">${pageOf(dismissed, shown[kind]).map(recommendation => createRecommendation(recommendation, busyImpactIds)).join("")}</ul>
            ${createMoreButton(dismissed, shown[kind], kind)}
        </details>
    `;
};

// component: the activities in one itinerary as a timeline, a red dot marks those hit by news.
// editing happens on the itinerary's edit page
const createActivityList = (itineraryId, activities, impactedActivityIds, adviceFor = () => "",
                            acceptedFor = () => null) => {
    const addLink = `<a class="btn small add" href="#/itineraries/${itineraryId}/activities/new">${icon("plus")}Add activity</a>`;
    if (!activities.length) return `<p class="meta">No activities planned yet.</p>${addLink}`;
    return `<ol class="timeline">${sortByStart(activities).map(activity => `
        <li${impactedActivityIds.has(activity.id) ? ` class="hit"` : ""}>
            <div class="activity-head">
                <strong>${escapeHtml(activity.name)}</strong>
                ${createAcceptedBadge(acceptedFor(activity.id))}
            </div>
            <span class="meta">${[escapeHtml(activity.address), dateRange(activity.dateStart, activity.dateEnd)].filter(Boolean).join(" · ")}</span>
            ${adviceFor(activity.id)}
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
export const createTripSummary = detailsList => {
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
export const createNextTrip = detailsList => {
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
export const createLatestNews = detailsList => {
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
const createItineraryCard = ({ itinerary, activities, impacts, recommendations, adminChanges,
                               failed, isSuggesting, busyRecommendations, shown }) => {
    const itineraryId = Number(itinerary.id);
    const shownCounts = shown ?? {};
    const isPast = isTripOver(itinerary);
    const tripCountdown = countdown(itinerary);
    // what the user rejected no longer counts as impacted, but is kept in its own section so it can be restored
    const impactedOnly = stillImpacted(impacts);
    const dismissed = impacts.filter(impact => impact.impacted && impact.rejected);
    const impactedActivityIds = new Set(impactedOnly.map(impact => impact.activityId).filter(id => id != null));
    const events = groupByEvent(impactedOnly);
    const dismissedEvents = groupByEvent(dismissed);
    // worked out once here so every news row can find the advice that came out of it
    const byImpact = adviceByImpact(planFor(impactedOnly, recommendations).current);
    // what the traveller accepted, so the activity can say so without opening anything
    const acceptedByActivity = new Map();
    for (const recommendation of recommendations) {
        if (recommendation.status !== "ACCEPTED" || recommendation.activityId == null) continue;
        const seen = acceptedByActivity.get(recommendation.activityId);
        if (!seen || new Date(recommendation.decidedAt) > new Date(seen.decidedAt)) {
            acceptedByActivity.set(recommendation.activityId, recommendation);
        }
    }

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
            ${createActivityList(itineraryId, activities, impactedActivityIds, activityId =>
                createNewsAccordion(events, activityId, byImpact, busyRecommendations ?? new Set(),
                    "Affected news", shownCounts, isPast, isSuggesting),
                activityId => acceptedByActivity.get(activityId) ?? null)}
            ${createPlanSection(impactedOnly, recommendations, isPast, isSuggesting, busyRecommendations ?? new Set(), shownCounts)}
            ${createNewsAccordion(events, "trip", byImpact, busyRecommendations ?? new Set(),
                "News affecting the whole trip", shownCounts, isPast, isSuggesting)}
            ${createDismissedSection(dismissedEvents, shownCounts)}
            <p class="err" data-impact-error role="alert"></p>`;

    return `
        <article class="card ${isPast ? "past" : ""}" data-itinerary="${itineraryId}" tabindex="-1">
            <div class="row">
                <h2>
                    ${escapeHtml(itinerary.name)}
                    ${timingTag}
                    ${impactedOnly.length ? `<span class="tag bad">Impacted</span>` : ""}
                </h2>
                <div class="heading-actions">
                    <button data-chat="${itineraryId}" data-chat-name="${escapeHtml(itinerary.name)}" aria-haspopup="dialog">
                        ${icon("chat")}Ask the AI assistant
                    </button>
                    <button type="button" data-trip-history="${itineraryId}" aria-haspopup="dialog"
                            aria-label="${escapeHtml(`Decisions you have made on ${itinerary.name}`)}">History</button>
                    <a class="btn" href="#/itineraries/${itineraryId}/edit" aria-label="Edit ${escapeHtml(itinerary.name)}">${icon("pencil")}Edit</a>
                </div>
            </div>
            <p class="meta trip-meta">
                <span>${flagOrPin(itinerary.countryName)}${escapeHtml(itinerary.countryName)}</span>
                <span>${icon("calendar")}${dateRange(itinerary.dateStart, itinerary.dateEnd)}</span>
            </p>
            ${createAdminEditNote(itinerary, adminChanges)}
            ${body}
        </article>
    `;
};

// recommendations are extra, so if they fail to load the card still shows, just without them
async function loadRecommendations(itineraryId) {
    try {
        // dismissed ones come back too, so they can be listed on their own and put back
        return (await api.itineraryRecommendations(itineraryId, true)) ?? [];
    } catch (error) {
        if (error.status === 401) throw error;
        return [];
    }
}

// fetches activities, impacts and recommendations for one itinerary. a 401 is rethrown, other errors only affect this card
export async function loadDetails(itinerary) {
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
    const sameMoreButton = clickedButton.dataset.more
        ? redrawnCard.querySelector(`[data-more="${clickedButton.dataset.more}"]`)
        : null;
    const clickedSummary = redrawnCard.querySelector(`details[data-section="${clickedSection}"] > summary`);
    const fallbackSummary = redrawnCard.querySelector("summary");
    (sameMoreButton ?? clickedSummary ?? fallbackSummary)?.focus();
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
    // the line stands for every copy of that story, so all of them move together
    const impactIds = button.dataset.impact.split(",").map(Number);
    const rejected = button.dataset.reject === "true";

    button.disabled = true;
    currentCardFor(list, itineraryId).querySelector("[data-impact-error]").textContent = "";

    try {
        const saved = await Promise.all(impactIds.map(id => api.setImpactRejected(id, rejected)));
        const byId = new Map(saved.map(impact => [impact.id, impact]));
        details.impacts = details.impacts.map(impact => byId.get(impact.id) ?? impact);
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
    // every accordion that was waiting has its own button and status line
    card.querySelectorAll("[data-suggest-status]").forEach(status => {
        status.textContent = "This can take a few seconds for each activity.";
    });
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

// accepts or dismisses one piece of advice, or puts the decision back to pending
async function decideAdvice(button, detailsById, onAuthLost, onChange) {
    const list = button.closest("#itinerary-list");
    const itineraryId = Number(button.closest("[data-itinerary]").dataset.itinerary);
    const details = detailsById.get(itineraryId);
    const recommendationId = Number(button.dataset.decide);
    const status = button.dataset.status;

    button.disabled = true;
    currentCardFor(list, itineraryId).querySelector("[data-impact-error]").textContent = "";

    try {
        const saved = await api.decideRecommendation(recommendationId, status);
        details.recommendations = details.recommendations.map(recommendation => recommendation.id === recommendationId
            // deciding does not work out whether the advice is outdated, so keep what we already knew
            ? { ...saved, stale: recommendation.stale }
            : recommendation);
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

// asks the model for a different suggestion, which takes as long as generating one did
async function regenerateAdvice(button, detailsById, onAuthLost, onChange) {
    const list = button.closest("#itinerary-list");
    const itineraryId = Number(button.closest("[data-itinerary]").dataset.itinerary);
    const details = detailsById.get(itineraryId);
    const recommendationId = Number(button.dataset.regenerate);
    const previous = details.recommendations.find(recommendation => recommendation.id === recommendationId);

    // kept on the details rather than only on this button, so a redraw meanwhile keeps it disabled
    details.busyRecommendations.add(previous.impactId);
    button.disabled = true;
    button.textContent = "Working it out…";
    currentCardFor(list, itineraryId).querySelector("[data-impact-error]").textContent = "";

    let saved;
    let failure = null;
    try {
        saved = await api.regenerateRecommendation(recommendationId);
    } catch (error) {
        failure = error;
    }
    details.busyRecommendations.delete(previous.impactId);

    if (failure?.status === 401) return onAuthLost();

    if (!failure) {
        // a new attempt is a new row, so swap by impact or the old one would stay beside it
        details.recommendations = details.recommendations
            .filter(recommendation => recommendation.impactId !== saved.impactId)
            .concat(saved);
    }

    // the model is slow, so the user may well have left the page by now
    const cardNow = currentCardFor(list, itineraryId);
    if (!cardNow) return;
    redrawCard(cardNow, details, button);

    if (failure) {
        currentCardFor(list, itineraryId).querySelector("[data-impact-error]").textContent = failure.message;
        return;
    }
    onChange();
}

// one dialog is reused by every card, built the first time history is opened
function historyDialog() {
    let dialog = document.querySelector("dialog.history-panel");
    if (dialog) return dialog;

    document.body.insertAdjacentHTML("beforeend", `
        <dialog class="history-panel" aria-labelledby="history-title">
            <header class="row history-head">
                <h3 id="history-title">Your decisions on this trip</h3>
                <button type="button" class="ghost icon-button" data-history-close
                        aria-label="Close history">×</button>
            </header>
            <div class="history-body"></div>
        </dialog>
    `);
    dialog = document.querySelector("dialog.history-panel");
    dialog.addEventListener("click", event => {
        // the backdrop is the dialog itself, so a click outside the content closes it
        if (event.target === dialog || event.target.closest("[data-history-close]")) dialog.close();
    });
    return dialog;
}

// shows what the traveller has accepted and dismissed on this trip.
// nothing is fetched, the card already holds every decision and when it was made
function openDecisionLog(button, detailsById) {
    const card = button.closest("[data-itinerary]");
    const details = detailsById.get(Number(card.dataset.itinerary));
    const dialog = historyDialog();

    dialog.querySelector("#history-title").textContent = `Your decisions on ${details.itinerary.name}`;
    dialog.querySelector(".history-body").innerHTML = createDecisionLog(details.recommendations);
    dialog.showModal();
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
        itinerariesWithDetails.forEach(details => {
            // working state for this card, kept beside the server data so a redraw does not lose it
            details.busyRecommendations = new Set();
            details.shown = {};
            detailsById.set(Number(details.itinerary.id), details);
        });

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

        const decideButton = event.target.closest("[data-decide]");
        if (decideButton) return decideAdvice(decideButton, detailsById, onAuthLost, drawSummary);

        const regenerateButton = event.target.closest("[data-regenerate]");
        if (regenerateButton) return regenerateAdvice(regenerateButton, detailsById, onAuthLost, drawSummary);

        const historyButton = event.target.closest("[data-trip-history]");
        if (historyButton) return openDecisionLog(historyButton, detailsById);

        const moreButton = event.target.closest("[data-more]");
        if (moreButton) return showMore(moreButton, detailsById);

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
