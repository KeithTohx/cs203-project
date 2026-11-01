// admin only: every account, searched and paged on the backend
import { api } from "../api.js";
import { createPager, debounce, escapeHtml, showError } from "../util.js";

const PAGE_SIZES = [10, 20];

// one row, the name opens that user's page
const createUserRow = account => `
    <tr>
        <td>${Number(account.id)}</td>
        <td><a href="#/admin/users/${Number(account.id)}"><strong>${escapeHtml(account.username)}</strong></a></td>
        <td>${escapeHtml(account.email)}</td>
        <td><span class="tag${account.role === "ADMIN" ? " admin" : ""}">${escapeHtml(account.role)}</span></td>
        <td>${account.enabled ? `<span class="meta">Active</span>` : `<span class="tag bad">Disabled</span>`}</td>
    </tr>
`;

const createUserTable = accounts => `
    <div class="table-scroll">
        <table class="users">
            <thead>
                <tr>
                    <th scope="col">ID</th>
                    <th scope="col">Username</th>
                    <th scope="col">Email</th>
                    <th scope="col">Role</th>
                    <th scope="col">Status</th>
                </tr>
            </thead>
            <tbody>
                ${accounts.map(createUserRow).join("")}
            </tbody>
        </table>
    </div>
`;

const createPageSizeSelect = () => {
    const options = PAGE_SIZES.map(size => `
        <option value="${size}"${size === 20 ? " selected" : ""}>${size} per page</option>
    `).join("");

    return `
        <label for="page-size" class="sr-only">Users per page</label>
        <select id="page-size">
            ${options}
        </select>
    `;
};

// the backend only searches email and username, so a whole number is also tried as an id
const findById = async search => {
    const isId = /^\d+$/.test(search);
    if (!isId) return null;

    try {
        return await api.adminUser(Number(search));
    } catch (error) {
        if (error.status === 404) return null;
        throw error;
    }
};

export async function showAdminUsers(root, user, onAuthLost) {
    root.innerHTML = `
        <h1>Users</h1>
        <div class="card">
            <div class="row">
                <label for="user-search" class="sr-only">Search users</label>
                <input id="user-search" type="search" placeholder="Search by ID, username or email" autocomplete="off">
                <div class="filter">
                    ${createPageSizeSelect()}
                </div>
            </div>
            <p class="meta" id="user-count" role="status"></p>
            <div id="user-list">
                <div class="msg">Loading…</div>
            </div>
        </div>
    `;

    const searchInput = root.querySelector("#user-search");
    const pageSizeSelect = root.querySelector("#page-size");
    const countLabel = root.querySelector("#user-count");
    const userList = root.querySelector("#user-list");

    // a slow answer to an older search must not replace the answer to a newer one
    let latestRequest = 0;

    const loadPage = async page => {
        const thisRequest = ++latestRequest;
        const search = searchInput.value.trim();
        const size = Number(pageSizeSelect.value);

        let result;
        let idMatch;
        try {
            [result, idMatch] = await Promise.all([
                api.adminUsers({ search, page, size }),
                findById(search),
            ]);
        } catch (error) {
            if (thisRequest === latestRequest) showError(userList, error, onAuthLost);
            return;
        }
        if (thisRequest !== latestRequest) return;

        // the id match goes first on the first page, unless its username or email matched too
        const alreadyListed = idMatch && result.content.some(account => account.id === idMatch.id);
        const showIdMatch = idMatch && !alreadyListed && result.page === 0;
        const accounts = showIdMatch ? [idMatch, ...result.content] : result.content;

        const total = result.totalElements + (idMatch && !alreadyListed ? 1 : 0);
        const noun = total === 1 ? "user" : "users";
        countLabel.textContent = search ? `${total} ${noun} found` : `${total} ${noun}`;

        userList.innerHTML = accounts.length
            ? createUserTable(accounts) + createPager(result)
            : `<p class="meta">No users match your search.</p>`;
    };

    // a new search or page size always starts again from the first page
    searchInput.addEventListener("input", debounce(() => loadPage(0)));
    pageSizeSelect.addEventListener("change", () => loadPage(0));
    userList.addEventListener("click", event => {
        const pageButton = event.target.closest("[data-page]");
        if (pageButton) loadPage(Number(pageButton.dataset.page));
    });

    loadPage(0);
}
