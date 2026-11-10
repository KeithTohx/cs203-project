// stored news, newest first, with older stories on demand and a filter for news affecting your trips
import { api } from "../api.js";
import { emptyState, escapeHtml, hostnameOf, loading, newsTitleOf, pageHead, safeUrl, showError, timeTag } from "../util.js";
import { icon } from "../icons.js";

// small enough to read through, and the backend caps a page at 100 anyway
const PAGE_SIZE = 10;

// one story, linking to the original article and tagging any trip it affects
const createNewsItem = (news, affectedTrips = []) => {
    const link = safeUrl(news.url);
    const title = escapeHtml(newsTitleOf(news.title));
    const heading = link
        ? `<a href="${link}" target="_blank" rel="noopener noreferrer">${title}<span class="sr-only"> (opens in a new tab)</span></a>`
        : `<span>${title}</span>`;

    const where = news.source || hostnameOf(news.url);
    const meta = [escapeHtml(where), timeTag(news.publishedAt)].filter(Boolean).join(" · ");

    const tripTags = affectedTrips.map(tripName => `
        <span class="tag bad">Affects ${escapeHtml(tripName)}</span>
    `).join("");

    return `
        <li class="news-item">
            <div class="news-head">
                <strong>${heading}</strong>
                ${tripTags}
            </div>
            ${meta ? `<p class="meta">${meta}</p>` : ""}
            ${news.description ? `<p class="news-description">${escapeHtml(news.description)}</p>` : ""}
        </li>
    `;
};

// all news or only news affecting my trips, for travellers only
const createViewSwitch = affectingCount => `
    <div class="view-switch" role="group" aria-label="Which news to show">
        <button class="ghost small" type="button" data-view="all" aria-pressed="true">All news</button>
        <button class="ghost small" type="button" data-view="mine" aria-pressed="false">
            Affecting my trips (${affectingCount})
        </button>
    </div>
`;

// news id -> names of the user's trips it affects, rejected impacts left out.
// a trip whose impacts fail to load is skipped, so the others still get their labels
const findAffectedTrips = async user => {
    const itineraries = (await api.itineraries(user.id)) ?? [];
    const results = await Promise.allSettled(itineraries.map(async itinerary => ({
        itinerary,
        impacts: (await api.itineraryImpacts(itinerary.id)) ?? [],
    })));

    // a 401 means every request failed the same way, so it is passed on for the login page
    const sessionEnded = results.find(result => result.status === "rejected" && result.reason?.status === 401);
    if (sessionEnded) throw sessionEnded.reason;

    const impactsPerTrip = results
        .filter(result => result.status === "fulfilled")
        .map(result => result.value);

    const tripsByNews = new Map();
    const newsById = new Map();
    for (const { itinerary, impacts } of impactsPerTrip) {
        for (const impact of impacts) {
            if (!impact.impacted || impact.rejected) continue;

            const trips = tripsByNews.get(impact.newsId) ?? new Set();
            trips.add(itinerary.name);
            tripsByNews.set(impact.newsId, trips);

            // an impact carries the article's fields, so the "my trips" view needs no extra request
            newsById.set(impact.newsId, {
                id: impact.newsId,
                title: impact.newsTitle,
                source: impact.newsSource,
                url: impact.newsUrl,
                description: impact.newsDescription,
                publishedAt: impact.newsPublishedAt,
            });
        }
    }
    return { tripsByNews, affectingNews: [...newsById.values()] };
};

