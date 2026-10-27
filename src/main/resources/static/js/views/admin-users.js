import { api } from "../api.js";
import { escapeHtml, showError } from "../util.js";

export async function showAdminUsers(root, user, onAuthLost) {
    root.innerHTML = `<h1>Users</h1>
        <form id="user-search-form" class="row">
            <label for="user-search">Search accounts</label>
            <input id="user-search" type="search" placeholder="Username or email">
            <button type="submit">Search</button>
        </form>
        <p id="admin-status" role="status"></p>
        <div id="user-list" class="card">Loading…</div>
        <div class="row"><button id="previous-page">Previous</button>
            <span id="page-status"></span><button id="next-page">Next</button></div>`;
    const list = root.querySelector("#user-list");
    const status = root.querySelector("#admin-status");
    const previous = root.querySelector("#previous-page");
    const next = root.querySelector("#next-page");
    let page = 0;
    let query = "";
    let requestNumber = 0;

    async function load() {
        const currentRequest = ++requestNumber;
        previous.disabled = next.disabled = true;
        try {
            const result = await api.adminUsers(query, page);
            if (currentRequest !== requestNumber) return;
            list.innerHTML = `<div class="table-scroll"><table class="users">
                <thead><tr><th>ID</th><th>Username</th><th>Email</th><th>Status</th><th>Role</th></tr></thead>
                <tbody>${result.content.map(account => `<tr>
                    <td>${Number(account.id)}</td><td>${escapeHtml(account.username)}</td>
                    <td>${escapeHtml(account.email)}</td><td>${account.enabled === false ? "Disabled" : "Enabled"}</td>
                    <td>${account.id === user.id ? `${escapeHtml(account.role)} (you)` : `
                        <form data-account-id="${Number(account.id)}" class="row">
                            <select name="role" aria-label="Role for ${escapeHtml(account.username)}">
                                <option value="USER" ${account.role === "USER" ? "selected" : ""}>USER</option>
                                <option value="ADMIN" ${account.role === "ADMIN" ? "selected" : ""}>ADMIN</option>
                            </select><button type="submit">Save role</button>
                        </form>`}</td></tr>`).join("")}</tbody></table></div>
                ${result.content.length ? "" : "<p>No accounts match your search.</p>"}`;
            root.querySelector("#page-status").textContent =
                `Page ${result.page + 1} of ${Math.max(1, result.totalPages)} · ${result.totalElements} accounts`;
            previous.disabled = page === 0;
            next.disabled = page + 1 >= result.totalPages;
        } catch (error) {
            if (currentRequest === requestNumber) showError(status, error, onAuthLost);
        }
    }

    list.addEventListener("submit", async event => {
        event.preventDefault();
        const form = event.target.closest("form[data-account-id]");
        if (!form) return;
        const button = form.querySelector("button");
        button.disabled = true;
        status.textContent = "";
        try {
            await api.changeUserRole(Number(form.dataset.accountId), form.elements.role.value);
            status.textContent = "Role updated. The account’s existing sessions have ended.";
            await load();
        } catch (error) {
            showError(status, error, onAuthLost);
        } finally { button.disabled = false; }
    });
    root.querySelector("#user-search-form").addEventListener("submit", event => {
        event.preventDefault();
        query = root.querySelector("#user-search").value.trim();
        page = 0;
        load();
    });
    previous.addEventListener("click", () => { page--; load(); });
    next.addEventListener("click", () => { page++; load(); });
    await load();
}
