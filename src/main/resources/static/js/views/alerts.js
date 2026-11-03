// every alert, newest first, with older ones on demand and a filter for unread ones
import { api } from "../api.js";
import {
    alertMetaParts, alertTitle, groupAlerts, isGroupUnread, isUnread, markAlertsRead, markAllAlertsRead, onNewAlerts,
} from "../alerts.js";
import { emptyState, escapeHtml, hostnameOf, safeUrl, showError } from "../util.js";
import { icon } from "../icons.js";

// the same as the bell loads, the backend caps a page at 100
const PAGE_SIZE = 20;

// one news article on one trip, like an entry in the bell but with the reason and source as well
const createAlertItem = group => {
    const unread = isGroupUnread(group);
    const first = group.alerts[0];

    // the reason can differ per activity, so each different one is listed once
    const reasons = [...new Set(group.alerts.map(alert => alert.reason).filter(reason => reason))];
    const reasonLines = reasons.map(reason => `<p class="alert-reason">${escapeHtml(reason)}</p>`).join("");

    const link = safeUrl(first.newsUrl);
    const sourceName = escapeHtml(first.newsSource || hostnameOf(first.newsUrl));
    const source = link && sourceName
        ? `<a href="${link}" target="_blank" rel="noopener noreferrer">${sourceName}<span class="sr-only"> (opens in a new tab)</span></a>`
        : sourceName;
    const meta = [...alertMetaParts(group), source]
        .filter(part => part)
        .join(" · ");

    return `
        <li class="news-item alert-item${unread ? " is-new" : ""}">
            <div class="news-head">
                <strong>
                    <a href="#/itineraries/${Number(group.itineraryId)}" data-open-group="${Number(group.id)}">
                        ${unread ? `<span class="sr-only">New: </span>` : ""}${alertTitle(group)}
                    </a>
                </strong>
                ${unread ? `<span class="tag">New</span>` : ""}
            </div>
            <p class="meta">${meta}</p>
            ${reasonLines}
            ${unread
                ? `<button class="link-button" type="button" data-read-group="${Number(group.id)}">Mark as read</button>`
                : ""}
        </li>
    `;
};

const createViewSwitch = (view, unreadCount) => `
    <div class="view-switch" role="group" aria-label="Which alerts to show">
        <button class="ghost small" type="button" data-view="all" aria-pressed="${view === "all"}">All alerts</button>
        <button class="ghost small" type="button" data-view="unread" aria-pressed="${view === "unread"}">
            Unread (${unreadCount})
        </button>
        ${unreadCount ? `<button class="link-button" type="button" data-read-all>Mark all as read</button>` : ""}
    </div>
`;

