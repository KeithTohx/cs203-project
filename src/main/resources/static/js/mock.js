// DEV ONLY: fake backend that runs in the browser. Enabled by opening /?mock on localhost.
// Answers after a short pause, with a copy so views can't change the mock data by accident.
const respondWith = value => new Promise(resolve => setTimeout(() => resolve(structuredClone(value)), 250));
const fail = (status, message) => Promise.reject(Object.assign(new Error(message), { status }));

const USERS = [
    { id: 1, username: "john", email: "john@example.com", password: "password123", role: "USER" },
    { id: 2, username: "alice", email: "alice@example.com", password: "password123", role: "USER" },
    { id: 3, username: "admin", email: "admin@example.com", password: "admin123", role: "ADMIN" },
    { id: 4, username: "bob", email: "bob@example.com", password: "password123", role: "USER" },
    { id: 5, username: "carol", email: "carol@example.com", password: "password123", role: "USER" },
    { id: 6, username: "dave", email: "dave@example.com", password: "password123", role: "USER", enabled: false },
];
for (const user of USERS) user.enabled ??= true;
const withoutPassword = ({ password, ...user }) => user;
let me = USERS.find(user => user.id === Number(sessionStorage.getItem("mockUserId"))) || null;
// Returns a rejected promise when nobody is logged in, otherwise null.
const requireLogin = () => me ? null : fail(401, "Not logged in");

const COUNTRIES = [{ id: 1, name: "Japan" }, { id: 2, name: "Singapore" }, { id: 3, name: "Thailand" }];
const makeItinerary = (id, userId, name, countryName, dateStart, dateEnd) =>
    ({ id, userId, name, countryId: COUNTRIES.find(country => country.name === countryName).id, countryName, dateStart, dateEnd });
const ITINERARIES = [
    makeItinerary(1, 1, "Japan Ski Trip", "Japan", "2026-12-15T00:00:00", "2026-12-22T00:00:00"),
    makeItinerary(3, 1, "Singapore Weekend", "Singapore", "2027-01-08T00:00:00", "2027-01-10T00:00:00"),
    makeItinerary(9, 1, "Kyoto Autumn (past)", "Japan", "2026-03-01T00:00:00", "2026-03-08T00:00:00"),
    makeItinerary(2, 2, "Thailand Beach Holiday", "Thailand", "2026-11-05T00:00:00", "2026-11-12T00:00:00"),
    makeItinerary(6, 2, "Tokyo Cherry Blossoms", "Japan", "2027-03-25T00:00:00", "2027-04-02T00:00:00"),
];
// Same flat shape as ActivityResponse on the backend.
const makeActivity = (id, itineraryId, name, address, dateStart, dateEnd) => ({ id, itineraryId, name, address, dateStart, dateEnd });
const ACTIVITIES = [
    makeActivity(1, 1, "Ski at Niseko", "Niseko, Hokkaido", "2026-12-16T09:00:00", "2026-12-18T17:00:00"),
    makeActivity(2, 1, "Onsen visit", "Noboribetsu, Hokkaido", "2026-12-19T15:00:00", "2026-12-19T20:00:00"),
    makeActivity(3, 1, "Sapporo food tour", "Sapporo", "2026-12-21T11:00:00", "2026-12-21T16:00:00"),
    makeActivity(4, 3, "Universal Studios Singapore", "Sentosa", "2027-01-09T10:00:00", "2027-01-09T19:00:00"),
    makeActivity(5, 3, "Gardens by the Bay", "Marina Bay", "2027-01-08T18:00:00", "2027-01-08T21:00:00"),
    makeActivity(6, 9, "Fushimi Inari hike", "Kyoto", "2026-03-02T08:00:00", "2026-03-02T12:00:00"),
    makeActivity(7, 2, "Grand Palace visit", "Bangkok", "2026-11-06T09:00:00", "2026-11-06T12:00:00"),
    makeActivity(8, 2, "Island hopping", "Phi Phi Islands", "2026-11-09T08:00:00", "2026-11-09T17:00:00"),
    // itinerary 6 has no activities, to test the empty state
];
// a time this long before the page loaded, so the "5 minutes ago" style times show in mock mode too
const minutesAgo = minutes => new Date(Date.now() - minutes * 60_000).toISOString();

// news id -> the article fields an impact carries. 3 has no source so the label falls back to the
// link's site name, and 2 has no link so its bubble is not clickable
const NEWS = {
    1: { title: "Magnitude 6.8 earthquake strikes near Hokkaido", source: "Japan Meteorological Agency",
        url: "https://www.jma.go.jp/jma/indexe.html", publishedAt: "2026-12-14T08:30:00Z",
        description: "A strong earthquake hit southern Hokkaido early this morning. Rail services are suspended while tracks are inspected, and several ski resorts have paused lifts." },
    2: { title: "Universal Studios Singapore to close for maintenance", source: "The Straits Times",
        url: null, publishedAt: "2026-12-30T10:00:00Z", description: null },
    3: { title: "Cherry blossom forecast: early bloom & record crowds", source: null,
        url: "https://www.japan-guide.com/sakura", publishedAt: "2027-02-20T09:00:00Z",
        description: "Forecasters expect blossoms in Tokyo up to a week early, with record visitor numbers at popular parks." },
    4: { title: "Flooding closes Bangkok tourist areas", source: "Bangkok Post",
        url: "https://www.bangkokpost.com/", publishedAt: "2026-11-01T14:00:00Z",
        description: "Heavy rain has flooded parts of central Bangkok, closing some attractions near the river." },
    5: { title: "Grand Palace closed for royal ceremony", source: "Bangkok Post",
        url: "https://www.bangkokpost.com/", publishedAt: "2026-10-28T07:00:00Z", description: null },
    6: { title: "Storm warning issued for the Andaman coast", source: "Thai Meteorological Department",
        url: "https://www.tmd.go.th/en", publishedAt: "2026-11-04T06:00:00Z",
        description: "Strong winds and high waves are expected off Krabi and Phuket, and small boats are told to stay in port." },
    // stories that affect nobody's trip, so the news page has more than one page to load
    7: { title: "Taxi drivers in Seoul plan two-day strike", source: "The Korea Herald",
        url: "https://www.koreaherald.com/", publishedAt: minutesAgo(25),
        description: "Fewer taxis are expected across Seoul as drivers protest over fares." },
    8: { title: "New rail line opens between Kuala Lumpur and Singapore", source: "Channel NewsAsia",
        url: "https://www.channelnewsasia.com/", publishedAt: minutesAgo(180),
        description: "The first trains run this weekend, cutting the journey to under two hours." },
    9: { title: "Volcanic ash closes Bali airport for a morning", source: null,
        url: "https://www.example.com/bali-ash", publishedAt: minutesAgo(1560),
        description: "Flights were held while ash from Mount Lewotobi drifted over the island. Most resumed by noon." },
    10: { title: "Heatwave warning for Hanoi this week", source: "VnExpress",
        url: "https://e.vnexpress.net/", publishedAt: minutesAgo(4320),
        description: "Temperatures are forecast to pass 38°C, and outdoor sites may shorten their hours." },
    11: { title: "Typhoon season ends early for the Philippines", source: "Rappler",
        url: "https://www.rappler.com/", publishedAt: minutesAgo(7200), description: null },
    12: { title: "Jeju ferry services return to normal", source: "Yonhap",
        url: "https://en.yna.co.kr/", publishedAt: minutesAgo(8640),
        description: "Ferries between Jeju and the mainland are back on their usual timetable after last week's storms." },
};
// the stored news as the backend's /api/news returns it, newest first
const newsNewestFirst = () => Object.entries(NEWS)
    .map(([id, news]) => ({ id: Number(id), ...news }))
    .sort((first, second) => new Date(second.publishedAt) - new Date(first.publishedAt));
