// alerts bell in the top bar (CS203-5): live from /api/alerts/stream, or checked every minute without it
import { api } from "./api.js";
import { onAlertsInvalidated } from "./alert-sync.js";
import { escapeHtml, findToastShelf, newsTitleOf, timeTag } from "./util.js";
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

export const alertTitle = group => escapeHtml(newsTitleOf(group.newsTitle));

// names what the news affects, eg "Museum, Dinner and 2 more on Japan"
export const describeTarget = group => {
    const itineraryName = escapeHtml(group.itineraryName);
    const activityNames = group.alerts
        .map(alert => alert.activityName)
        .filter(name => name);
    const coversWholeTrip = activityNames.length < group.alerts.length;

    if (coversWholeTrip) return `the whole of ${itineraryName}`;

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

// a group's id is its newest alert, which changes when another alert joins it,
// so the group is found by any alert in it and an id drawn a moment ago still works
export const findGroupWithAlert = (groups, id) => {
    const alertId = Number(id);
    return groups.find(group => group.alerts.some(alert => alert.id === alertId));
};

const findGroup = id => findGroupWithAlert(groupsInBell(), id);

// the same alerts in the same order, each still read or unread as before
const isSameList = (oldAlerts, newAlerts) => {
    if (oldAlerts.length !== newAlerts.length) return false;

    return oldAlerts.every((alert, index) => {
        const other = newAlerts[index];
        return alert.id === other.id && isUnread(alert) === isUnread(other);
    });
};

// ---------------------------------------------------------------- loading and live updates

// a 401 means the session ended, so stop everything and let the app send the user to the login page
function sessionEnded() {
    stopAlerts();
    state.onAuthLost();
}

// counts every refresh started, so a slow older one cannot land on top of a newer one
let latestRefresh = 0;

// pages with alerts of their own, so they keep up with the bell. each change carries unreadCount and one of
// newAlerts (with outOfStep, set when the count moved for some other reason), readIds, readUpToId (null for all)
// or saveFailed
const changeListeners = new Set();

// gives back a function that stops listening
export function onAlertsChanged(listener) {
    changeListeners.add(listener);
    return () => changeListeners.delete(listener);
}

// one listener failing should not stop the others, or count as the bell failing to load
function tellListeners(change) {
    const fullChange = { ...change, unreadCount: state.unreadCount };
    for (const listener of changeListeners) {
        try {
            listener(fullChange);
        } catch (error) {
            console.error(error);
        }
    }
}

// every save waits for the one before it, since sqlite refuses a second write while one is going.
// a reload in the middle could bring back reads that are not saved yet, so none happens while saves are
// running, and the bell reloads once they are all done instead
let saveQueue = Promise.resolve();
let savesRunning = 0;

// Session generation advances on logout, isolating pending requests and saves from the next login.
let saveSession = 0;

const queueSave = save => {
    const session = saveSession;
    savesRunning += 1;
    const queued = saveQueue.then(() => {
        // A queued action belongs to the session that created it, not the next account's cookies.
        if (session !== saveSession) return;
        return save();
    });
    saveQueue = queued.catch(() => { });   // one failed save should not block the ones after it

    saveQueue.then(() => {
        const isOldSession = session !== saveSession;
        if (isOldSession) return;

        savesRunning -= 1;
        const allSaved = savesRunning === 0;
        if (allSaved) refresh();
    });
    return queued;
};

// Whether an asynchronous result belongs to a session that has since ended.
const isFromOldSession = session => session !== saveSession;

// for the alerts page, so its own loads also wait until the server has every read
export const waitForSaves = () => saveQueue;

async function refresh() {
    const userId = state.userId;
    if (userId == null) return;
    if (savesRunning > 0) return;   // the queue reloads once saving is done

    latestRefresh += 1;
    const thisRefresh = latestRefresh;
    const session = saveSession;

    try {
        const page = await api.alerts({ size: 20 });
        const switchedAccount = state.userId !== userId;   // logged out or switched account while waiting
        const isOutdated = thisRefresh !== latestRefresh;   // a newer refresh has started since
        const savingStarted = savesRunning > 0;             // this may be from before the save, the reload after it is not
        if (isFromOldSession(session) || switchedAccount || isOutdated || savingStarted) return;

        const countBefore = state.unreadCount;
        const isFirstLoad = state.seenIds == null;
        const loadedAlerts = page?.content ?? [];
        const loadedCount = page?.unreadCount ?? 0;

        // most checks find nothing new, and redrawing anyway would make screen readers read the bell out again
        const sameCount = loadedCount === countBefore;
        const nothingChanged = !isFirstLoad && sameCount && isSameList(state.alerts, loadedAlerts);

        const listChanged = !isSameList(state.alerts, loadedAlerts);
        state.alerts = loadedAlerts;
        state.unreadCount = loadedCount;
        if (!nothingChanged) draw();

        const newAlerts = findNewAlerts();
        announceNewAlerts(newAlerts);

        // new alerts only ever add to the count, so any other change came from somewhere else,
        // eg reads in another tab. the first load has nothing to compare with, so it never counts
        const countChanged = state.unreadCount !== countBefore;
        const expectedCount = countBefore + newAlerts.length;
        const outOfStep = !isFirstLoad && (state.unreadCount !== expectedCount || listChanged && !newAlerts.length);
        if (newAlerts.length || countChanged || outOfStep) tellListeners({ newAlerts, outOfStep });
    } catch (error) {
        if (isFromOldSession(session)) return;
        // a failed load keeps the last alerts and tries again on the next event or check
        if (error.status === 401) sessionEnded();
    }
}

// the backend sends one event per alert, so news hitting six activities is six events in a row.
// they are gathered into one reload instead of six requests for the same list
const ALERT_BURST_MS = 500;
let burstTimer = null;
let stopSync = null;

const refreshAfterBurst = () => {
    clearTimeout(burstTimer);
    burstTimer = setTimeout(refresh, ALERT_BURST_MS);
};

const canStream = () => Boolean(api.alertStreamUrl) && typeof EventSource !== "undefined";

// While disconnected, recover saved alerts and reconnect a closed stream.
function checkAgain() {
    const canReconnect = canStream() && !state.stream;
    if (canReconnect) return connectStream();
    refresh();
}

function startPolling() {
    if (state.pollTimer) return;
    state.pollTimer = setInterval(checkAgain, CHECK_EVERY_MS);
}

function stopPolling() {
    clearInterval(state.pollTimer);
    state.pollTimer = null;
}

// Returning to a tab also recovers changes made outside this browser.
document.addEventListener("visibilitychange", () => {
    if (!document.hidden) refresh();
});

// both events reload the list, which also catches anything missed while disconnected
function connectStream() {
    if (!canStream()) return startPolling();

    const stream = new EventSource(api.alertStreamUrl);
    let recovering = false;
    stream.addEventListener("ready", () => {
        if (state.stream !== stream) return;
        recovering = false;
        stopPolling();
        refresh();
    });
    stream.addEventListener("alert", () => {
        if (state.stream === stream) refreshAfterBurst();
    });
    stream.addEventListener("error", () => {
        if (state.stream !== stream) return;
        if (stream.readyState === EventSource.CLOSED) state.stream = null;
        // Only the first failure in an outage triggers an immediate history check.
        // Further EventSource retries share the existing polling interval.
        startPolling();
        if (!recovering) refresh();   // also detects an ended session before the first ready event
        recovering = true;
    });
    state.stream = stream;
    startPolling();
}

// ---------------------------------------------------------------- marking read, used by the bell and the alerts page

// the alerts are shown as read straight away, in the bell and on the alerts page, then saved.
// the bell reloads once saving is done, which also undoes any read that failed.
// a 401 sends the user to log in. any other failure is thrown, so the alerts page can show what went wrong
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
    const coveredGroups = groupAlerts(unread).map(group => ({
        itineraryId: group.itineraryId, newsId: group.alerts[0].newsId,
        upToId: Math.max(...group.alerts.map(alert => alert.id)),
    }));
    tellListeners({ readIds: unreadIds, readGroups: coveredGroups });

    // queued as one save, so nothing reloads between two alerts of the same group
    // Mark the persisted group, including members outside the loaded page. Bound each
    // request by the newest displayed member so later arrivals remain unread.
    const saveEach = async () => {
        for (const group of coveredGroups) {
            // The session can change while the previous group's request is in flight.
            if (isFromOldSession(session)) return;
            await api.markAlertGroupRead(group.upToId);
        }
    };

    const session = saveSession;
    try {
        await queueSave(saveEach);
    } catch (error) {
        // the account logged out meanwhile, so a failure here says nothing about the one now logged in
        if (isFromOldSession(session)) return;
        if (error.status === 401) return sessionEnded();
        tellListeners({ saveFailed: true });
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
    // only alerts newer than upToId are still unread, the server count comes back with the response
    const newerUnread = state.alerts.filter(isUnread);
    state.unreadCount = newerUnread.length;
    draw();
    tellListeners({ readUpToId: upToId });

    // the reload after saving loads any alert that came in meanwhile, so it shows as unread
    const session = saveSession;
    let result;
    try {
        result = await queueSave(() => api.markAllAlertsRead(upToId));
    } catch (error) {
        if (isFromOldSession(session)) return;
        if (error.status === 401) return sessionEnded();
        tellListeners({ saveFailed: true });
        throw error;
    }

    // the count left belongs to the old account, not the one now logged in
    if (isFromOldSession(session)) return;

    const unreadLeft = result?.unreadCount ?? 0;
    state.unreadCount = unreadLeft;
    draw();
    tellListeners({ readUpToId: upToId });
    return unreadLeft;
}

// ---------------------------------------------------------------- drawing

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
                ${hasUnread ? `<button class="link-button" type="button" data-bell-read-all>Mark all as read</button>` : ""}
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

// the bell is drawn from scratch, so whatever had focus inside it is remembered as a selector and found again.
// these are the things in the bell that can take focus
const focusableSelectors = ["#alert-button", "[data-bell-read-all]", "[data-view-all]", ".alert-panel h2"];

const findFocusSelector = () => {
    const focused = document.activeElement;
    if (!focused || !state.slot.contains(focused)) return null;

    // the alerts are already updated by now, so the group's id may be a newer one than the link shows
    const alertId = focused.dataset.alertLink;
    if (alertId) {
        const group = findGroup(alertId);
        const currentId = group ? group.id : Number(alertId);
        return `[data-alert-link="${Number(currentId)}"]`;
    }

    return focusableSelectors.find(selector => focused.matches(selector)) ?? null;
};

// a focused link or button that is gone after drawing, like "mark all as read", hands focus to the heading.
// once the panel has closed it is left alone, eg after following an alert the trip page should not
// find focus sitting on the bell
function restoreFocus(selector) {
    if (!selector) return;

    const sameElement = state.slot.querySelector(selector);
    if (sameElement) {
        sameElement.focus();
        return;
    }

    if (!state.isOpen) return;
    state.slot.querySelector(".alert-panel h2")?.focus();
}

function draw() {
    if (!state.slot?.isConnected) return;

    const focusSelector = findFocusSelector();

    const badgeText = createBadgeText();
    const label = badgeText ? `Alerts, ${badgeText} new` : "Alerts";
    const badge = badgeText
        ? `<span class="alert-badge" aria-hidden="true">${badgeText}</span>`
        : "";

    state.slot.innerHTML = `
        <div class="alert-bell">
            <button class="ghost icon-button" type="button" id="alert-button"
                    aria-label="${label}" aria-expanded="${state.isOpen}" aria-controls="alert-panel">
                ${icon("bell")}
                ${badge}
            </button>
            ${state.isOpen ? createPanel() : ""}
        </div>
    `;

    restoreFocus(focusSelector);
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
            ${icon("close")}
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
    // its own attribute, since the alerts page has a "mark all as read" button of its own
    if (event.target.closest("[data-bell-read-all]")) return readAll();

    const link = event.target.closest("[data-alert-link]");
    if (link) return goToTrip(link);

    if (event.target.closest("[data-view-all]")) return close();

    if (state.isOpen && !event.target.closest(".alert-bell")) close();
});