export async function showNews(root, user, reauth) {
    // the news and the trips load side by side, so an ended session would otherwise be reported twice
    let authLostReported = false;
    const onAuthLost = () => {
        if (authLostReported) return;
        authLostReported = true;
        reauth();
    };

    root.innerHTML = `
        ${pageHead("News")}
        <p class="meta page-intro">Travel news the system has picked up, newest first. New stories are added automatically.</p>
        <div id="news-switch"></div>
        <ul class="news-list" id="news-list"></ul>
        <div id="news-status" role="status">${loading()}</div>
        <p class="pager">
            <button class="ghost" type="button" id="load-more" hidden>Load older news</button>
        </p>
    `;

    const list = root.querySelector("#news-list");
    const status = root.querySelector("#news-status");
    const loadMoreButton = root.querySelector("#load-more");
    const switchContainer = root.querySelector("#news-switch");

    let nextPage = 0;
    let hasMore = false;   // whether the last page loaded was full, so there may be another
    let loadedNews = [];
    let tripsByNews = new Map();
    let affectingNews = [];
    let currentView = "all";

    const tripsFor = news => [...(tripsByNews.get(news.id) ?? [])];

    const drawAll = () => {
        list.innerHTML = loadedNews.map(news => createNewsItem(news, tripsFor(news))).join("");
        status.innerHTML = loadedNews.length ? "" : emptyState({
            iconHtml: icon("news"),
            title: "No news yet",
            text: "Stories appear here as the system picks them up.",
        });
    };

    // built from the impacts, so it is complete even for stories on pages that are not loaded yet
    const drawMine = () => {
        const newestFirst = [...affectingNews]
            .sort((first, second) => new Date(second.publishedAt ?? 0) - new Date(first.publishedAt ?? 0));
        list.innerHTML = newestFirst.map(news => createNewsItem(news, tripsFor(news))).join("");
        status.innerHTML = newestFirst.length ? "" : emptyState({
            iconHtml: icon("check"),
            title: "Nothing affects your trips",
            text: "None of the news so far affects your trips.",
        });
        loadMoreButton.hidden = true;
    };

    // a short page means it was the last one
    const loadNextPage = async () => {
        loadMoreButton.disabled = true;
        loadMoreButton.textContent = "Loading…";

        let page;
        try {
            page = (await api.news({ page: nextPage, size: PAGE_SIZE })) ?? [];
        } catch (error) {
            loadMoreButton.disabled = false;
            loadMoreButton.textContent = "Load older news";
            return showError(status, error, onAuthLost);
        }

        // news arriving between clicks shifts the pages, so skip stories already shown
        const shownIds = new Set(loadedNews.map(news => news.id));
        const unseen = page.filter(news => !shownIds.has(news.id));
        loadedNews = [...loadedNews, ...unseen];
        nextPage += 1;
        hasMore = page.length === PAGE_SIZE;
        loadMoreButton.disabled = false;
        loadMoreButton.textContent = "Load older news";

        if (currentView === "all") {
            drawAll();
            loadMoreButton.hidden = !hasMore;
        }
    };

    const switchTo = view => {
        currentView = view;
        for (const button of switchContainer.querySelectorAll("[data-view]")) {
            button.setAttribute("aria-pressed", String(button.dataset.view === view));
        }
        if (view === "mine") return drawMine();

        drawAll();
        // not worked out from loadedNews, which skips repeats and so can come up short of a full page
        loadMoreButton.hidden = !hasMore;
    };

    // trips are extra, so the news still shows if they fail to load.
    // they take a request per trip, so the news is not kept waiting and gets its trip labels once they arrive
    const loadAffectedTrips = async () => {
        try {
            ({ tripsByNews, affectingNews } = await findAffectedTrips(user));
        } catch (error) {
            if (error.status === 401) onAuthLost();
            return;
        }

        switchContainer.innerHTML = createViewSwitch(affectingNews.length);
        const newsIsShowing = currentView === "all" && loadedNews.length > 0;
        if (newsIsShowing) drawAll();
    };

    switchContainer.addEventListener("click", event => {
        const button = event.target.closest("[data-view]");
        if (button) switchTo(button.dataset.view);
    });
    loadMoreButton.addEventListener("click", loadNextPage);

    const tripsLoading = user.role === "USER" ? loadAffectedTrips() : null;
    await Promise.all([loadNextPage(), tripsLoading]);
}