export async function showAlerts(root, user, onAuthLost, onLeave) {
    root.innerHTML = `
        <h1>Alerts</h1>
        <p class="meta">News that affects your trips, newest first.</p>
        <div id="alerts-switch"></div>
        <ul class="news-list" id="alerts-list"></ul>
        <div id="alerts-status" role="status">
            <div class="msg">Loading…</div>
        </div>
        <p class="pager">
            <button class="ghost" type="button" id="load-more" hidden>Load older alerts</button>
        </p>
    `;

    const list = root.querySelector("#alerts-list");
    const status = root.querySelector("#alerts-status");
    const loadMoreButton = root.querySelector("#load-more");
    const switchContainer = root.querySelector("#alerts-switch");

    let currentView = "all";
    let loadedAlerts = [];
    let unreadCount = 0;
    let hasMore = false;
    let nextBeforeId = null;   // the cursor the backend gave for the next page, null for the newest

    // alerts read while on the unread view drop out of it straight away
    const shownGroups = () => {
        const groups = groupAlerts(loadedAlerts);
        if (currentView !== "unread") return groups;
        return groups.filter(isGroupUnread);
    };

    // loaded alerts are left out, since a new one can arrive live while a page of them is still loading
    const addAlerts = (alerts, { atTop }) => {
        const loadedIds = new Set(loadedAlerts.map(alert => alert.id));
        const notLoaded = alerts.filter(alert => !loadedIds.has(alert.id));
        loadedAlerts = atTop ? [...notLoaded, ...loadedAlerts] : [...loadedAlerts, ...notLoaded];
    };

    const draw = () => {
        const groups = shownGroups();
        switchContainer.innerHTML = createViewSwitch(currentView, unreadCount);
        list.innerHTML = groups.map(createAlertItem).join("");
        loadMoreButton.hidden = !hasMore;

        if (groups.length) {
            status.innerHTML = "";
        } else if (currentView === "unread") {
            status.innerHTML = emptyState({
                iconHtml: icon("check"),
                title: "All caught up",
                text: "You have read every alert.",
            });
        } else {
            status.innerHTML = emptyState({
                iconHtml: icon("news"),
                title: "No alerts yet",
                text: "You get an alert when news affects one of your trips.",
            });
        }
    };

    // each page starts after the oldest alert shown, so marking alerts read in between skips none
    const loadMore = async () => {
        loadMoreButton.disabled = true;
        loadMoreButton.textContent = "Loading…";
        const viewAsked = currentView;

        let page;
        try {
            page = await api.alerts({ unreadOnly: viewAsked === "unread", beforeId: nextBeforeId, size: PAGE_SIZE });
        } catch (error) {
            loadMoreButton.disabled = false;
            loadMoreButton.textContent = "Load older alerts";
            return showError(status, error, onAuthLost);
        }
        if (viewAsked !== currentView) return;   // switched view while waiting

        addAlerts(page?.content ?? [], { atTop: false });
        unreadCount = page?.unreadCount ?? 0;
        hasMore = page?.hasMore ?? false;
        nextBeforeId = page?.nextBeforeId ?? null;

        loadMoreButton.disabled = false;
        loadMoreButton.textContent = "Load older alerts";
        draw();
    };

    const reload = async () => {
        loadedAlerts = [];
        hasMore = false;
        nextBeforeId = null;
        list.innerHTML = "";
        status.innerHTML = `<div class="msg">Loading…</div>`;
        await loadMore();
    };

    const switchTo = view => {
        if (view === currentView) return;
        currentView = view;
        reload();
    };

    // a failed save is shown after reloading, so the list is back to what the server has
    const showSaveFailure = async error => {
        await reload();
        showError(status, error, onAuthLost);
    };

    const markRead = async alerts => {
        const unreadBefore = alerts.filter(isUnread).length;
        const saved = markAlertsRead(alerts);
        unreadCount = Math.max(0, unreadCount - unreadBefore);
        draw();

        try {
            await saved;
        } catch (error) {
            await showSaveFailure(error);
        }
    };

    // only up to the newest alert on this page, so one that arrives while it is open stays unread
    const markAllRead = async () => {
        const newestShownId = loadedAlerts[0]?.id ?? null;
        const now = new Date().toISOString();
        for (const alert of loadedAlerts) alert.readAt ??= now;
        unreadCount = 0;
        draw();

        let unreadLeft;
        try {
            unreadLeft = await markAllAlertsRead(newestShownId);
        } catch (error) {
            return showSaveFailure(error);
        }

        // undefined when the session ended, and the app is already on its way to the login page
        if (unreadLeft == null) return;
        unreadCount = unreadLeft;
        draw();
    };

    const findGroup = id => shownGroups().find(group => group.id === Number(id));

    switchContainer.addEventListener("click", event => {
        const viewButton = event.target.closest("[data-view]");
        if (viewButton) return switchTo(viewButton.dataset.view);

        if (event.target.closest("[data-read-all]")) markAllRead();
    });

    list.addEventListener("click", event => {
        const readButton = event.target.closest("[data-read-group]");
        if (readButton) {
            const group = findGroup(readButton.dataset.readGroup);
            if (group) markRead(group.alerts);
            return;
        }

        // the trip opens as usual, this only marks the alert read on the way
        const tripLink = event.target.closest("[data-open-group]");
        if (tripLink) {
            const group = findGroup(tripLink.dataset.openGroup);
            if (group) markRead(group.alerts);
        }
    });

    loadMoreButton.addEventListener("click", loadMore);

    // the bell hears about new alerts live, so they are added to the top here as well.
    // new alerts are unread, so they belong in both views
    const stopListening = onNewAlerts((newAlerts, latestUnreadCount) => {
        addAlerts(newAlerts, { atTop: true });
        unreadCount = latestUnreadCount;
        draw();
    });
    onLeave(stopListening);

    await loadMore();
}
