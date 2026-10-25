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
    { id: 6, username: "dave", email: "dave@example.com", password: "password123", role: "USER" },
];
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
// news id -> the article fields an impact carries. 3 has no source so the label falls back to the
// link's site name, and 2 has no link so its bubble isn't clickable
const NEWS = {
    1: { title: "Magnitude 6.8 earthquake strikes near Hokkaido", source: "Japan Meteorological Agency",
        url: "https://www.jma.go.jp/jma/indexe.html", publishedAt: "2026-12-14T08:30:00",
        description: "A strong earthquake hit southern Hokkaido early this morning. Rail services are suspended while tracks are inspected, and several ski resorts have paused lifts." },
    2: { title: "Universal Studios Singapore to close for maintenance", source: "The Straits Times",
        url: null, publishedAt: "2026-12-30T10:00:00", description: null },
    3: { title: "Cherry blossom forecast: early bloom & record crowds", source: null,
        url: "https://www.japan-guide.com/sakura", publishedAt: "2027-02-20T09:00:00",
        description: "Forecasters expect blossoms in Tokyo up to a week early, with record visitor numbers at popular parks." },
    4: { title: "Flooding closes Bangkok tourist areas", source: "Bangkok Post",
        url: "https://www.bangkokpost.com/", publishedAt: "2026-11-01T14:00:00",
        description: "Heavy rain has flooded parts of central Bangkok, closing some attractions near the river." },
    5: { title: "Grand Palace closed for royal ceremony", source: "Bangkok Post",
        url: "https://www.bangkokpost.com/", publishedAt: "2026-10-28T07:00:00", description: null },
};
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
    };
};
const IMPACTS = {
    1: [makeImpact(1, true, 1, 1, "Ski at Niseko", "Some ski resorts suspending lifts."),
    makeImpact(2, true, 1, 2, "Onsen visit", null),
    makeImpact(3, true, 1, null, null, "Flights into Sapporo may be delayed.")],
    3: [makeImpact(6, true, 2, 4, "Universal Studios Singapore", "Dates to be confirmed.")],
    6: [makeImpact(11, true, 3, null, null, "Expect queues at popular parks in late March.")],
    2: [makeImpact(4, true, 4, 7, "Grand Palace visit", "Some attractions closed."),
    makeImpact(5, false, 5, 7, "Grand Palace visit", "Closure is before your visit.")],
    // itinerary 9 has no impacts, to test the empty state
};

// itinerary id -> [{ role, content }], oldest first. john's ski trip starts with a short conversation
const CHATS = {
    1: [
        { role: "user", content: "Is my ski trip affected by anything?" },
        { role: "assistant", content: "(mock reply) The Hokkaido earthquake may affect Ski at Niseko, as some resorts have suspended lifts. Check with the resort before you go." },
    ],
};

const myItinerary = id => ITINERARIES.find(itinerary => itinerary.id === Number(id) && itinerary.userId === me?.id);
const myActivity = id => ACTIVITIES.find(activity => activity.id === Number(id) && myItinerary(activity.itineraryId));

