// Read-only summary. Each section links to its own edit page.
import { api } from "../api.js";
import { labelFor } from "../preference-options.js";
import { escapeHtml, takeFlash } from "../util.js";

// "With family, with children and elderly travellers", or just "On my own"
const describeTravelGroup = preferences => {
    const companions = [];
    if (preferences.travellingWithChildren) companions.push("children");
    if (preferences.travellingWithElderly) companions.push("elderly travellers");

    const group = labelFor("travelGroup", preferences.travelGroup);
    return companions.length ? `${group}, with ${companions.join(" and ")}` : group;
};

const createPreferenceSummary = preferences => `
    <dl>
        <dt>Caution</dt>
        <dd>${escapeHtml(labelFor("riskTolerance", preferences.riskTolerance))}</dd>
        <dt>Flexibility</dt>
        <dd>${escapeHtml(labelFor("flexibilityLevel", preferences.flexibilityLevel))}</dd>
        <dt>Detail</dt>
        <dd>${escapeHtml(labelFor("explanationDetail", preferences.explanationDetail))}</dd>
        <dt>Travelling</dt>
        <dd>${escapeHtml(describeTravelGroup(preferences))}</dd>
    </dl>
`;

// the rest of the page does not wait for this, so a slow or failed load only affects this one card
async function loadPreferenceSummary(container, onAuthLost) {
    try {
        const preferences = await api.preferences();
        container.innerHTML = createPreferenceSummary(preferences);
    } catch (error) {
        if (error.status === 401) return onAuthLost();
        container.innerHTML = `<p class="meta">${escapeHtml(error.message)}</p>`;
    }
}

// preferences only shape how the AI explains news about itineraries, and admins do not have any
const createPreferenceCard = () => `
    <section class="card">
        <div class="row">
            <h2>Travel preferences</h2>
            <a class="btn" href="#/account/preferences">Edit</a>
        </div>
        <div id="preference-summary">
            <p class="meta">Loading…</p>
        </div>
    </section>
`;

export function showAccount(root, user, onAuthLost) {
        const flashMessage = takeFlash();
        const isAdmin = user.role === "ADMIN";
        const deleteDescription = isAdmin
            ? "Permanently removes your account."
            : "Permanently removes your account, itineraries and chat history.";

        root.innerHTML = `<h1>Your account</h1>
    ${flashMessage ? `<p class="ok" role="status">${escapeHtml(flashMessage)}</p>` : ""}
    <section class="card">
        <div class="row">
            <h2>Details</h2>
            <a class="btn" href="#/account/details">Edit</a>
        </div>
        <dl><dt>Username</dt><dd>${escapeHtml(user.username)}</dd><dt>Email</dt><dd>${escapeHtml(user.email)}</dd></dl>
    </section>
    <section class="card">
        <div class="row">
            <h2>Password</h2>
            <a class="btn" href="#/account/password">Change</a>
        </div>
        <p class="meta">••••••••</p>
    </section>
    ${isAdmin ? "" : createPreferenceCard()}
    <section class="card danger-zone">
        <div class="row">
            <h2>Delete account</h2>
            <a class="btn danger" href="#/account/delete">Delete</a>
        </div>
        <p class="meta">${deleteDescription}</p>
    </section>
    `;

    if (!isAdmin) loadPreferenceSummary(root.querySelector("#preference-summary"), onAuthLost);
}