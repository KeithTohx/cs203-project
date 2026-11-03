// alerts bell in the top bar (CS203-5): live from /api/alerts/stream, or checked every minute without it
import { api } from "./api.js";
import { escapeHtml, findToastShelf, timeTag } from "./util.js";
import { icon } from "./icons.js";

const CHECK_EVERY_MS = 60_000;
const MOST_SHOWN = 10;
const TOAST_SHOWN_MS = 7000;

// kept outside the nav, which is redrawn on every page change
const state = {
    userId: null,
    alerts: [],          // newest first, as the backend sends them
    unreadCount: 0,      // across every alert, not just the ones loaded
    stream: null,
    pollTimer: null,
    slot: null,
    isOpen: false,
    seenIds: null,       // alerts already loaded, so only ones arriving later pop up. null until the first load
    toastAlerts: [],     // what the pop-up on screen is showing, so later alerts can join it
    toastTimer: null,
    onAuthLost: () => { },
    redraw: () => { },
};

// ---------------------------------------------------------------- grouping and wording, shared with the alerts page

export const isUnread = alert => alert.readAt == null;

// one entry per news article and trip, like the news section on the itineraries page,
// so news hitting three activities shows once instead of three times. the alerts page groups the same way
export const groupAlerts = alerts => {
    const groups = new Map();

    for (const alert of alerts) {
        const newsKey = alert.newsId ?? alert.newsTitle;
        const key = `${newsKey}:${alert.itineraryId}`;

        if (!groups.has(key)) {
            groups.set(key, {
                id: alert.id,   // the newest alert stands in for the group, since alerts come newest first
                newsTitle: alert.newsTitle,
                itineraryId: alert.itineraryId,
                itineraryName: alert.itineraryName,
                createdAt: alert.createdAt,
                alerts: [],
            });
        }
        groups.get(key).alerts.push(alert);
    }

    return [...groups.values()];
};

export const isGroupUnread = group => group.alerts.some(isUnread);

export const alertTitle = group => escapeHtml(group.newsTitle || "Untitled news");

// names what the news affects, eg "Museum, Dinner and 2 more on Japan"
export const describeTarget = group => {
    const itineraryName = escapeHtml(group.itineraryName);
    const activityNames = group.alerts
        .map(alert => alert.activityName)
        .filter(name => name);
    const coversWholeTrip = activityNames.length < group.alerts.length;

    if (coversWholeTrip || !activityNames.length) return `the whole of ${itineraryName}`;

    // up to three names in full, past that the rest are counted
    const escapedNames = activityNames.map(escapeHtml);
    let names;
    if (escapedNames.length <= 3) {
        const last = escapedNames.pop();
        names = escapedNames.length ? `${escapedNames.join(", ")} and ${last}` : last;
    } else {
        const namesLeft = escapedNames.length - 2;
        names = `${escapedNames.slice(0, 2).join(", ")} and ${namesLeft} more`;
    }

    return `${names} on ${itineraryName}`;
};

// the "affects ... · when" line both lists show, as parts so the alerts page can add the source
export const alertMetaParts = group => {
    const affects = `Affects ${describeTarget(group)}`;
    const when = timeTag(group.createdAt);
    return [affects, when].filter(part => part);
};

const groupsInBell = () => groupAlerts(state.alerts);

const findGroup = id => groupsInBell().find(group => group.id === Number(id));

// ---------------------------------------------------------------- loading and live updates

// a 401 means the session ended, so stop everything and let the app send the user to the login page
function sessionEnded() {
    stopAlerts();
    state.onAuthLost();
}

// counts every refresh started, so a slow older one cannot land on top of a newer one
let latestRefresh = 0;

// pages showing alerts of their own, told when new ones arrive so they do not fall behind the bell
const newAlertListeners = new Set();

// gives back a function that stops listening
export function onNewAlerts(listener) {
    newAlertListeners.add(listener);
    return () => newAlertListeners.delete(listener);
}

