// admin only: one account's details and activity, with role changes, disable, enable and delete
import { api } from "../api.js";
import { createActionFilter, createAuditTable } from "../audit.js";
import { mountUserData } from "./admin-data.js";
import { confirmTwice, createPager, escapeHtml, flash, showError } from "../util.js";

// what the hint under the details says, depending on what can be done to the account
const describeAccountRules = (account, isSelf) => {
    if (isSelf) return "This is your account, so its role and status cannot be changed here, and it cannot be deleted.";
    // an admin disabled before admins were protected can still be enabled, or made a user to delete
    if (account.role === "ADMIN" && !account.enabled) {
        return "This admin account cannot log in until it is enabled again. To delete it, make them a user first.";
    }
    if (account.role === "ADMIN") {
        return "Admins cannot be disabled or deleted, so make them a user first. Changing the role logs them out.";
    }
    if (!account.enabled) return "This account cannot log in until it is enabled again.";
    return "Disabling logs them out straight away and stops them logging in. Their data is kept. "
        + "Changing the role logs them out too.";
};

// the account's details, with buttons for its role and status.
// the backend only disables plain user accounts, so an enabled admin has no disable button
const createAccountCard = (account, isSelf) => {
    const isAdmin = account.role === "ADMIN";

    const roleButton = isAdmin
        ? `<button class="ghost" type="button" data-toggle-role>Make user</button>`
        : `<button class="ghost" type="button" data-toggle-role>Make admin</button>`;

    let statusButton = "";
    if (!account.enabled) {
        statusButton = `<button type="button" data-toggle-status>Enable account</button>`;
    } else if (!isAdmin) {
        statusButton = `<button class="ghost" type="button" data-toggle-status>Disable account</button>`;
    }

    const buttons = isSelf
        ? ""
        : `
            <div class="heading-actions">
                ${roleButton}
                ${statusButton}
            </div>
        `;

    return `
        <div class="row">
            <h2>Account</h2>
            ${buttons}
        </div>
        <dl>
            <dt>Email</dt>
            <dd>${escapeHtml(account.email)}</dd>
            <dt>Role</dt>
            <dd><span class="tag${account.role === "ADMIN" ? " admin" : ""}">${escapeHtml(account.role)}</span></dd>
            <dt>Status</dt>
            <dd>${account.enabled ? "Active" : `<span class="tag bad">Disabled</span>`}</dd>
        </dl>
        <p class="meta hint">${describeAccountRules(account, isSelf)}</p>
        <p class="err" data-error role="alert"></p>
    `;
};

// deleting is for good, so it sits apart like on the account page.
// hidden while the account is an admin, since the backend only deletes plain user accounts
const createDangerZone = account => `
    <section class="card danger-zone" id="danger-zone"${account.role === "ADMIN" ? " hidden" : ""}>
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

    // a deleted account 404s but its activity is kept
    const [accountResult, activityResult] = await Promise.allSettled([
        api.adminUser(userId),
        api.userActivity(userId),
    ]);

    if (activityResult.status === "rejected") return showError(root, activityResult.reason, onAuthLost);

    // missing with activity means deleted, missing without any never existed
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
        ${account ? `<div id="trip-data"></div>` : ""}
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

    // with a filter on, an empty page means nothing matched, not that the account has no history
    const drawActivity = result => {
        const nothingMatched = !result.content.length && actionFilter.value;
        if (nothingMatched) {
            activity.innerHTML = `<p class="meta">Nothing matches this filter.</p>`;
            return;
        }
        activity.innerHTML = createAuditTable(result.content) + createPager(result);
    };

    // a slow answer to an older filter must not replace the answer to a newer one
    let latestRequest = 0;
    const loadActivity = async page => {
        latestRequest += 1;
        const thisRequest = latestRequest;

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

    // a deleted account has nothing left to show, only its history
    if (account) {
        const tripData = root.querySelector("#trip-data");
        mountUserData(tripData, userId, onAuthLost, { onChange: () => loadActivity(0) });
    }
    activity.addEventListener("click", event => {
        const pageButton = event.target.closest("[data-page]");
        if (pageButton) loadActivity(Number(pageButton.dataset.page));
    });

    if (!account || isSelf) return;

    const accountCard = root.querySelector("#account-card");
    const dangerZone = root.querySelector("#danger-zone");

    // a role change can take away or bring back the disable button and the delete section
    const drawAccount = () => {
        accountCard.innerHTML = createAccountCard(account, isSelf);
        dangerZone.hidden = account.role === "ADMIN";
    };

    // a changed role or status can be undone straight away, so one click is enough (deleting below needs two)
    accountCard.addEventListener("click", async event => {
        const statusButton = event.target.closest("[data-toggle-status]");
        const roleButton = event.target.closest("[data-toggle-role]");
        const button = statusButton ?? roleButton;
        if (!button) return;

        const errorMessage = accountCard.querySelector("[data-error]");
        button.disabled = true;
        errorMessage.textContent = "";

        try {
            if (statusButton) {
                account = account.enabled ? await api.disableUser(userId) : await api.enableUser(userId);
            } else {
                const newRole = account.role === "ADMIN" ? "USER" : "ADMIN";
                account = await api.changeUserRole(userId, newRole);
            }
        } catch (error) {
            button.disabled = false;
            if (error.status === 401) return onAuthLost();
            errorMessage.textContent = error.message;   // eg the last enabled admin cannot be made a user
            return;
        }

        drawAccount();

        // the same button again, or the role button when the status one is gone, eg after making them an admin
        const sameButton = accountCard.querySelector(statusButton ? "[data-toggle-status]" : "[data-toggle-role]");
        const focusTarget = sameButton ?? accountCard.querySelector("[data-toggle-role]");
        focusTarget.focus();
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
            if (error.status === 401) {
                onAuthLost();
                return;
            }
            deleteError.textContent = error.message;
        }
    } });
}