// focus goes back to the bell when it was in the bell, or nowhere at all, like after a click in safari,
// which does not focus buttons. escape in eg the chat panel leaves focus there
document.addEventListener("keydown", event => {
    if (event.key !== "Escape") return;

    const wasInBell = Boolean(event.target.closest?.(".alert-bell"));
    const wasNowhere = event.target === document.body;
    close({ returnFocus: wasInBell || wasNowhere });
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
        stopSync = onAlertsInvalidated(refreshAfterBurst);

        // loaded straight away rather than waiting for "ready", which a proxy holding the stream back can delay for good
        refresh();
        connectStream();
    }
    draw();
}

// on logout or session end, so nothing keeps listening in the background
export function stopAlerts() {
    stopSync?.();
    stopSync = null;
    state.stream?.close();
    clearInterval(state.pollTimer);
    clearTimeout(state.toastTimer);
    clearTimeout(burstTimer);

    // saves still running for this account carry on, but no longer hold back the next account's loads
    saveSession += 1;
    saveQueue = Promise.resolve();
    savesRunning = 0;

    document.querySelectorAll(".alert-toast").forEach(toast => toast.remove());

    state.userId = null;
    state.alerts = [];
    state.unreadCount = 0;
    state.stream = null;
    state.pollTimer = null;
    state.isOpen = false;
    state.seenIds = null;
    state.toastAlerts = [];
    state.toastTimer = null;
}