export const mockApi = {
    login: (email, password) => {
        const user = USERS.find(candidate => candidate.email.toLowerCase() === email.toLowerCase() && candidate.password === password);
        if (!user) return fail(401, "Invalid email or password.");
        me = user; sessionStorage.setItem("mockUserId", user.id); return respondWith(null);
    },
    register: details => {
        const taken = field => USERS.some(user => user[field].toLowerCase() === details[field].toLowerCase());
        if (taken("email")) return fail(409, `email '${details.email}' is already taken`);
        if (taken("username")) return fail(409, `username '${details.username}' is already taken`);
        const user = { id: Math.max(...USERS.map(user => user.id)) + 1, ...details, role: "USER" };
        USERS.push(user);
        return respondWith(withoutPassword(user));
    },
    logout: () => { me = null; sessionStorage.removeItem("mockUserId"); return respondWith(null); },
    me: () => requireLogin() || respondWith(withoutPassword(me)),
    // like the backend: only the username changes, and a name taken by someone else is a 409
    updateMe: details => {
        if (requireLogin()) return requireLogin();

        const takenBy = USERS.find(user => user.username.toLowerCase() === details.username.toLowerCase());
        if (takenBy && takenBy.id !== me.id) return fail(409, `username '${details.username}' is already taken`);

        me.username = details.username;
        return respondWith(withoutPassword(me));
    },
    // like the backend: a password change ends the session, so the user has to log in again
    changePassword: passwords => {
        if (requireLogin()) return requireLogin();
        if (passwords.currentPassword !== me.password) return fail(400, "current password is incorrect");
        if (passwords.newPassword === me.password) return fail(400, "new password must be different from the current password");

        me.password = passwords.newPassword;
        me = null;
        sessionStorage.removeItem("mockUserId");
        return respondWith(null);
    },
    // like the backend: needs the current password, the last admin cannot go, and the user's
    // itineraries (with their activities, impacts and chats) are removed along with the account
    deleteMe: currentPassword => {
        const notLoggedIn = requireLogin();
        if (notLoggedIn) return notLoggedIn;
        if (currentPassword !== me.password) return fail(400, "current password is incorrect");

        const adminCount = USERS.filter(user => user.role === "ADMIN").length;
        if (me.role === "ADMIN" && adminCount <= 1) return fail(409, "the last admin account cannot be deleted");

        const ownedItineraries = ITINERARIES.filter(itinerary => itinerary.userId === me.id);
        for (const itinerary of ownedItineraries) {
            ITINERARIES.splice(ITINERARIES.indexOf(itinerary), 1);
            for (let index = ACTIVITIES.length - 1; index >= 0; index--) {
                if (ACTIVITIES[index].itineraryId === itinerary.id) ACTIVITIES.splice(index, 1);
            }
            delete IMPACTS[itinerary.id];
            delete CHATS[itinerary.id];
        }

        USERS.splice(USERS.indexOf(me), 1);
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
        return respondWith(itinerary);
    },
    // Removes the itinerary's activities and impacts with it.
    deleteItinerary: id => {
        const itinerary = myItinerary(id);
        if (requireLogin() || !itinerary) return requireLogin() || fail(404, "Itinerary not found.");
        ITINERARIES.splice(ITINERARIES.indexOf(itinerary), 1);
        for (let index = ACTIVITIES.length - 1; index >= 0; index--) {
            if (ACTIVITIES[index].itineraryId === itinerary.id) ACTIVITIES.splice(index, 1);
        }
        delete IMPACTS[itinerary.id];
        return respondWith(null);
    },
    updateItinerary: (id, changes) => {
        const itinerary = myItinerary(id);
        if (requireLogin() || !itinerary) return requireLogin() || fail(404, "Itinerary not found.");
        const country = COUNTRIES.find(candidate => candidate.id === changes.country.id);
        Object.assign(itinerary, { name: changes.name, dateStart: changes.dateStart, dateEnd: changes.dateEnd, countryId: country.id, countryName: country.name });
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
    adminUsers: () => requireLogin() || (me.role !== "ADMIN"
        ? fail(403, "Admins only.")
        : respondWith(USERS.map(withoutPassword))),
    itineraryImpacts: id => requireLogin() || respondWith(IMPACTS[id] || []),
    // conversations are kept per itinerary, like the backend's chat memory
    chat: (itineraryId, message) => {
        if (requireLogin() || !myItinerary(itineraryId)) return requireLogin() || fail(404, "Itinerary not found.");

        const reply = {
            role: "assistant",
            content: `(mock reply) You asked about itinerary ${itineraryId}: "${message}". I would check the affected activities and suggest alternatives.`,
        };
        const conversation = CHATS[itineraryId] ?? (CHATS[itineraryId] = []);
        conversation.push({ role: "user", content: message }, reply);

        return new Promise(resolve => setTimeout(() => resolve(structuredClone(reply)), 700));
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