// Same flat shape as ImpactResponse on the backend.
const makeImpact = (id, impacted, newsId, activityId, activityName, reason) => {
    const news = NEWS[newsId];
    return {
        id, impacted, newsId,
        newsTitle: news.title,
        newsSource: news.source,
        newsUrl: news.url,
        newsDescription: news.description,
        newsPublishedAt: news.publishedAt,
        activityId, activityName, reason,
        rejected: false,
    };
};
const IMPACTS = {
    1: [makeImpact(1, true, 1, 1, "Ski at Niseko", "Some ski resorts suspending lifts."),
    makeImpact(2, true, 1, 2, "Onsen visit", null),
    makeImpact(3, true, 1, null, null, "Flights into Sapporo may be delayed.")],
    3: [makeImpact(6, true, 2, 4, "Universal Studios Singapore", "Dates to be confirmed.")],
    6: [makeImpact(11, true, 3, null, null, "Expect queues at popular parks in late March.")],
    2: [makeImpact(4, true, 4, 7, "Grand Palace visit", "Some attractions closed."),
    makeImpact(5, false, 5, 7, "Grand Palace visit", "Closure is before your visit."),
    makeImpact(12, true, 6, 8, "Island hopping", "Boat tours cannot run while the storm warning is in place.")],
    // itinerary 9 has no impacts, to test the empty state
};

// same shape as RecommendationResponse, the onsen and universal studios start without one
const makeRecommendation = (id, impact, severity, action, advice, option = null) => ({
    id, severity, action, advice,
    impactId: impact.id,
    reason: impact.reason,
    itineraryId: Number(Object.keys(IMPACTS).find(key => IMPACTS[key].includes(impact))),
    activityId: impact.activityId,
    activityName: impact.activityName,
    newsId: impact.newsId,
    newsTitle: impact.newsTitle,
    optionId: option?.id ?? null,
    optionName: option?.name ?? null,
    optionArea: option?.area ?? null,
    optionAddress: option?.address ?? null,
});
// a few places per country to suggest instead, like the backend's activity_option table
const OPTIONS = {
    Japan: [
        { id: 101, name: "Sapporo Art Park", area: "Sapporo", address: "2-75 Geijutsunomori, Minami-ku, Sapporo" },
        { id: 102, name: "Hokkaido Museum", area: "Sapporo", address: "53-2 Konopporo, Atsubetsu-ku, Sapporo" },
        { id: 103, name: "Jozankei Onsen", area: "Sapporo", address: "Jozankei, Minami-ku, Sapporo" },
    ],
    Singapore: [{ id: 201, name: "ArtScience Museum", area: "Marina Bay", address: "6 Bayfront Ave, Singapore" }],
    Thailand: [{ id: 301, name: "Jim Thompson House", area: "Pathum Wan", address: "6 Kasem San 2 Alley, Bangkok" }],
};
const RECOMMENDATIONS = {
    1: [
        makeRecommendation(1, IMPACTS[1][0], "HIGH", "REPLACE",
            "Lifts at Niseko may stay closed after the earthquake. Spend the day at Sapporo Art Park instead.",
            OPTIONS.Japan[0]),
        makeRecommendation(2, IMPACTS[1][2], "MEDIUM", "MONITOR",
            "Affects the whole trip, so follow local updates and keep the plans flexible."),
    ],
    2: [
        makeRecommendation(3, IMPACTS[2][0], "MEDIUM", "RESCHEDULE",
            "The flooding near the river should ease within a few days. Move the Grand Palace visit to the 10th."),
        makeRecommendation(4, IMPACTS[2][2], "HIGH", "CANCEL",
            "Boats are told to stay in port while the storm passes. Cancel the island hopping and ask the operator for a refund."),
    ],
    6: [
        makeRecommendation(5, IMPACTS[6][0], "LOW", "MONITOR",
            "Crowds are expected but the parks stay open, so no change is needed. Go early in the morning to avoid the queues."),
    ],
};
let nextRecommendationId = 6;

// mock model answers, anything else gets a plain swap like the backend's fallback
const MOCK_ANSWERS = {
    2: { severity: "MEDIUM", action: "RELOCATE", optionIndex: 2,
        advice: "Noboribetsu may close its baths for safety checks. Soak at Jozankei Onsen near Sapporo instead." },
    6: { severity: "LOW", action: "MONITOR", optionIndex: null,
        advice: "The maintenance dates are not confirmed yet, so no change is needed. Check the park calendar a week before you go." },
};
const SEVERITY_ORDER = ["HIGH", "MEDIUM", "LOW"];
const worstFirst = recommendations => [...recommendations]
    .sort((first, second) => SEVERITY_ORDER.indexOf(first.severity) - SEVERITY_ORDER.indexOf(second.severity));

// like the backend's alert table, one per assessed impact, and no stream here so the bell polls
const ALERTS = [
    { id: 1, impactId: 1, createdAt: "2026-12-14T09:00:00Z", readAt: "2026-12-14T10:00:00Z" },
    { id: 2, impactId: 2, createdAt: "2026-12-14T09:00:05Z", readAt: "2026-12-14T10:00:00Z" },
    { id: 3, impactId: 3, createdAt: minutesAgo(90), readAt: null },
    { id: 4, impactId: 6, createdAt: minutesAgo(12), readAt: null },
    { id: 5, impactId: 4, createdAt: "2026-11-01T15:00:00Z", readAt: null },
    { id: 6, impactId: 12, createdAt: "2026-11-04T07:00:00Z", readAt: null },
    { id: 7, impactId: 11, createdAt: "2027-02-20T10:00:00Z", readAt: null },
];

// the alert as AlertResponse sends it, or null when the backend would hide it
const visibleAlert = alert => {
    const itineraryId = Number(Object.keys(IMPACTS).find(key => IMPACTS[key].some(impact => impact.id === alert.impactId)));
    const impact = IMPACTS[itineraryId]?.find(candidate => candidate.id === alert.impactId);
    const itinerary = myItinerary(itineraryId);
    if (!impact || !itinerary || !impact.impacted || impact.rejected) return null;

    return {
        id: alert.id, impactId: impact.id, itineraryId, itineraryName: itinerary.name,
        activityId: impact.activityId, activityName: impact.activityName,
        newsId: impact.newsId, newsTitle: impact.newsTitle, newsSource: impact.newsSource, newsUrl: impact.newsUrl,
        reason: impact.reason, createdAt: alert.createdAt, readAt: alert.readAt,
        emailStatus: "PENDING", emailSentAt: null,
    };
};

// itinerary id -> [{ role, content }], oldest first. john's ski trip starts with a short conversation
// the itineraries whose chat is still waiting on an answer, like ChatbotServiceImpl.answering
const ANSWERING = new Set();
const CHATS = {
    1: [
        { role: "user", content: "Is my ski trip affected by anything?" },
        { role: "assistant", content: "(mock reply) The Hokkaido earthquake may affect Ski at Niseko, as some resorts have suspended lifts. Check with the resort before you go." },
    ],
};

// user id -> saved preferences. users without an entry get the defaults, like on the backend
const PREFERENCES = {};
const defaultPreferences = () => ({
    riskTolerance: "MEDIUM",
    flexibilityLevel: "SOMEWHAT_FLEXIBLE",
    explanationDetail: "BRIEF",
    travelGroup: "SOLO",
    travellingWithChildren: false,
    travellingWithElderly: false,
    updatedAt: new Date().toISOString(),
});

const myItinerary = id => ITINERARIES.find(itinerary => itinerary.id === Number(id) && itinerary.userId === me?.id);
const myActivity = id => ACTIVITIES.find(activity => activity.id === Number(id) && myItinerary(activity.itineraryId));

// audit log, oldest first, same fields as AuditEntryResponse
const AUDIT = [];
const record = (action, actorId, actorEmail, targetId, details = null, occurredAt = new Date().toISOString()) =>
    AUDIT.push({ id: AUDIT.length + 1, occurredAt, actorId, actorEmail, action, targetId, details });

// a little history so the admin pages are not empty on first load
record("REGISTER", 1, "john@example.com", 1, null, "2026-10-01T09:12:00Z");
record("LOGIN", 1, "john@example.com", 1, null, "2026-10-01T09:13:00Z");
record("ITINERARY_CREATED", 1, "john@example.com", 1, "itinerary 1 'Japan Ski Trip'", "2026-10-01T09:20:00Z");
record("LOGIN_FAILED", null, "alice@example.com", 2, null, "2026-10-12T22:41:00Z");
record("LOGIN", 2, "alice@example.com", 2, null, "2026-10-12T22:42:00Z");
record("ITINERARY_UPDATED", 2, "alice@example.com", 2, "itinerary 2 'Thailand Beach Holiday'", "2026-10-12T22:50:00Z");
record("ACCOUNT_DISABLED", 3, "admin@example.com", 6, "disabled user 'dave'", "2026-10-19T17:30:00Z");
record("LOGIN_FAILED", null, "dave@example.com", 6, "account is disabled", "2026-10-20T08:05:00Z");