// one listener failing should not stop the others, or count as the bell failing to load
function tellListeners(newAlerts) {
    for (const listener of newAlertListeners) {
        try {
            listener(newAlerts, state.unreadCount);
        } catch (error) {
            console.error(error);
        }
    }
}

async function refresh() {
    const userId = state.userId;
    if (userId == null) return;

    latestRefresh += 1;
    const thisRefresh = latestRefresh;

    try {
        const page = await api.alerts({ size: 20 });
        const switchedAccount = state.userId !== userId;   // logged out or switched account while waiting
        const isOutdated = thisRefresh !== latestRefresh;   // a newer refresh has started since
        if (switchedAccount || isOutdated) return;

        state.alerts = page?.content ?? [];
        state.unreadCount = page?.unreadCount ?? 0;
        draw();

        const newAlerts = findNewAlerts();
        announceNewAlerts(newAlerts);
        if (newAlerts.length) tellListeners(newAlerts);
    } catch (error) {
        // a failed load keeps the last alerts and tries again on the next event or check
        if (error.status === 401) sessionEnded();
    }
}

function startPolling() {
    if (state.pollTimer) return;
    state.pollTimer = setInterval(refresh, CHECK_EVERY_MS);
}

// both events reload the list, which also catches anything missed while disconnected
function connectStream() {
    if (!api.alertStreamUrl || typeof EventSource === "undefined") return startPolling();

    const stream = new EventSource(api.alertStreamUrl);
    stream.addEventListener("ready", refresh);
    stream.addEventListener("alert", refresh);
    stream.addEventListener("error", () => {
        if (stream.readyState !== EventSource.CLOSED) return;
        if (state.stream === stream) state.stream = null;
        startPolling();
        refresh();   // finds out whether the session ended, and if so sends the user to log in
    });
    state.stream = stream;
}

// ---------------------------------------------------------------- marking read, used by the bell and the alerts page

// the alerts are shown as read straight away, here and in the bell, then saved one at a time
// since sqlite refuses a second write that starts while another is still going.
// a 401 sends the user to log in. any other failure reloads the bell and is thrown, so the
// alerts page can show what went wrong
export async function markAlertsRead(alerts) {
    const unread = alerts.filter(isUnread);
    if (!unread.length) return;

    const now = new Date().toISOString();
    const unreadIds = new Set(unread.map(alert => alert.id));
    for (const alert of unread) alert.readAt = now;
    for (const alert of state.alerts) {
        if (unreadIds.has(alert.id)) alert.readAt ??= now;
    }
    state.unreadCount = Math.max(0, state.unreadCount - unread.length);
    draw();

    try {
        for (const alert of unread) {
            await api.markAlertRead(alert.id);
        }
    } catch (error) {
        if (error.status === 401) return sessionEnded();
        refresh();
        throw error;
    }
}

// one request for every unread alert, since the bell only loads the newest 20
// and marking those alone would leave older ones behind.
// upToId is the newest alert the user has seen, so one arriving meanwhile stays unread.
// gives back how many are still unread, which the alerts page shows as well
export async function markAllAlertsRead(upToId = state.alerts[0]?.id ?? null) {
    const now = new Date().toISOString();
    for (const alert of state.alerts) {
        const isCovered = upToId == null || alert.id <= upToId;
        if (isCovered) alert.readAt ??= now;
    }
    state.unreadCount = 0;
    draw();

    let result;
    try {
        result = await api.markAllAlertsRead(upToId);
    } catch (error) {
        if (error.status === 401) return sessionEnded();
        refresh();
        throw error;
    }

    const unreadLeft = result?.unreadCount ?? 0;
    state.unreadCount = unreadLeft;
    draw();
    refresh();   // loads any alert that came in meanwhile, so it shows as unread
    return unreadLeft;
}

// ---------------------------------------------------------------- drawing

const bellIcon = `
    <svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"></path>
        <path d="M13.7 21a2 2 0 0 1-3.4 0"></path>
    </svg>
`;

const closeIcon = `
    <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
         stroke-width="2.5" stroke-linecap="round">
        <path d="M18 6 6 18M6 6l12 12"></path>
    </svg>
`;

