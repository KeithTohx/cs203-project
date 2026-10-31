// The news the system has picked up, newest first, with older stories loaded on demand.
// Travellers can also switch to only the news that affects one of their trips.
import { api } from "../api.js";
import { escapeHtml, formatDateTime, hostnameOf, safeUrl, showError } from "../util.js";

// small enough to read through, and the backend caps a page at 100 anyway
const PAGE_SIZE = 10;

// Component: one story. the title opens the original article, and a red tag names any trip it affects
const createNewsItem = (news, affectedTrips = []) => {
    const link = safeUrl(news.url);
    const title = escapeHtml(news.title || "Untitled news");
    const heading = link
        ? `<a href="${link}" target="_blank" rel="noopener noreferrer">${title}<span class="sr-only"> (opens in a new tab)</span></a>`
        : `<span>${title}</span>`;

    const where = news.source || hostnameOf(news.url);
    const published = formatDateTime(news.publishedAt);
    const meta = [where, published].filter(Boolean).map(escapeHtml).join(" · ");

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

// Component: the two views to switch between. only travellers have trips, so admins only get the full list
const createViewSwitch = affectingCount => `
    <div class="view-switch" role="group" aria-label="Which news to show">
        <button class="ghost small" type="button" data-view="all" aria-pressed="true">All news</button>
        <button class="ghost small" type="button" data-view="mine" aria-pressed="false">
            Affecting my trips (${affectingCount})
        </button>
    </div>
`;

// news id -> the names of the user's trips it affects. a rejected impact no longer counts,
// same as on the itineraries page
const findAffectedTrips = async user => {
    const itineraries = (await api.itineraries(user.id)) ?? [];
    const impactsPerTrip = await Promise.all(itineraries.map(async itinerary => ({
        itinerary,
        impacts: (await api.itineraryImpacts(itinerary.id)) ?? [],
    })));

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

export async function showNews(root, user, onAuthLost) {
    root.innerHTML = `
        <h1>News</h1>
        <p class="meta">Travel news the system has picked up, newest first. New stories are added automatically.</p>
        <div id="news-switch"></div>
        <ul class="news-list" id="news-list"></ul>
        <div id="news-status" role="status">
            <div class="msg">Loading…</div>
        </div>
        <p class="pager">
            <button class="ghost" type="button" id="load-more" hidden>Load older news</button>
        </p>
    `;

    const list = root.querySelector("#news-list");
    const status = root.querySelector("#news-status");
    const loadMoreButton = root.querySelector("#load-more");
    const switchContainer = root.querySelector("#news-switch");

    let nextPage = 0;
    let loadedNews = [];
    let tripsByNews = new Map();
    let affectingNews = [];
    let currentView = "all";

    const tripsFor = news => [...(tripsByNews.get(news.id) ?? [])];

    const drawAll = () => {
        list.innerHTML = loadedNews.map(news => createNewsItem(news, tripsFor(news))).join("");
        status.innerHTML = loadedNews.length ? "" : `<div class="msg">No news has been picked up yet.</div>`;
    };

    // built from the impacts, so it is complete even for stories on pages that are not loaded yet
    const drawMine = () => {
        const newestFirst = [...affectingNews]
            .sort((first, second) => new Date(second.publishedAt ?? 0) - new Date(first.publishedAt ?? 0));
        list.innerHTML = newestFirst.map(news => createNewsItem(news, tripsFor(news))).join("");
        status.innerHTML = newestFirst.length ? "" : `<div class="msg">None of the news so far affects your trips.</div>`;
        loadMoreButton.hidden = true;
    };

    // a full page means there may be more behind it. a short page is the end of the list
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

        // pages are counted from the newest story, so news that arrives between clicks pushes
        // everything down and the next page can repeat a story already shown. skip those
        const shownIds = new Set(loadedNews.map(news => news.id));
        const unseen = page.filter(news => !shownIds.has(news.id));
        loadedNews = [...loadedNews, ...unseen];
        nextPage += 1;
        loadMoreButton.disabled = false;
        loadMoreButton.textContent = "Load older news";

        if (currentView === "all") {
            drawAll();
            loadMoreButton.hidden = page.length < PAGE_SIZE;
        }
    };

    const switchTo = view => {
        currentView = view;
        for (const button of switchContainer.querySelectorAll("[data-view]")) {
            button.setAttribute("aria-pressed", String(button.dataset.view === view));
        }
        if (view === "mine") return drawMine();

        drawAll();
        // the last page loaded decides whether there is more, so ask again only if it was full
        loadMoreButton.hidden = loadedNews.length < nextPage * PAGE_SIZE;
    };

    // the trips are extra. if they fail to load, the news still shows, just without the tags and the switch
    if (user.role === "USER") {
        try {
            ({ tripsByNews, affectingNews } = await findAffectedTrips(user));
            switchContainer.innerHTML = createViewSwitch(affectingNews.length);
        } catch (error) {
            if (error.status === 401) return onAuthLost();
        }
    }

    switchContainer.addEventListener("click", event => {
        const button = event.target.closest("[data-view]");
        if (button) switchTo(button.dataset.view);
    });
    loadMoreButton.addEventListener("click", loadNextPage);

    await loadNextPage();
}