// smaller than the backend's 20, so the pager shows up with this little mock data
const PAGE_SIZE = 5;
const pageOf = (items, page, size = PAGE_SIZE) => {
    const totalPages = Math.ceil(items.length / size);
    const safePage = Math.max(0, page);
    return {
        content: items.slice(safePage * size, (safePage + 1) * size),
        page: safePage,
        size,
        totalElements: items.length,
        totalPages,
    };
};
const newestFirst = entries => [...entries].sort((first, second) => second.id - first.id);

// a rejected promise unless an admin is logged in, otherwise null
const requireAdmin = () => requireLogin() || (me.role !== "ADMIN" ? fail(403, "Admins only.") : null);
// same idea for things only normal users have, like preferences
const requireUser = () => requireLogin() || (me.role !== "USER" ? fail(403, "Only for user accounts.") : null);
const findUser = id => USERS.find(user => user.id === Number(id));
const userNotFound = id => fail(404, `No user found with id ${id}`);
const adminMustBeDemoted = () =>
    fail(409, "admin accounts must be demoted through role management before disabling or deleting");

// the itinerary and everything hanging off it, so a new one reusing its id starts clean
const removeItinerary = itinerary => {
    ITINERARIES.splice(ITINERARIES.indexOf(itinerary), 1);
    for (let index = ACTIVITIES.length - 1; index >= 0; index--) {
        if (ACTIVITIES[index].itineraryId === itinerary.id) ACTIVITIES.splice(index, 1);
    }
    delete IMPACTS[itinerary.id];
    delete RECOMMENDATIONS[itinerary.id];
    delete CHATS[itinerary.id];
};

// the account and everything it owns
const removeAccount = account => {
    const ownedItineraries = ITINERARIES.filter(itinerary => itinerary.userId === account.id);
    for (const itinerary of ownedItineraries) removeItinerary(itinerary);

    delete PREFERENCES[account.id];
    // declared further down, which is fine since this only runs once the whole file has loaded
    const theirChanges = ADMIN_CHANGES.filter(change => change.userId === account.id);
    for (const change of theirChanges) ADMIN_CHANGES.splice(ADMIN_CHANGES.indexOf(change), 1);
    USERS.splice(USERS.indexOf(account), 1);
};

// ---------- admin console helpers ----------

// the impact with this id and the itinerary it sits under, or null
const findImpactEntry = id => {
    for (const [itineraryId, impacts] of Object.entries(IMPACTS)) {
        const impact = impacts.find(candidate => candidate.id === Number(id));
        if (impact) return { itineraryId: Number(itineraryId), impact };
    }
    return null;
};

// the recommendation with this id and the itinerary it sits under, or null
const findRecommendationEntry = id => {
    for (const [itineraryId, recommendations] of Object.entries(RECOMMENDATIONS)) {
        const recommendation = recommendations.find(candidate => candidate.id === Number(id));
        if (recommendation) return { itineraryId: Number(itineraryId), recommendation };
    }
    return null;
};

const ownerOfItinerary = itineraryId => ITINERARIES.find(itinerary => itinerary.id === Number(itineraryId))?.userId ?? null;

// the impact in the admin shape, which also says whose trip it is on
const adminImpact = (itineraryId, impact) => {
    const itinerary = ITINERARIES.find(candidate => candidate.id === itineraryId);
    return { ...impact, itineraryId, itineraryName: itinerary?.name ?? null, userId: itinerary?.userId ?? null };
};

// the seeded rows have no status, they are all still waiting on the traveller
const adminRecommendation = recommendation => ({ status: "PENDING", decidedAt: null, stale: false, ...recommendation });

const allOptions = () => Object.values(OPTIONS).flat();

// an impact goes with its alert and its recommendations
const removeImpact = (itineraryId, impact) => {
    IMPACTS[itineraryId].splice(IMPACTS[itineraryId].indexOf(impact), 1);

    const recommendations = RECOMMENDATIONS[itineraryId] ?? [];
    RECOMMENDATIONS[itineraryId] = recommendations.filter(recommendation => recommendation.impactId !== impact.id);

    for (let index = ALERTS.length - 1; index >= 0; index--) {
        if (ALERTS[index].impactId === impact.id) ALERTS.splice(index, 1);
    }
};

// like the backend's ClearedVerdict.auditDetails, so the log reads the same in both
const clearedDetails = (itineraryId, impact, cause) => {
    const parts = [impact.impacted ? "impacted" : "not impacted"];
    if (impact.rejected) parts.push("rejected by the traveller");
    if (ALERTS.some(alert => alert.impactId === impact.id)) parts.push("had an alert");

    const recommendationCount = (RECOMMENDATIONS[itineraryId] ?? [])
        .filter(recommendation => recommendation.impactId === impact.id).length;
    if (recommendationCount) parts.push(`${recommendationCount} ${recommendationCount === 1 ? "recommendation" : "recommendations"}`);

    const title = NEWS[impact.newsId]?.title;
    const onNews = title ? `news ${impact.newsId} '${title}'` : `news ${impact.newsId}`;
    const reason = impact.reason ? `. reason: ${impact.reason}` : "";
    return `impact ${impact.id} on ${onNews}: ${parts.join(", ")}. cleared because ${cause} (by admin)${reason}`;
};

// like the backend, verdicts made for an old country, date, place or name are dropped on an edit,
// and each one is written to the log first. the real one then judges the trip again, the mock has
// no model so it just leaves them gone
const clearImpacts = (itineraryId, belongs, cause) => {
    const stale = (IMPACTS[itineraryId] ?? []).filter(belongs);
    const ownerId = ownerOfItinerary(itineraryId);

    for (const impact of stale) {
        // not recordByAdmin, the details already say "(by admin)" before the reason
        record("IMPACT_CLEARED", me.id, me.email, ownerId, clearedDetails(itineraryId, impact, cause));
        removeImpact(itineraryId, impact);
    }
    return stale.length;
};

// like the backend's checks on dates sent to the admin console
const endsBeforeItStarts = ({ dateStart, dateEnd }) => dateStart && dateEnd && dateEnd < dateStart;

// recorded against the owner, so it shows up on their admin page
const recordByAdmin = (action, ownerId, details) => record(action, me.id, me.email, ownerId, `${details} (by admin)`);

// what the traveller is told, like the backend's AdminChangeService. an admin's own trips are left out
const ADMIN_CHANGES = [];
// target is what the change was about, like AdminChangeTarget: { itineraryId, itineraryName, newsId }.
// the names are copied in, so they still read right after the trip is deleted
const tellOwner = (ownerId, message, target = {}) => {
    if (ownerId == null || ownerId === me.id) return;
    const nextId = Math.max(0, ...ADMIN_CHANGES.map(change => change.id)) + 1;
    const itineraryId = target.itineraryId == null ? null : Number(target.itineraryId);
    const newsId = target.newsId ?? null;

    ADMIN_CHANGES.push({
        id: nextId,
        userId: ownerId,
        message,
        createdAt: new Date().toISOString(),
        readAt: null,
        itineraryId,
        itineraryName: target.itineraryName ?? (itineraryId == null ? null : tripNameOf(itineraryId)),
        newsId,
        newsTitle: newsId == null ? null : NEWS[newsId]?.title ?? null,
    });
};

// what the traveller gets back, with whether the trip can still be opened
const adminChangeView = change => {
    const trip = ITINERARIES.find(itinerary => itinerary.id === change.itineraryId);
    const { userId, ...shown } = change;
    return { ...shown, itineraryExists: Boolean(trip) && trip.userId === userId };
};

// the same wording as AdminDataController
const tripNameOf = itineraryId => ITINERARIES.find(itinerary => itinerary.id === Number(itineraryId))?.name ?? "?";
const activityOnTrip = activity => `the activity '${activity.name}' on '${tripNameOf(activity.itineraryId)}'`;
const placeOfImpact = (itineraryId, impact) => impact.activityId == null
    ? `your trip '${tripNameOf(itineraryId)}'`
    : `the activity '${impact.activityName}' on '${tripNameOf(itineraryId)}'`;
