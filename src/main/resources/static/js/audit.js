// audit actions as the admin pages show them, values must match the backend's AuditService
import { escapeHtml, timeTag } from "./util.js";

export const AUDIT_ACTIONS = [
    { value: "LOGIN", label: "Logged in" },
    { value: "LOGIN_FAILED", label: "Failed login" },
    { value: "LOGOUT", label: "Logged out" },
    { value: "REGISTER", label: "Registered" },
    { value: "PROFILE_UPDATED", label: "Changed username" },
    { value: "PASSWORD_CHANGED", label: "Changed password" },
    { value: "ITINERARY_CREATED", label: "Created itinerary" },
    { value: "ITINERARY_UPDATED", label: "Updated itinerary" },
    { value: "ITINERARY_DELETED", label: "Deleted itinerary" },
    { value: "ACCOUNT_DISABLED", label: "Disabled account" },
    { value: "ACCOUNT_ENABLED", label: "Enabled account" },
    { value: "ACCOUNT_DELETED", label: "Deleted account" },
    { value: "ROLE_CHANGED", label: "Changed role" },
    { value: "ACCOUNT_CREATED", label: "Created account" },
    // only the admin console makes these for now
    { value: "ACTIVITY_CREATED", label: "Created activity" },
    { value: "ACTIVITY_UPDATED", label: "Updated activity" },
    { value: "ACTIVITY_DELETED", label: "Deleted activity" },
    { value: "IMPACT_CREATED", label: "Created impact" },
    { value: "IMPACT_UPDATED", label: "Updated impact" },
    { value: "IMPACT_DELETED", label: "Deleted impact" },
    { value: "IMPACT_CLEARED", label: "Cleared out of date impact" },
    { value: "RECOMMENDATION_CREATED", label: "Created recommendation" },
    { value: "RECOMMENDATION_UPDATED", label: "Updated recommendation" },
    { value: "RECOMMENDATION_DELETED", label: "Deleted recommendation" },
];

// the text for an action, or the raw value if the backend sends one this file does not know yet
const actionLabel = value => AUDIT_ACTIONS.find(action => action.value === value)?.label ?? value;

// the actions that admins should notice, so they stand out in the table
const WARNING_ACTIONS = ["LOGIN_FAILED", "ACCOUNT_DISABLED", "ACCOUNT_DELETED"];

// a dropdown of every action, plus "All actions" for no filter
export const createActionFilter = id => {
    const options = AUDIT_ACTIONS.map(action => `
        <option value="${escapeHtml(action.value)}">${escapeHtml(action.label)}</option>
    `).join("");

    return `
        <label for="${id}" class="sr-only">Filter by action</label>
        <select id="${id}">
            <option value="">All actions</option>
            ${options}
        </select>
    `;
};

// a failed login has no actor, so the tried email links to the account it was aimed at
const createActor = entry => {
    const accountId = entry.actorId ?? entry.targetId;
    if (!entry.actorEmail) return `<span class="meta">Unknown</span>`;
    if (accountId == null) return escapeHtml(entry.actorEmail);

    return `<a href="#/admin/users/${Number(accountId)}">${escapeHtml(entry.actorEmail)}</a>`;
};

const createAuditRow = entry => {
    const tagClass = WARNING_ACTIONS.includes(entry.action) ? "tag bad" : "tag";

    return `
        <tr>
            <td class="nowrap">${timeTag(entry.occurredAt)}</td>
            <td>${createActor(entry)}</td>
            <td><span class="${tagClass}">${escapeHtml(actionLabel(entry.action))}</span></td>
            <td>${escapeHtml(entry.details ?? "")}</td>
        </tr>
    `;
};

// the entries of one page, newest first as the backend sends them
export const createAuditTable = entries => {
    if (!entries.length) return `<p class="meta">Nothing has been recorded yet.</p>`;

    return `
        <div class="table-scroll">
            <table class="data-table">
                <thead>
                    <tr>
                        <th scope="col">When</th>
                        <th scope="col">Who</th>
                        <th scope="col">Action</th>
                        <th scope="col">Details</th>
                    </tr>
                </thead>
                <tbody>
                    ${entries.map(createAuditRow).join("")}
                </tbody>
            </table>
        </div>
    `;
};