// one news article on one trip, linking to that trip
const createAlertItem = group => {
    const unread = isGroupUnread(group);
    const meta = alertMetaParts(group).join(" · ");

    return `
        <li${unread ? ` class="is-new"` : ""}>
            <a href="#/itineraries/${Number(group.itineraryId)}" data-alert-link="${Number(group.id)}">
                ${unread ? `<span class="sr-only">New: </span>` : ""}
                <strong>${alertTitle(group)}</strong>
                <span class="meta">${meta}</span>
            </a>
        </li>
    `;
};

const createPanel = () => {
    const groups = groupsInBell();
    const shown = groups.slice(0, MOST_SHOWN);
    const hasUnread = shown.some(isGroupUnread);

    const list = shown.length
        ? `<ul>${shown.map(createAlertItem).join("")}</ul>`
        : `<p class="meta">No alerts right now. News affecting your trips still shows on your itineraries.</p>`;
    const hiddenCount = groups.length - shown.length;
    const viewAllText = hiddenCount > 0 ? `View all alerts (${hiddenCount} more)` : "View all alerts";

    return `
        <div class="alert-panel" id="alert-panel" role="dialog" aria-label="Alerts">
            <div class="row">
                <h2 tabindex="-1">Alerts</h2>
                ${hasUnread ? `<button class="link-button" type="button" data-read-all>Mark all as read</button>` : ""}
            </div>
            ${list}
            <a class="alert-panel-footer" href="#/alerts" data-view-all>${viewAllText}</a>
        </div>
    `;
};

// counts condensed alerts like the panel lists them, so news hitting three activities on one trip counts once.
// the bell only loads the newest 20, so when the backend has more unread than are loaded a + says there are others
const createBadgeText = () => {
    const unreadGroups = groupsInBell().filter(isGroupUnread).length;
    const unreadLoaded = state.alerts.filter(isUnread).length;
    const hasOlderUnread = state.unreadCount > unreadLoaded;

    if (unreadGroups > 9) return "9+";
    if (hasOlderUnread) return `${Math.max(unreadGroups, 1)}+`;
    if (unreadGroups) return String(unreadGroups);
    return "";
};

function draw() {
    if (!state.slot?.isConnected) return;

    const badgeText = createBadgeText();
    const label = badgeText ? `Alerts, ${badgeText} new` : "Alerts";
    const badge = badgeText
        ? `<span class="alert-badge" aria-hidden="true">${badgeText}</span>`
        : "";

    state.slot.innerHTML = `
        <div class="alert-bell">
            <button class="ghost icon-button" type="button" id="alert-button"
                    aria-label="${label}" aria-expanded="${state.isOpen}" aria-controls="alert-panel">
                ${bellIcon}
                ${badge}
            </button>
            ${state.isOpen ? createPanel() : ""}
        </div>
    `;
}

// ---------------------------------------------------------------- pop-ups for new alerts

// new since the last load. the first load only remembers what is there,
// so logging in or reloading never pops up alerts that were already waiting
function findNewAlerts() {
    const isFirstLoad = state.seenIds == null;
    const seenIds = state.seenIds ?? new Set();

    const unseen = state.alerts.filter(alert => !seenIds.has(alert.id));
    for (const alert of state.alerts) seenIds.add(alert.id);
    state.seenIds = seenIds;

    if (isFirstLoad) return [];
    return unseen.filter(isUnread);
}

// one news article reads as that article, several at once are rolled into one pop-up
const createToastBody = groups => {
    if (groups.length === 1) {
        const group = groups[0];
        return `
            <strong>${alertTitle(group)}</strong>
            <span class="meta">Affects ${describeTarget(group)}</span>
            <a href="#/itineraries/${Number(group.itineraryId)}" data-alert-link="${Number(group.id)}">View trip</a>
        `;
    }

    return `
        <strong>${groups.length} new alerts</strong>
        <span class="meta">News is affecting your trips.</span>
        <a href="#/alerts" data-view-all>View alerts</a>
    `;
};