const newsNameOf = impact => NEWS[impact.newsId]?.title ? `'${NEWS[impact.newsId].title}'` : "article";
const clearedNote = count => {
    if (!count) return "";
    const checks = count === 1 ? "1 news check on it was cleared" : `${count} news checks on it were cleared`;
    return ` ${checks}, and it will be checked against the news again.`;
};
const markEditedByAdmin = itineraryId => {
    const itinerary = ITINERARIES.find(candidate => candidate.id === Number(itineraryId));
    if (itinerary) itinerary.adminEditedAt = new Date().toISOString();
};

export const mockApi = {
    // like the backend: a disabled account gets the same answer as a wrong password, so nothing is given away
    login: (email, password) => {
        const aimedAt = USERS.find(candidate => candidate.email.toLowerCase() === email.toLowerCase());
        const passwordMatches = aimedAt && aimedAt.password === password;

        if (!passwordMatches || !aimedAt.enabled) {
            const details = passwordMatches ? "account is disabled" : null;
            record("LOGIN_FAILED", null, email, aimedAt?.id ?? null, details);
            return fail(401, "Invalid email or password.");
        }

        me = aimedAt;
        sessionStorage.setItem("mockUserId", me.id);
        record("LOGIN", me.id, me.email, me.id);
        return respondWith(null);
    },
    register: details => {
        const taken = field => USERS.some(user => user[field].toLowerCase() === details[field].toLowerCase());
        if (taken("email")) return fail(409, `email '${details.email}' is already taken`);
        if (taken("username")) return fail(409, `username '${details.username}' is already taken`);
        const user = { id: Math.max(...USERS.map(user => user.id)) + 1, ...details, role: "USER", enabled: true };
        USERS.push(user);
        record("REGISTER", user.id, user.email, user.id);
        return respondWith(withoutPassword(user));
    },
    logout: () => {
        if (me) record("LOGOUT", me.id, me.email, me.id);
        me = null;
        sessionStorage.removeItem("mockUserId");
        return respondWith(null);
    },
    me: () => requireLogin() || respondWith(withoutPassword(me)),
    // like the backend: only the username changes, and a name taken by someone else is a 409
    updateMe: details => {
        if (requireLogin()) return requireLogin();

        const takenBy = USERS.find(user => user.username.toLowerCase() === details.username.toLowerCase());
        if (takenBy && takenBy.id !== me.id) return fail(409, `username '${details.username}' is already taken`);

        me.username = details.username;
        record("PROFILE_UPDATED", me.id, me.email, me.id, "changed username");
        return respondWith(withoutPassword(me));
    },
    // like the backend: a password change ends the session, so the user has to log in again
    changePassword: passwords => {
        if (requireLogin()) return requireLogin();
        if (passwords.currentPassword !== me.password) return fail(400, "current password is incorrect");
        if (passwords.newPassword === me.password) return fail(400, "new password must be different from the current password");

        me.password = passwords.newPassword;
        record("PASSWORD_CHANGED", me.id, me.email, me.id);
        me = null;
        sessionStorage.removeItem("mockUserId");
        return respondWith(null);
    },
    preferences: () => {
        const notAllowed = requireUser();
        if (notAllowed) return notAllowed;

        return respondWith(PREFERENCES[me.id] ?? defaultPreferences());
    },
    // like the backend: fields left out keep their value, and the end result is checked as a whole
    updatePreferences: changes => {
        const notAllowed = requireUser();
        if (notAllowed) return notAllowed;

        const sentFields = Object.fromEntries(Object.entries(changes).filter(([, value]) => value != null));
        const updated = { ...(PREFERENCES[me.id] ?? defaultPreferences()), ...sentFields };

        const hasCompanions = updated.travellingWithChildren || updated.travellingWithElderly;
        if (updated.travelGroup === "SOLO" && hasCompanions) {
            return fail(400, "children or elderly can't be set when travelling solo; change the travel group first");
        }

        updated.updatedAt = new Date().toISOString();
        PREFERENCES[me.id] = updated;
        return respondWith(updated);
    },
    // like the backend: needs the current password, the last admin cannot go, and the user's
    // itineraries (with their activities, impacts and chats) are removed along with the account
    deleteMe: currentPassword => {
        const notLoggedIn = requireLogin();
        if (notLoggedIn) return notLoggedIn;
        if (currentPassword !== me.password) return fail(400, "current password is incorrect");

        const adminCount = USERS.filter(user => user.role === "ADMIN").length;
        if (me.role === "ADMIN" && adminCount <= 1) return fail(409, "the last admin account cannot be deleted");

        removeAccount(me);
        record("ACCOUNT_DELETED", me.id, me.email, me.id, "deleted their own account");
        me = null;
        sessionStorage.removeItem("mockUserId");
        return respondWith(null);
    },
    itineraries: () => requireLogin() || respondWith(ITINERARIES.filter(itinerary => itinerary.userId === me.id)),
    countries: () => requireLogin() || respondWith(COUNTRIES),
    itinerary: id => requireLogin() || respondWith(myItinerary(id) ?? null),
    addItinerary: newItinerary => {
        if (requireLogin()) return requireLogin();
        const country = COUNTRIES.find(candidate => candidate.id === newItinerary.country.id);
        if (!country) return fail(400, "Unknown country.");
        const nextId = Math.max(0, ...ITINERARIES.map(itinerary => itinerary.id)) + 1;
        const itinerary = makeItinerary(nextId, me.id, newItinerary.name, country.name, newItinerary.dateStart, newItinerary.dateEnd);
        ITINERARIES.push(itinerary);
        record("ITINERARY_CREATED", me.id, me.email, me.id, `itinerary ${itinerary.id} '${itinerary.name}'`);
        return respondWith(itinerary);
    },
    // removes the itinerary's activities, impacts, recommendations and chat too
    deleteItinerary: id => {
        const itinerary = myItinerary(id);
        if (requireLogin() || !itinerary) return requireLogin() || fail(404, "Itinerary not found.");
        removeItinerary(itinerary);
        record("ITINERARY_DELETED", me.id, me.email, me.id, `itinerary ${itinerary.id} '${itinerary.name}'`);
        return respondWith(null);
    },
    updateItinerary: (id, changes) => {
        const itinerary = myItinerary(id);
        if (requireLogin() || !itinerary) return requireLogin() || fail(404, "Itinerary not found.");
        const country = COUNTRIES.find(candidate => candidate.id === changes.country.id);
        Object.assign(itinerary, { name: changes.name, dateStart: changes.dateStart, dateEnd: changes.dateEnd, countryId: country.id, countryName: country.name });
        record("ITINERARY_UPDATED", me.id, me.email, me.id, `itinerary ${itinerary.id} '${itinerary.name}'`);
        return respondWith(itinerary);
    },
    itineraryActivities: id => requireLogin() || respondWith(ACTIVITIES.filter(activity => activity.itineraryId === Number(id))),
    activity: id => requireLogin() || respondWith(myActivity(id) ?? null),
    addActivity: newActivity => {
        if (requireLogin() || !myItinerary(newActivity.itinerary.id)) return requireLogin() || fail(404, "Itinerary not found.");
        const nextId = Math.max(0, ...ACTIVITIES.map(activity => activity.id)) + 1;
        const activity = makeActivity(nextId, newActivity.itinerary.id, newActivity.name, newActivity.address, newActivity.dateStart, newActivity.dateEnd);
        ACTIVITIES.push(activity);
        return respondWith(activity);
    },
    updateActivity: (id, changes) => {
        const activity = myActivity(id);
        if (requireLogin() || !activity) return requireLogin() || fail(404, "Activity not found.");
        Object.assign(activity, { name: changes.name, address: changes.address, dateStart: changes.dateStart, dateEnd: changes.dateEnd });
        return respondWith(activity);
    },
    deleteActivity: id => {
        const activity = myActivity(id);
        if (requireLogin() || !activity) return requireLogin() || fail(404, "Activity not found.");
        ACTIVITIES.splice(ACTIVITIES.indexOf(activity), 1);
        return respondWith(null);
    },
    // like the backend: email and username only, the page looks ids up itself
    adminUsers: ({ search = "", page = 0, size = 20 } = {}) => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const term = search.trim().toLowerCase();
        const matches = USERS
            .filter(user => !term || user.email.toLowerCase().includes(term) || user.username.toLowerCase().includes(term))
            .sort((first, second) => first.id - second.id)
            .map(withoutPassword);
        return respondWith(pageOf(matches, page, size));
    },
    adminUser: id => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const account = findUser(id);
        return account ? respondWith(withoutPassword(account)) : userNotFound(id);
    },
    disableUser: id => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;
        if (Number(id) === me.id) return fail(400, "admins cannot disable their own account");

        const account = findUser(id);
        if (!account) return userNotFound(id);
        if (account.role === "ADMIN") return adminMustBeDemoted();

        account.enabled = false;
        record("ACCOUNT_DISABLED", me.id, me.email, account.id, `disabled user '${account.username}'`);
        return respondWith(withoutPassword(account));
    },
    enableUser: id => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const account = findUser(id);
        if (!account) return userNotFound(id);

        account.enabled = true;
        record("ACCOUNT_ENABLED", me.id, me.email, account.id, `enabled user '${account.username}'`);
        return respondWith(withoutPassword(account));
    },
    adminDeleteUser: id => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;
        if (Number(id) === me.id) return fail(400, "admins cannot delete their own account");

        const account = findUser(id);
        if (!account) return userNotFound(id);
        if (account.role === "ADMIN") return adminMustBeDemoted();

        removeAccount(account);
        record("ACCOUNT_DELETED", me.id, me.email, account.id, `deleted user '${account.username}'`);
        return respondWith(null);
    },
    adminCreateUser: ({ username, email, password, role }) => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const taken = USERS.some(user => user.email.toLowerCase() === email.toLowerCase()
            || user.username.toLowerCase() === username.toLowerCase());
        if (taken) return fail(409, "email or username is already taken");

        const account = { id: Math.max(...USERS.map(user => user.id)) + 1, username, email, password, role, enabled: true };
        USERS.push(account);
        record("ACCOUNT_CREATED", me.id, me.email, account.id, `created ${role} account '${username}'`);
        return respondWith(withoutPassword(account));
    },
    // ---------- admin console: any account's trip data ----------
    adminItineraries: userId => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;
        if (!findUser(userId)) return userNotFound(userId);

        const theirs = ITINERARIES.filter(itinerary => itinerary.userId === Number(userId));
        return respondWith([...theirs].sort((first, second) => first.id - second.id));
    },
    adminAddItinerary: (userId, { name, countryId, dateStart, dateEnd }) => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;
        if (!findUser(userId)) return userNotFound(userId);

        const country = COUNTRIES.find(candidate => candidate.id === Number(countryId));
        if (!country) return fail(400, `no country with id ${countryId}`);
        if (endsBeforeItStarts({ dateStart, dateEnd })) return fail(400, "the end cannot be before the start");

        const nextId = Math.max(0, ...ITINERARIES.map(itinerary => itinerary.id)) + 1;
        const itinerary = makeItinerary(nextId, Number(userId), name, country.name, dateStart, dateEnd);
        itinerary.adminEditedAt = new Date().toISOString();
        ITINERARIES.push(itinerary);
        recordByAdmin("ITINERARY_CREATED", itinerary.userId, `itinerary ${itinerary.id} '${itinerary.name}'`);
        tellOwner(itinerary.userId, `An admin added the trip '${itinerary.name}'.`, { itineraryId: itinerary.id });
        return respondWith(itinerary);
    },
    adminUpdateItinerary: (id, { name, countryId, dateStart, dateEnd, keepImpacts }) => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const itinerary = ITINERARIES.find(candidate => candidate.id === Number(id));
        if (!itinerary) return fail(404, `Itinerary with ID: ${id} not found`);
        const country = COUNTRIES.find(candidate => candidate.id === Number(countryId));
        if (!country) return fail(400, `no country with id ${countryId}`);
        if (endsBeforeItStarts({ dateStart, dateEnd })) return fail(400, "the end cannot be before the start");

        const countryChanged = country.id !== itinerary.countryId;
        const renamed = name !== itinerary.name;
        const datesChanged = dateStart !== itinerary.dateStart || dateEnd !== itinerary.dateEnd;
        // like ItineraryServiceImpl: a new name or dates only clear the whole trip verdicts, a new
        // country clears the activity ones too, unless the admin chose to keep them
        let clearedCount = 0;
        if ((countryChanged || renamed || datesChanged) && !keepImpacts) {
            const cause = `itinerary ${itinerary.id} '${name}' changed name, country or dates`;
            const isOutOfDate = impact => countryChanged || impact.activityId == null;
            clearedCount = clearImpacts(itinerary.id, isOutOfDate, cause);
        }

        Object.assign(itinerary, { name, dateStart, dateEnd, countryId: country.id, countryName: country.name });
        markEditedByAdmin(itinerary.id);
        recordByAdmin("ITINERARY_UPDATED", itinerary.userId, `itinerary ${itinerary.id} '${itinerary.name}'`);
        tellOwner(itinerary.userId, `An admin edited your trip '${itinerary.name}'.${clearedNote(clearedCount)}`, { itineraryId: itinerary.id });
        return respondWith(itinerary);
    },
    adminDeleteItinerary: id => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const itinerary = ITINERARIES.find(candidate => candidate.id === Number(id));
        if (!itinerary) return fail(404, `Itinerary with ID: ${id} not found`);

        removeItinerary(itinerary);
        recordByAdmin("ITINERARY_DELETED", itinerary.userId, `itinerary ${itinerary.id} '${itinerary.name}'`);
        tellOwner(itinerary.userId, `An admin deleted your trip '${itinerary.name}'.`,
            { itineraryId: itinerary.id, itineraryName: itinerary.name });
        return respondWith(null);
    },
    adminActivities: itineraryId => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;
        if (ownerOfItinerary(itineraryId) == null) return fail(404, `Itinerary with ID: ${itineraryId} not found`);

        const onTrip = ACTIVITIES.filter(activity => activity.itineraryId === Number(itineraryId));
        return respondWith([...onTrip].sort((first, second) => String(first.dateStart).localeCompare(String(second.dateStart))));
    },
    adminUserActivities: userId => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;
        if (!findUser(userId)) return userNotFound(userId);

        const theirs = ACTIVITIES.filter(activity => ownerOfItinerary(activity.itineraryId) === Number(userId));
        return respondWith([...theirs].sort((first, second) => String(first.dateStart).localeCompare(String(second.dateStart))));
    },
    adminAddActivity: (itineraryId, { name, address, dateStart, dateEnd, category }) => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const ownerId = ownerOfItinerary(itineraryId);
        if (ownerId == null) return fail(404, `Itinerary with ID: ${itineraryId} not found`);
        if (endsBeforeItStarts({ dateStart, dateEnd })) return fail(400, "the end cannot be before the start");

        const nextId = Math.max(0, ...ACTIVITIES.map(activity => activity.id)) + 1;
        const activity = { ...makeActivity(nextId, Number(itineraryId), name, address, dateStart, dateEnd), category };
        ACTIVITIES.push(activity);
        markEditedByAdmin(activity.itineraryId);
        recordByAdmin("ACTIVITY_CREATED", ownerId, `activity ${activity.id} '${activity.name}'`);
        tellOwner(ownerId, `An admin added ${activityOnTrip(activity)}.`, { itineraryId: activity.itineraryId });
        return respondWith(activity);
    },
    adminUpdateActivity: (id, { name, address, dateStart, dateEnd, category, keepImpacts }) => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const activity = ACTIVITIES.find(candidate => candidate.id === Number(id));
        if (!activity) return fail(404, `Activity with ID: ${id} not found`);
        if (endsBeforeItStarts({ dateStart, dateEnd })) return fail(400, "the end cannot be before the start");

        const judgedFieldsChanged = name !== activity.name
            || (address || null) !== (activity.address || null)
            || dateStart !== activity.dateStart;
        let clearedCount = 0;
        if (judgedFieldsChanged && !keepImpacts) {
            const cause = `activity ${activity.id} '${name}' changed name, address or start`;
            clearedCount = clearImpacts(activity.itineraryId, impact => impact.activityId === activity.id, cause);
        }

        Object.assign(activity, { name, address, dateStart, dateEnd, category });
        markEditedByAdmin(activity.itineraryId);
        recordByAdmin("ACTIVITY_UPDATED", ownerOfItinerary(activity.itineraryId), `activity ${activity.id} '${activity.name}'`);
        tellOwner(ownerOfItinerary(activity.itineraryId), `An admin edited ${activityOnTrip(activity)}.${clearedNote(clearedCount)}`,
            { itineraryId: activity.itineraryId });
        return respondWith(activity);
    },
    // the impacts on it go too, with their alerts and recommendations
    adminDeleteActivity: id => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const activity = ACTIVITIES.find(candidate => candidate.id === Number(id));
        if (!activity) return fail(404, `Activity with ID: ${id} not found`);

        const onActivity = (IMPACTS[activity.itineraryId] ?? []).filter(impact => impact.activityId === activity.id);
        for (const impact of onActivity) removeImpact(activity.itineraryId, impact);

        ACTIVITIES.splice(ACTIVITIES.indexOf(activity), 1);
        markEditedByAdmin(activity.itineraryId);
        recordByAdmin("ACTIVITY_DELETED", ownerOfItinerary(activity.itineraryId), `activity ${activity.id} '${activity.name}'`);
        tellOwner(ownerOfItinerary(activity.itineraryId), `An admin deleted ${activityOnTrip(activity)}.`, { itineraryId: activity.itineraryId });
        return respondWith(null);
    },
    adminImpacts: userId => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;
        if (!findUser(userId)) return userNotFound(userId);

        const theirTrips = ITINERARIES.filter(itinerary => itinerary.userId === Number(userId));
        const theirImpacts = theirTrips.flatMap(itinerary =>
            (IMPACTS[itinerary.id] ?? []).map(impact => adminImpact(itinerary.id, impact)));
        return respondWith(theirImpacts.sort((first, second) => second.id - first.id));
    },
    adminAddImpact: (userId, { newsId, itineraryId, activityId, impacted, reason, rejected, sendAlert }) => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;
        if (!findUser(userId)) return userNotFound(userId);

        // same checks, and the same 400s, as the backend
        const ownerId = ownerOfItinerary(itineraryId);
        if (ownerId == null) return fail(400, `no itinerary with id ${itineraryId}`);
        if (ownerId !== Number(userId)) return fail(400, `itinerary ${itineraryId} does not belong to user ${userId}`);

        let activity = null;
        if (activityId != null) {
            activity = ACTIVITIES.find(candidate => candidate.id === Number(activityId));
            if (!activity) return fail(400, `no activity with id ${activityId}`);
            if (activity.itineraryId !== Number(itineraryId)) return fail(400, `activity ${activityId} is not on itinerary ${itineraryId}`);
        }
        if (!NEWS[newsId]) return fail(400, `no news with id ${newsId}`);
        if (sendAlert && (!impacted || rejected || !reason)) {
            return fail(400, "an alert needs an impacted, not rejected impact with a reason");
        }

        const onSamePair = impact => impact.newsId === Number(newsId) && impact.activityId === (activity?.id ?? null);
        const existing = (IMPACTS[itineraryId] ?? []).find(onSamePair);
        if (existing) {
            const on = activity ? "this activity" : "this trip";
            return fail(409, `impact ${existing.id} already covers this news on ${on}, edit that one instead`);
        }

        const allIds = Object.values(IMPACTS).flat().map(impact => impact.id);
        const impact = makeImpact(Math.max(0, ...allIds) + 1, impacted, Number(newsId), activity?.id ?? null, activity?.name ?? null, reason);
        impact.rejected = Boolean(rejected);
        IMPACTS[itineraryId] ??= [];
        IMPACTS[itineraryId].push(impact);

        if (sendAlert) {
            const nextAlertId = Math.max(0, ...ALERTS.map(alert => alert.id)) + 1;
            ALERTS.push({ id: nextAlertId, impactId: impact.id, createdAt: new Date().toISOString(), readAt: null });
        }
        recordByAdmin("IMPACT_CREATED", ownerId, `impact ${impact.id} (${impacted ? "impacted" : "not impacted"})`);
        tellOwner(ownerId, `An admin linked the news ${newsNameOf(impact)} to ${placeOfImpact(itineraryId, impact)}.`,
            { itineraryId, newsId: impact.newsId });
        return respondWith(adminImpact(Number(itineraryId), impact));
    },
    adminUpdateImpact: (id, { impacted, reason, rejected }) => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const entry = findImpactEntry(id);
        if (!entry) return fail(404, `Could not find impact ${id}`);

        const raisedAnAlert = ALERTS.some(alert => alert.impactId === entry.impact.id);
        if (raisedAnAlert && !reason?.trim()) return fail(400, "this impact has or is about to raise an alert, so it needs a reason");

        Object.assign(entry.impact, { impacted, reason, rejected: Boolean(rejected) });
        recordByAdmin("IMPACT_UPDATED", ownerOfItinerary(entry.itineraryId), `impact ${entry.impact.id} (${impacted ? "impacted" : "not impacted"})`);
        tellOwner(ownerOfItinerary(entry.itineraryId),
            `An admin changed how the news ${newsNameOf(entry.impact)} affects ${placeOfImpact(entry.itineraryId, entry.impact)}.`,
            { itineraryId: entry.itineraryId, newsId: entry.impact.newsId });
        return respondWith(adminImpact(entry.itineraryId, entry.impact));
    },
    adminDeleteImpact: id => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const entry = findImpactEntry(id);
        if (!entry) return fail(404, `Could not find impact ${id}`);

        removeImpact(entry.itineraryId, entry.impact);
        recordByAdmin("IMPACT_DELETED", ownerOfItinerary(entry.itineraryId), `impact ${entry.impact.id}`);
        tellOwner(ownerOfItinerary(entry.itineraryId),
            `An admin removed the news ${newsNameOf(entry.impact)} from ${placeOfImpact(entry.itineraryId, entry.impact)}.`,
            { itineraryId: entry.itineraryId, newsId: entry.impact.newsId });
        return respondWith(null);
    },
    adminRecommendations: userId => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;
        if (!findUser(userId)) return userNotFound(userId);

        const theirTrips = ITINERARIES.filter(itinerary => itinerary.userId === Number(userId));
        const theirs = theirTrips.flatMap(itinerary => (RECOMMENDATIONS[itinerary.id] ?? []).map(adminRecommendation));
        return respondWith(theirs.sort((first, second) => second.id - first.id));
    },
    adminAddRecommendation: (impactId, { severity, action, advice, optionId, status }) => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const entry = findImpactEntry(impactId);
        if (!entry) return fail(404, `Could not find impact ${impactId}`);
        const option = optionId == null ? null : allOptions().find(candidate => candidate.id === Number(optionId));
        if (optionId != null && !option) return fail(400, `no activity option with id ${optionId}`);

        const recommendation = makeRecommendation(nextRecommendationId++, entry.impact, severity, action, advice, option);
        const chosenStatus = status ?? "PENDING";
        Object.assign(recommendation, {
            itineraryId: entry.itineraryId,
            status: chosenStatus,
            decidedAt: chosenStatus === "PENDING" ? null : new Date().toISOString(),
        });
        RECOMMENDATIONS[entry.itineraryId] ??= [];
        RECOMMENDATIONS[entry.itineraryId].push(recommendation);

        recordByAdmin("RECOMMENDATION_CREATED", ownerOfItinerary(entry.itineraryId), `recommendation ${recommendation.id} (${action})`);
        tellOwner(ownerOfItinerary(entry.itineraryId), `An admin added a suggestion for ${placeOfImpact(entry.itineraryId, entry.impact)}.`,
            { itineraryId: entry.itineraryId, newsId: entry.impact.newsId });
        return respondWith(adminRecommendation(recommendation));
    },
    adminUpdateRecommendation: (id, { severity, action, advice, optionId, status }) => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const entry = findRecommendationEntry(id);
        if (!entry) return fail(404, `No recommendation with id ${id}`);
        const option = optionId == null ? null : allOptions().find(candidate => candidate.id === Number(optionId));
        if (optionId != null && !option) return fail(400, `no activity option with id ${optionId}`);

        const recommendation = entry.recommendation;
        // like the backend, a missing status keeps the current one, and the decision time only
        // moves when the status really changes
        const newStatus = status ?? recommendation.status ?? "PENDING";
        if (newStatus !== (recommendation.status ?? "PENDING")) {
            recommendation.decidedAt = newStatus === "PENDING" ? null : new Date().toISOString();
        }
        Object.assign(recommendation, {
            severity, action, advice, status: newStatus,
            optionId: option?.id ?? null,
            optionName: option?.name ?? null,
            optionArea: option?.area ?? null,
            optionAddress: option?.address ?? null,
        });

        recordByAdmin("RECOMMENDATION_UPDATED", ownerOfItinerary(entry.itineraryId), `recommendation ${recommendation.id} (${action})`);
        tellOwner(ownerOfItinerary(entry.itineraryId), `An admin changed a suggestion for ${placeOfImpact(entry.itineraryId, recommendation)}.`,
            { itineraryId: entry.itineraryId });
        return respondWith(adminRecommendation(recommendation));
    },
    adminDeleteRecommendation: id => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const entry = findRecommendationEntry(id);
        if (!entry) return fail(404, `No recommendation with id ${id}`);

        const list = RECOMMENDATIONS[entry.itineraryId];
        list.splice(list.indexOf(entry.recommendation), 1);
        recordByAdmin("RECOMMENDATION_DELETED", ownerOfItinerary(entry.itineraryId), `recommendation ${entry.recommendation.id} (${entry.recommendation.action})`);
        tellOwner(ownerOfItinerary(entry.itineraryId), `An admin removed a suggestion for ${placeOfImpact(entry.itineraryId, entry.recommendation)}.`,
            { itineraryId: entry.itineraryId });
        return respondWith(null);
    },
    // the delete alls: one audit entry and one note to the traveller per batch, like the backend
    adminDeleteAllItineraries: userId => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const theirs = ITINERARIES.filter(itinerary => itinerary.userId === Number(userId));
        for (const itinerary of theirs) removeItinerary(itinerary);

        if (theirs.length) {
            recordByAdmin("ITINERARY_DELETED", Number(userId), `all ${theirs.length} itineraries`);
            tellOwner(Number(userId), "An admin deleted all your trips.");
        }
        return respondWith({ deleted: theirs.length });
    },
    adminDeleteAllActivities: itineraryId => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const itinerary = ITINERARIES.find(candidate => candidate.id === Number(itineraryId));
        if (!itinerary) return fail(404, `Itinerary with ID: ${itineraryId} not found`);

        const onTrip = ACTIVITIES.filter(activity => activity.itineraryId === itinerary.id);
        const activityIds = new Set(onTrip.map(activity => activity.id));
        const onTheirActivities = (IMPACTS[itinerary.id] ?? []).filter(impact => activityIds.has(impact.activityId));
        for (const impact of onTheirActivities) removeImpact(itinerary.id, impact);
        for (const activity of onTrip) ACTIVITIES.splice(ACTIVITIES.indexOf(activity), 1);

        if (onTrip.length) {
            markEditedByAdmin(itinerary.id);
            recordByAdmin("ACTIVITY_DELETED", itinerary.userId, `all ${onTrip.length} activities on itinerary ${itinerary.id} '${itinerary.name}'`);
            tellOwner(itinerary.userId, `An admin deleted every activity on your trip '${itinerary.name}'.`, { itineraryId: itinerary.id });
        }
        return respondWith({ deleted: onTrip.length });
    },
    adminDeleteAllImpacts: itineraryId => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const itinerary = ITINERARIES.find(candidate => candidate.id === Number(itineraryId));
        if (!itinerary) return fail(404, `Itinerary with ID: ${itineraryId} not found`);

        const onTrip = [...(IMPACTS[itinerary.id] ?? [])];
        for (const impact of onTrip) removeImpact(itinerary.id, impact);

        if (onTrip.length) {
            recordByAdmin("IMPACT_DELETED", itinerary.userId, `all ${onTrip.length} impacts on itinerary ${itinerary.id} '${itinerary.name}'`);
            tellOwner(itinerary.userId, `An admin removed all the news from your trip '${itinerary.name}'.`, { itineraryId: itinerary.id });
        }
        return respondWith({ deleted: onTrip.length });
    },
    adminDeleteWholeTripImpacts: itineraryId => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const itinerary = ITINERARIES.find(candidate => candidate.id === Number(itineraryId));
        if (!itinerary) return fail(404, `Itinerary with ID: ${itineraryId} not found`);

        const onWholeTrip = (IMPACTS[itinerary.id] ?? []).filter(impact => impact.activityId == null);
        for (const impact of onWholeTrip) removeImpact(itinerary.id, impact);

        if (onWholeTrip.length) {
            recordByAdmin("IMPACT_DELETED", itinerary.userId, `all ${onWholeTrip.length} whole trip impacts on itinerary ${itinerary.id} '${itinerary.name}'`);
            tellOwner(itinerary.userId, `An admin removed all the news affecting your trip '${itinerary.name}' as a whole.`,
                { itineraryId: itinerary.id });
        }
        return respondWith({ deleted: onWholeTrip.length });
    },
    adminDeleteActivityImpacts: activityId => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const activity = ACTIVITIES.find(candidate => candidate.id === Number(activityId));
        if (!activity) return fail(404, `Activity with ID: ${activityId} not found`);

        const onActivity = (IMPACTS[activity.itineraryId] ?? []).filter(impact => impact.activityId === activity.id);
        for (const impact of onActivity) removeImpact(activity.itineraryId, impact);

        const ownerId = ownerOfItinerary(activity.itineraryId);
        if (onActivity.length) {
            recordByAdmin("IMPACT_DELETED", ownerId, `all ${onActivity.length} impacts on activity ${activity.id} of itinerary ${activity.itineraryId}`);
            tellOwner(ownerId, `An admin removed all the news from an activity on your trip '${tripNameOf(activity.itineraryId)}'.`,
                { itineraryId: activity.itineraryId });
        }
        return respondWith({ deleted: onActivity.length });
    },
    adminDeleteAllRecommendations: userId => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const theirTrips = ITINERARIES.filter(itinerary => itinerary.userId === Number(userId));
        let count = 0;
        for (const itinerary of theirTrips) {
            count += (RECOMMENDATIONS[itinerary.id] ?? []).length;
            RECOMMENDATIONS[itinerary.id] = [];
        }

        if (count) {
            recordByAdmin("RECOMMENDATION_DELETED", Number(userId), `all ${count} recommendations`);
            tellOwner(Number(userId), "An admin removed all the suggestions on your trips.");
        }
        return respondWith({ deleted: count });
    },
    // like the backend: not on yourself, and the last enabled admin cannot be made a user
    changeUserRole: (id, role) => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;
        if (Number(id) === me.id) return fail(400, "admins cannot change their own role");

        const account = findUser(id);
        if (!account) return userNotFound(id);

        const enabledAdmins = USERS.filter(user => user.role === "ADMIN" && user.enabled).length;
        const isLastAdmin = account.role === "ADMIN" && role !== "ADMIN" && account.enabled && enabledAdmins <= 1;
        if (isLastAdmin) return fail(409, "the last enabled admin cannot be demoted");

        const previousRole = account.role;
        account.role = role;
        record("ROLE_CHANGED", me.id, me.email, account.id, `role changed from ${previousRole} to ${role}`);
        return respondWith(withoutPassword(account));
    },
    // kept after the account is deleted, like on the backend
    userActivity: (id, { action = "", page = 0 } = {}) => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const userId = Number(id);
        const entries = AUDIT.filter(entry =>
            (entry.actorId === userId || entry.targetId === userId) && (!action || entry.action === action));
        return respondWith(pageOf(newestFirst(entries), page));
    },
    auditLog: ({ email = "", action = "", page = 0 } = {}) => {
        const notAdmin = requireAdmin();
        if (notAdmin) return notAdmin;

        const term = email.trim().toLowerCase();
        const entries = AUDIT.filter(entry =>
            (!term || (entry.actorEmail ?? "").toLowerCase().includes(term)) && (!action || entry.action === action));
        return respondWith(pageOf(newestFirst(entries), page));
    },
    // like the backend: a plain list for one page, and a short page means it is the last one
    news: ({ page = 0, size = 20 } = {}) => {
        const notLoggedIn = requireLogin();
        if (notLoggedIn) return notLoggedIn;

        const start = Math.max(page, 0) * size;
        return respondWith(newsNewestFirst().slice(start, start + size));
    },
    itineraryImpacts: id => requireLogin() || respondWith(IMPACTS[id] || []),
    // like the backend: newest first, and unreadCount counts every unread alert, not just this page.
    // beforeId leaves out that alert and every newer one, and nextBeforeId is null once nothing older is left
    alerts: ({ unreadOnly = false, beforeId = null, size = 20 } = {}) => {
        const notLoggedIn = requireLogin();
        if (notLoggedIn) return notLoggedIn;

        const mine = [...ALERTS].sort((first, second) => second.id - first.id).map(visibleAlert).filter(Boolean);
        const matching = unreadOnly ? mine.filter(alert => alert.readAt == null) : mine;
        const listed = beforeId == null ? matching : matching.filter(alert => alert.id < beforeId);

        const pageSize = Math.min(100, Math.max(1, size));
        const content = listed.slice(0, pageSize);
        const hasMore = listed.length > pageSize;
        return respondWith({
            content,
            hasMore,
            nextBeforeId: hasMore ? content.at(-1).id : null,
            unreadCount: mine.filter(alert => alert.readAt == null).length,
        });
    },
    // like the backend: owner only, and marking an already read alert changes nothing
    markAlertRead: id => {
        const notLoggedIn = requireLogin();
        if (notLoggedIn) return notLoggedIn;

        const alert = ALERTS.find(candidate => candidate.id === Number(id));
        if (!alert || !visibleAlert(alert)) return fail(404, "Alert not found.");
        alert.readAt ??= new Date().toISOString();
        return respondWith(visibleAlert(alert));
    },
    // Read the persisted news/trip group through the selected alert, including older pages.
    markAlertGroupRead: id => {
        const notLoggedIn = requireLogin();
        if (notLoggedIn) return notLoggedIn;
        const anchor = ALERTS.find(candidate => candidate.id === Number(id));
        const group = anchor && visibleAlert(anchor);
        if (!group) return fail(404, "Alert not found.");
        const now = new Date().toISOString();
        for (const alert of ALERTS) {
            const visible = visibleAlert(alert);
            if (visible && visible.itineraryId === group.itineraryId && visible.newsId === group.newsId
                    && alert.id <= anchor.id) alert.readAt ??= now;
        }
        return respondWith({ unreadCount: ALERTS.filter(alert => alert.readAt == null && visibleAlert(alert)).length });
    // like the backend: the newest 20 of this user's admin changes, and how many are unread
    adminChanges: () => {
        const notLoggedIn = requireLogin();
        if (notLoggedIn) return notLoggedIn;

        const mine = newestFirst(ADMIN_CHANGES.filter(change => change.userId === me.id));
        const unreadCount = mine.filter(change => change.readAt == null).length;
        return respondWith({ content: mine.slice(0, 20).map(adminChangeView), unreadCount });
    },
    markAdminChangeRead: id => {
        const notLoggedIn = requireLogin();
        if (notLoggedIn) return notLoggedIn;

        const mine = ADMIN_CHANGES.filter(change => change.userId === me.id);
        const change = mine.find(candidate => candidate.id === Number(id));
        if (change) change.readAt ??= new Date().toISOString();
        return respondWith({ unreadCount: mine.filter(candidate => candidate.readAt == null).length });
    },
    markAdminChangesRead: (upToId = null) => {
        const notLoggedIn = requireLogin();
        if (notLoggedIn) return notLoggedIn;

        const now = new Date().toISOString();
        const mine = ADMIN_CHANGES.filter(change => change.userId === me.id);
        for (const change of mine) {
            const isCovered = upToId == null || change.id <= upToId;
            if (isCovered) change.readAt ??= now;
        }
        return respondWith({ unreadCount: mine.filter(change => change.readAt == null).length });
    },
    // like the backend: every unread alert of this user up to upToId, whichever page it is on,
    // and the unreadCount left afterwards since newer alerts stay unread
    markAllAlertsRead: (upToId = null) => {
        const notLoggedIn = requireLogin();
        if (notLoggedIn) return notLoggedIn;

        const now = new Date().toISOString();
        for (const alert of ALERTS) {
            const isCovered = upToId == null || alert.id <= upToId;
            if (isCovered && visibleAlert(alert)) alert.readAt ??= now;
        }

        const unreadCount = ALERTS.filter(alert => alert.readAt == null && visibleAlert(alert)).length;
        return respondWith({ unreadCount });
    },
    // like the backend, an impact on someone else's itinerary looks the same as one that does not exist
    setImpactRejected: (id, rejected) => {
        if (requireLogin()) return requireLogin();

        const itineraryId = Object.keys(IMPACTS).find(key => IMPACTS[key].some(impact => impact.id === Number(id)));
        if (!itineraryId || !myItinerary(itineraryId)) return fail(404, "Impact not found.");

        const impact = IMPACTS[itineraryId].find(impact => impact.id === Number(id));
        impact.rejected = rejected;
        return respondWith(impact);
    },
    // stored ones only, including rows for impacts rejected later
    itineraryRecommendations: id => {
        if (requireLogin() || !myItinerary(id)) return requireLogin() || fail(404, "Itinerary not found.");
        return respondWith(worstFirst(RECOMMENDATIONS[id] ?? []));
    },
    // one per current impact, reusing any already made, and slow like the real model
    generateRecommendations: itineraryId => {
        const itinerary = myItinerary(itineraryId);
        if (requireLogin() || !itinerary) return requireLogin() || fail(404, "Itinerary not found.");

        const stored = RECOMMENDATIONS[itinerary.id] ?? (RECOMMENDATIONS[itinerary.id] = []);
        const currentImpacts = (IMPACTS[itinerary.id] ?? []).filter(impact => impact.impacted && !impact.rejected);
        const options = OPTIONS[itinerary.countryName] ?? [];

        const current = currentImpacts.map(impact => {
            const existing = stored.find(recommendation => recommendation.impactId === impact.id);
            if (existing) return existing;

            const answer = MOCK_ANSWERS[impact.id];
            let made;
            if (answer) {
                const option = answer.optionIndex == null ? null : options[answer.optionIndex];
                made = makeRecommendation(nextRecommendationId++, impact, answer.severity, answer.action, answer.advice, option);
            } else if (impact.activityId == null) {
                made = makeRecommendation(nextRecommendationId++, impact, "MEDIUM", "MONITOR",
                    "Affects the whole trip, so follow local updates and keep the plans flexible.");
            } else {
                const option = options[0];
                made = makeRecommendation(nextRecommendationId++, impact, "MEDIUM", "REPLACE",
                    `(mock advice) ${impact.activityName} may be disrupted. Swap it for ${option?.name ?? "something nearby"}.`,
                    option);
            }
            stored.push(made);
            return made;
        });

        return new Promise(resolve => setTimeout(resolve, 1500)).then(() => respondWith(worstFirst(current)));
    },
    // conversations are kept per itinerary, like the backend's chat memory
    chat: (itineraryId, message) => {
        if (requireLogin() || !myItinerary(itineraryId)) return requireLogin() || fail(404, "Itinerary not found.");

        const reply = {
            role: "assistant",
            content: `(mock reply) You asked about itinerary ${itineraryId}: "${message}". I would check the affected activities and suggest alternatives.`,
        };
        // like the backend: one question at a time, and the answer is saved even if the page has gone
        if (ANSWERING.has(Number(itineraryId))) {
            return fail(409, "The assistant is still answering your last question. Wait for that answer first.");
        }
        const conversation = CHATS[itineraryId] ?? (CHATS[itineraryId] = []);
        conversation.push({ role: "user", content: message });
        ANSWERING.add(Number(itineraryId));

        return new Promise(resolve => setTimeout(() => {
            conversation.push(reply);
            ANSWERING.delete(Number(itineraryId));
            resolve(structuredClone(reply));
        }, 700));
    },
    chatStatus: itineraryId => {
        if (requireLogin() || !myItinerary(itineraryId)) return requireLogin() || fail(404, "Itinerary not found.");
        return respondWith({ answering: ANSWERING.has(Number(itineraryId)) });
    },
    chatHistory: itineraryId => {
        if (requireLogin() || !myItinerary(itineraryId)) return requireLogin() || fail(404, "Itinerary not found.");
        return respondWith(CHATS[itineraryId] ?? []);
    },
    resetChat: itineraryId => {
        if (requireLogin() || !myItinerary(itineraryId)) return requireLogin() || fail(404, "Itinerary not found.");
        delete CHATS[itineraryId];
        return respondWith(null);
    },
};

export const showMockBanner = () => document.body.insertAdjacentHTML("afterbegin",
    `<div style="background:#ae86f7;color:#fff;padding:6px 12px;font:600 14px system-ui">MOCK MODE: fake data, no real backend. Logins: john@example.com / password123, admin@example.com / admin123</div>`);
