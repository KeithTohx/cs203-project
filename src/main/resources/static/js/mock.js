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

// the audit log, oldest first like ids on the backend. the admin pages read it newest first.
// same fields as AuditEntryResponse, and like the backend it stores plain ids so it outlives deleted accounts
const AUDIT = [];
const record = (action, actorId, actorEmail, targetId, details = null, occurredAt = new Date().toISOString()) =>
    AUDIT.push({ id: AUDIT.length + 1, occurredAt, actorId, actorEmail, action, targetId, details });

// a little history so the admin pages are not empty on first load
record("REGISTER", 1, "john@example.com", 1, null, "2026-10-01T09:12:00");
record("LOGIN", 1, "john@example.com", 1, null, "2026-10-01T09:13:00");
record("ITINERARY_CREATED", 1, "john@example.com", 1, "itinerary 1 'Japan Ski Trip'", "2026-10-01T09:20:00");
record("LOGIN_FAILED", null, "alice@example.com", 2, null, "2026-10-12T22:41:00");
record("LOGIN", 2, "alice@example.com", 2, null, "2026-10-12T22:42:00");
record("ITINERARY_UPDATED", 2, "alice@example.com", 2, "itinerary 2 'Thailand Beach Holiday'", "2026-10-12T22:50:00");
record("ACCOUNT_DISABLED", 3, "admin@example.com", 6, "disabled user 'dave'", "2026-10-19T17:30:00");
record("LOGIN_FAILED", null, "dave@example.com", 6, "account is disabled", "2026-10-20T08:05:00");

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

// Returns a rejected promise unless an admin is logged in, otherwise null.
const requireAdmin = () => requireLogin() || (me.role !== "ADMIN" ? fail(403, "Admins only.") : null);
// same idea for things only normal users have, like preferences
const requireUser = () => requireLogin() || (me.role !== "USER" ? fail(403, "Only for user accounts.") : null);
const findUser = id => USERS.find(user => user.id === Number(id));
const userNotFound = id => fail(404, `No user found with id ${id}`);

// the account and everything it owns: itineraries with their activities, impacts and chats, and its preferences
const removeAccount = account => {
    const ownedItineraries = ITINERARIES.filter(itinerary => itinerary.userId === account.id);
    for (const itinerary of ownedItineraries) {
        ITINERARIES.splice(ITINERARIES.indexOf(itinerary), 1);
        for (let index = ACTIVITIES.length - 1; index >= 0; index--) {
            if (ACTIVITIES[index].itineraryId === itinerary.id) ACTIVITIES.splice(index, 1);
        }
        delete IMPACTS[itinerary.id];
        delete CHATS[itinerary.id];
    }

    delete PREFERENCES[account.id];
    USERS.splice(USERS.indexOf(account), 1);
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
    // Removes the itinerary's activities and impacts with it.
    deleteItinerary: id => {
        const itinerary = myItinerary(id);
        if (requireLogin() || !itinerary) return requireLogin() || fail(404, "Itinerary not found.");
        ITINERARIES.splice(ITINERARIES.indexOf(itinerary), 1);
        for (let index = ACTIVITIES.length - 1; index >= 0; index--) {
            if (ACTIVITIES[index].itineraryId === itinerary.id) ACTIVITIES.splice(index, 1);
        }
        delete IMPACTS[itinerary.id];
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
    // like the backend: search matches part of the email or username, in id order
    // like the backend: only email and username are searched, the page looks ids up itself
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

        removeAccount(account);
        record("ACCOUNT_DELETED", me.id, me.email, account.id, `deleted user '${account.username}'`);
        return respondWith(null);
    },
    // what one account did and what was done to it. kept after the account is deleted, like on the backend
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