function removeToast(toast) {
    toast.classList.add("leaving");
    setTimeout(() => toast.remove(), 400);
}

function createToast() {
    const toast = document.createElement("div");
    toast.className = "toast alert-toast";
    toast.innerHTML = `
        ${icon("news")}
        <div class="alert-toast-body"></div>
        <button class="ghost icon-button" type="button" data-close-toast aria-label="Close">
            ${closeIcon}
        </button>
    `;

    // the links themselves are handled with the bell's, this only clears the pop-up away
    toast.addEventListener("click", event => {
        if (event.target.closest("[data-close-toast], a")) removeToast(toast);
    });

    findToastShelf().append(toast);
    return toast;
}

function announceNewAlerts(newAlerts) {
    if (!newAlerts.length || state.isOpen) return;   // with the panel open they are already in view

    // alerts arriving while a pop-up is still up join it, so there is only ever one, grouped like the bell.
    // its text changes in place rather than the whole pop-up sliding in again
    const shownToast = document.querySelector(".alert-toast:not(.leaving)");
    const alreadyShown = shownToast ? state.toastAlerts : [];
    state.toastAlerts = [...newAlerts, ...alreadyShown];

    const toast = shownToast ?? createToast();
    toast.querySelector(".alert-toast-body").innerHTML = createToastBody(groupAlerts(state.toastAlerts));

    // goes on its own, the alert stays in the bell for later. joining it starts the time again
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(() => removeToast(toast), TOAST_SHOWN_MS);
}

// ---------------------------------------------------------------- panel actions

function open() {
    state.isOpen = true;
    draw();
}

function close({ returnFocus = false } = {}) {
    if (!state.isOpen) return;
    state.isOpen = false;
    draw();
    if (returnFocus) state.slot?.querySelector("#alert-button")?.focus();
}

// a failure shows itself, since the bell reloads with the alerts still unread
async function readAll() {
    const finished = markAllAlertsRead();
    state.slot?.querySelector(".alert-panel h2")?.focus();

    try {
        await finished;
    } catch {
        // the bell has already reloaded
    }
}

// opening the same trip again fires no hashchange, so the app is asked to redraw the page
function goToTrip(link) {
    const group = findGroup(link.dataset.alertLink);
    if (group) markAlertsRead(group.alerts).catch(() => { });   // the bell reloads by itself on failure
    close();
    if (location.hash === link.getAttribute("href")) state.redraw();
}

// one set of listeners for the whole page, since the bell itself is redrawn often
document.addEventListener("click", event => {
    if (!state.slot?.isConnected) return;

    if (event.target.closest("#alert-button")) {
        state.isOpen ? close() : open();
        return;
    }

    // checked before "clicked outside", since redrawing removes the button that was clicked from the page
    if (event.target.closest("[data-read-all]")) return readAll();

    const link = event.target.closest("[data-alert-link]");
    if (link) return goToTrip(link);

    if (event.target.closest("[data-view-all]")) return close();

    if (state.isOpen && !event.target.closest(".alert-bell")) close();
});

document.addEventListener("keydown", event => {
    if (event.key === "Escape") close({ returnFocus: true });
});

// ---------------------------------------------------------------- used by the nav

// called on every nav redraw, only a new account loads and connects
export function mountAlertBell(slot, user, { onAuthLost, redraw }) {
    state.slot = slot;
    state.onAuthLost = onAuthLost;
    state.redraw = redraw;

    if (state.userId !== user.id) {
        stopAlerts();
        state.userId = user.id;
        refresh();
        connectStream();
    }
    draw();
}

// on logout or session end, so nothing keeps listening in the background
export function stopAlerts() {
    state.stream?.close();
    clearInterval(state.pollTimer);
    clearTimeout(state.toastTimer);
    document.querySelectorAll(".alert-toast").forEach(toast => toast.remove());
    Object.assign(state, {
        userId: null, alerts: [], unreadCount: 0, stream: null, pollTimer: null, isOpen: false,
        seenIds: null, toastAlerts: [], toastTimer: null,
    });
}
