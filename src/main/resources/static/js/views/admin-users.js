// Admin only: every user account, read-only, with a search box. Access is checked by the route table and the backend.
import { api } from "../api.js";
import { escapeHtml, showError } from "../util.js";

// Component: one row of the users table
const createUserRow = user => `
    <tr>
        <td>${Number(user.id)}</td>
        <td><strong>${escapeHtml(user.username)}</strong></td>
        <td>${escapeHtml(user.email)}</td>
        <td><span class="tag${user.role === "ADMIN" ? " admin" : ""}">${escapeHtml(user.role)}</span></td>
    </tr>`;

export async function showAdminUsers(root, user, onAuthLost) {
    root.innerHTML = `
        <h1>Users</h1>
        <div id="user-list" class="msg">Loading…</div>
    `;
    const userListContainer = root.querySelector("#user-list");

    let users;
    try { users = await api.adminUsers() ?? []; }
    catch (error) { return showError(userListContainer, error, onAuthLost); }
    const byId = [...users].sort((first, second) => first.id - second.id);

    userListContainer.className = "card";
    userListContainer.innerHTML = `
        <div class="row">
            <label for="user-search" class="sr-only">Search users</label>
            <input id="user-search" type="search" placeholder="Search by username or email" autocomplete="off">
            <p class="meta nowrap" id="user-count" role="status"></p>
        </div>
        <div class="table-scroll">
            <table class="users">
                <thead><tr><th scope="col">ID</th><th scope="col">Username</th><th scope="col">Email</th><th scope="col">Role</th></tr></thead>
                <tbody></tbody>
            </table>
        </div>
        <p class="meta" id="no-users" hidden>No users match your search.</p>
    `;

    const searchInput = userListContainer.querySelector("#user-search");
    const tableBody = userListContainer.querySelector("tbody");
    const countLabel = userListContainer.querySelector("#user-count");
    const emptyMessage = userListContainer.querySelector("#no-users");

    const showMatches = () => {
        const query = searchInput.value.trim().toLowerCase();
        const matches = byId.filter(account =>
            !query || [account.username, account.email].some(text => String(text ?? "").toLowerCase().includes(query)));
        tableBody.innerHTML = matches.map(createUserRow).join("");
        countLabel.textContent = `${matches.length} of ${byId.length} users`;
        emptyMessage.hidden = matches.length > 0;
    };
    searchInput.addEventListener("input", showMatches);
    showMatches();
}
