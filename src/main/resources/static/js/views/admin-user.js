// Admin only: one user account. Shows its details and activity, and lets the admin disable, enable or delete it.
// The backend refuses an admin disabling or deleting their own account, so those buttons are not shown for it.
import { api } from "../api.js";
import { createActionFilter, createAuditTable } from "../audit.js";
import { confirmTwice, createPager, escapeHtml, flash, showError } from "../util.js";

// Component: the account's details, with the button that disables or enables it
const createAccountCard = (account, isSelf) => {
    const statusButton = account.enabled
        ? `<button class="ghost" type="button" data-toggle-status>Disable account</button>`
        : `<button type="button" data-toggle-status>Enable account</button>`;

    const hint = isSelf
        ? "This is your account, so it cannot be disabled or deleted here."
        : account.enabled
            ? "Disabling logs them out straight away and stops them logging in. Their data is kept."
            : "This account cannot log in until it is enabled again.";

    return `
        <div class="row">
            <h2>Account</h2>
            ${isSelf ? "" : statusButton}
        </div>
        <dl>
            <dt>Email</dt>
            <dd>${escapeHtml(account.email)}</dd>
            <dt>Role</dt>
            <dd><span class="tag${account.role === "ADMIN" ? " admin" : ""}">${escapeHtml(account.role)}</span></dd>
            <dt>Status</dt>
            <dd>${account.enabled ? "Active" : `<span class="tag bad">Disabled</span>`}</dd>
        </dl>
        <p class="meta hint">${hint}</p>
        <p class="err" data-error role="alert"></p>
    `;
};

// Component: deleting is for good, so it sits apart from the rest like on the account page
const createDangerZone = account => `
    <section class="card danger-zone">
        <div class="row">
            <h2>Delete account</h2>
            <button class="ghost danger" type="button" id="delete-button"
                    aria-label="Delete ${escapeHtml(account.username)}'s account">
                Delete
            </button>
        </div>
        <p class="meta">Permanently removes this account and its itineraries. Its activity below is kept.</p>
        <p class="err" id="delete-error" role="alert"></p>
    </section>
`;

export async function showAdminUser(root, user, onAuthLost, id) {
    const userId = Number(id);
    const isSelf = userId === user.id;
    root.innerHTML = `<div class="msg">Loading…</div>`;

    // both at once. a deleted account has no details any more (404), but its activity is still kept
    const [accountResult, activityResult] = await Promise.allSettled([
        api.adminUser(userId),
        api.userActivity(userId),
    ]);

    if (activityResult.status === "rejected") return showError(root, activityResult.reason, onAuthLost);

    // a missing account with recorded activity was deleted. one with no activity at all never existed
    const isMissing = accountResult.status === "rejected" && accountResult.reason.status === 404;
    const isDeleted = isMissing && activityResult.value.totalElements > 0;
    if (accountResult.status === "rejected" && !isDeleted) return showError(root, accountResult.reason, onAuthLost);

    let account = isDeleted ? null : accountResult.value;

    const accountSection = isDeleted
        ? `<div class="msg">This account has been deleted. What it did is still kept below.</div>`
        : `<section class="card" id="account-card">${createAccountCard(account, isSelf)}</section>`;

    root.innerHTML = `
        <p><a href="#/admin/users">← All users</a></p>
        <h1>${isDeleted ? `Deleted account #${userId}` : escapeHtml(account.username)}</h1>
        ${accountSection}
        <section class="card">
            <div class="row">
                <h2>Activity</h2>
                <div class="filter">
                    ${createActionFilter("action-filter")}
                </div>
            </div>
            <div id="activity"></div>
        </section>
        ${account && !isSelf ? createDangerZone(account) : ""}
    `;

    const activity = root.querySelector("#activity");
    const actionFilter = root.querySelector("#action-filter");

    const drawActivity = result => {
        activity.innerHTML = createAuditTable(result.content) + createPager(result);
    };

    // a slow answer to an older filter must not replace the answer to a newer one
    let latestRequest = 0;
    const loadActivity = async page => {
        const thisRequest = ++latestRequest;

        let result;
        try {
            result = await api.userActivity(userId, { action: actionFilter.value, page });
        } catch (error) {
            if (thisRequest === latestRequest) showError(activity, error, onAuthLost);
            return;
        }
        if (thisRequest === latestRequest) drawActivity(result);
    };

    drawActivity(activityResult.value);
    actionFilter.addEventListener("change", () => loadActivity(0));
    activity.addEventListener("click", event => {
        const pageButton = event.target.closest("[data-page]");
        if (pageButton) loadActivity(Number(pageButton.dataset.page));
    });

    if (!account || isSelf) return;

    // disabling can be undone straight away, so one click is enough (deleting below needs two)
    const accountCard = root.querySelector("#account-card");
    accountCard.addEventListener("click", async event => {
        const button = event.target.closest("[data-toggle-status]");
        if (!button) return;

        const errorMessage = accountCard.querySelector("[data-error]");
        button.disabled = true;
        errorMessage.textContent = "";

        try {
            account = account.enabled ? await api.disableUser(userId) : await api.enableUser(userId);
        } catch (error) {
            button.disabled = false;
            if (error.status === 401) return onAuthLost();
            errorMessage.textContent = error.message;
            return;
        }

        accountCard.innerHTML = createAccountCard(account, isSelf);
        accountCard.querySelector("[data-toggle-status]").focus();
        loadActivity(0);   // the change is in the activity now, at the top
    });

    const deleteButton = root.querySelector("#delete-button");
    const deleteError = root.querySelector("#delete-error");
    confirmTwice(deleteButton, { confirmText: "Delete account?", onConfirm: async () => {
        deleteButton.disabled = true;
        deleteError.textContent = "";
        try {
            await api.adminDeleteUser(userId);
            flash(`${account.username}'s account was deleted.`);
            location.hash = "/admin/users";
        } catch (error) {
            deleteButton.disabled = false;
            error.status === 401 ? onAuthLost() : (deleteError.textContent = error.message);
        }
    } });
}
