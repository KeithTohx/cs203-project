// every alert, newest first, with older ones on demand and a filter for unread ones
import { api } from "../api.js";
import {
    alertMetaParts, alertTitle, findGroupWithAlert, groupAlerts, isGroupUnread, markAlertsRead, markAllAlertsRead,
    onAlertsChanged, waitForSaves,
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
    // data-source-group lets focus find this link again after the list is redrawn
    const source = link && sourceName
        // no line breaks inside the link, or the spaces would show as underlined gaps
        ? `<a href="${link}" target="_blank" rel="noopener noreferrer" data-source-group="${Number(group.id)}">${sourceName}<span class="sr-only"> (opens in a new tab)</span></a>`
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
        <div id="alerts-error"></div>
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
    const errorBox = root.querySelector("#alerts-error");   // kept apart from the status, which every redraw clears

    let currentView = "all";
    let loadedAlerts = [];
    let unreadCount = 0;
    let hasMore = false;
    let nextBeforeId = null;   // the cursor the backend gave for the next page, null for the newest
    let isReloading = true;    // the first page has not come back yet, after opening or reloading

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

    // the switch and list are drawn from scratch, so whatever had focus in them is looked for again afterwards.
    // a "mark as read" button is gone once its alert is read, so focus moves to that alert's link,
    // and to the current view's button when the alert itself is gone, eg having left the unread view.
    // anything else is left alone, so a live update never pulls focus up to the switch
    const findFocusSelectors = () => {
        const focused = document.activeElement;
        const isInPage = focused && (switchContainer.contains(focused) || list.contains(focused));
        if (!isInPage) return [];

        // a new alert joining the group gives it a newer id, and the alerts are already updated by now
        const allGroups = groupAlerts(loadedAlerts);
        const currentIdFor = drawnId => {
            const group = findGroupWithAlert(allGroups, drawnId);
            return Number(group ? group.id : drawnId);
        };

        const { view, readGroup, openGroup, sourceGroup } = focused.dataset;
        const selectors = [];
        if (view) selectors.push(`[data-view="${view}"]`);
        if (readGroup) {
            const id = currentIdFor(readGroup);
            selectors.push(`[data-read-group="${id}"]`, `[data-open-group="${id}"]`);
        }
        if (openGroup) {
            const id = currentIdFor(openGroup);
            selectors.push(`[data-open-group="${id}"]`);
        }
        if (sourceGroup) {
            const id = currentIdFor(sourceGroup);
            selectors.push(`[data-source-group="${id}"]`, `[data-open-group="${id}"]`);
        }
        if (focused.matches("[data-read-all]")) selectors.push("[data-read-all]");

        const isKnown = selectors.length > 0;
        if (isKnown) selectors.push(`[data-view="${currentView}"]`);
        return selectors;
    };

    const restoreFocus = selectors => {
        const target = selectors
            .map(selector => root.querySelector(selector))
            .find(element => element);
        target?.focus();
    };

    const draw = () => {
        const focusSelectors = findFocusSelectors();
        const groups = shownGroups();
        switchContainer.innerHTML = createViewSwitch(currentView, unreadCount);
        list.innerHTML = groups.map(createAlertItem).join("");
        loadMoreButton.hidden = !hasMore;
        restoreFocus(focusSelectors);

        // a live change can land while the list is reloading, which is not the same as there being no alerts
        if (groups.length) {
            status.innerHTML = "";
        } else if (isReloading) {
            status.innerHTML = `<div class="msg">Loading…</div>`;
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

    // counts every load started, so one from before a reload cannot land in the fresh list,
    // even when the view switched away and back while it was waiting
    let latestLoad = 0;

    // reads made while a page is on its way, which that page may still show as unread.
    // null while nothing is loading
    let readsWhileLoading = null;

    // reads can come from the bell or from here, so copies are matched by id
    const markCopiesRead = (alerts, isCovered) => {
        const now = new Date().toISOString();
        for (const alert of alerts) {
            if (isCovered(alert)) alert.readAt ??= now;
        }
    };

    // each page starts after the oldest alert shown, so marking alerts read in between skips none.
    // reads still saving are waited for, or the page could come back with them unread
    const loadMore = async () => {
        loadMoreButton.disabled = true;
        loadMoreButton.textContent = "Loading…";
        latestLoad += 1;
        const thisLoad = latestLoad;
        readsWhileLoading = [];

        let page;
        try {
            await waitForSaves();
            page = await api.alerts({ unreadOnly: currentView === "unread", beforeId: nextBeforeId, size: PAGE_SIZE });
        } catch (error) {
            // an error for a list already reloaded is not worth showing, unless the session ended
            const isStale = thisLoad !== latestLoad && error.status !== 401;
            if (isStale) return;
            readsWhileLoading = null;
            isReloading = false;
            loadMoreButton.disabled = false;
            loadMoreButton.textContent = "Load older alerts";
            return showError(status, error, onAuthLost);
        }
        if (thisLoad !== latestLoad) return;   // reloaded while waiting, the newer load fills the list

        // the server may have answered before those reads saved, so they are applied to this page too,
        // and its count is older than the one the bell has sent since
        const pageAlerts = page?.content ?? [];
        const readsMeanwhile = readsWhileLoading;
        readsWhileLoading = null;
        for (const isCovered of readsMeanwhile) markCopiesRead(pageAlerts, isCovered);

        addAlerts(pageAlerts, { atTop: false });
        const countIsCurrent = readsMeanwhile.length === 0;
        if (countIsCurrent) unreadCount = page?.unreadCount ?? 0;
        hasMore = page?.hasMore ?? false;
        nextBeforeId = page?.nextBeforeId ?? null;
        isReloading = false;

        loadMoreButton.disabled = false;
        loadMoreButton.textContent = "Load older alerts";
        draw();
    };

    // kept so a failed save can wait for the reload the change listener started
    let latestReload = null;

    const reload = () => {
        // focus inside the list would fall to the page itself once it empties, so it waits on the view button
        const focusWasInList = list.contains(document.activeElement);
        if (focusWasInList) switchContainer.querySelector(`[data-view="${currentView}"]`)?.focus();

        loadedAlerts = [];
        hasMore = false;
        nextBeforeId = null;
        isReloading = true;
        list.innerHTML = "";
        status.innerHTML = `<div class="msg">Loading…</div>`;
        latestReload = loadMore();
        return latestReload;
    };

    const switchTo = view => {
        if (view === currentView) return;
        currentView = view;
        errorBox.innerHTML = "";   // a failed save belongs to the view it happened on
        reload();
    };

    // a failed save has already set off a reload through the change listener below,
    // so the error is shown once the list is back to what the server has.
    // a 401 never gets here, the bell sends the user to log in itself
    const showSaveFailure = async error => {
        // left for a trip page while saving, so there is nothing here to show it in
        if (!list.isConnected) return;
        const viewWhenFailed = currentView;

        // the bell's own reload after saving can start another one, which cuts the first short,
        // so this keeps waiting until the newest reload has finished
        let awaitedReload;
        do {
            awaitedReload = latestReload ?? reload();
            await awaitedReload;
        } while (awaitedReload !== latestReload);

        // the error belongs to the view it happened on, so switching away while waiting drops it
        const switchedView = currentView !== viewWhenFailed;
        if (switchedView) return;
        showError(errorBox, error, onAuthLost);
    };

    // the list and count are updated through the change listener below, the same as for reads in the bell
    const markRead = async alerts => {
        errorBox.innerHTML = "";
        try {
            await markAlertsRead(alerts);
        } catch (error) {
            await showSaveFailure(error);
        }
    };

    // only up to the newest alert on this page, so one that arrives while it is open stays unread
    const markAllRead = async () => {
        const newestShownId = loadedAlerts[0]?.id ?? null;
        errorBox.innerHTML = "";
        try {
            await markAllAlertsRead(newestShownId);
        } catch (error) {
            await showSaveFailure(error);
        }
    };

    // marks the loaded copies, and remembers the read for a page still on its way
    const markLoadedRead = isCovered => {
        markCopiesRead(loadedAlerts, isCovered);
        readsWhileLoading?.push(isCovered);
    };

    const findGroup = id => findGroupWithAlert(shownGroups(), id);

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

    // keeps this list in step with the bell: new alerts go on top and reads show here too.
    // a failed save or a count that moved elsewhere, eg in another tab, means the list is loaded again
    const stopListening = onAlertsChanged(change => {
        if (change.saveFailed || change.outOfStep) {
            reload();
            return;
        }

        if (change.newAlerts) addAlerts(change.newAlerts, { atTop: true });

        if (change.readIds) markLoadedRead(alert => change.readIds.has(alert.id));

        if (change.readUpToId !== undefined) {
            const upToId = change.readUpToId;
            markLoadedRead(alert => upToId == null || alert.id <= upToId);
        }

        unreadCount = change.unreadCount;
        draw();
    });
    onLeave(stopListening);

    await loadMore();
}
