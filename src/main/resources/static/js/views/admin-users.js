// admin only: every account, searched and paged on the backend
import { api } from "../api.js";
import { createPager, debounce, escapeHtml, flash, formPage, passwordProblem, PASSWORD_MAX, PASSWORD_MIN, showError } from "../util.js";

// same rule as the backend, the "-" is escaped because pattern attributes use the strict "v" regex mode
const USERNAME_PATTERN = "[A-Za-z0-9_.\\-]+";

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
        <table class="data-table">
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

// whether the backend search would find the account by its username or email anyway
const matchesText = (account, search) => {
    const needle = search.toLowerCase();
    const username = (account.username ?? "").toLowerCase();
    const email = (account.email ?? "").toLowerCase();
    return username.includes(needle) || email.includes(needle);
};

// the backend only searches email and username, so a whole number is also tried as an id.
// this is only an extra, so a failed lookup leaves the normal results alone. a 401 still counts,
// since the user has to log in again either way
const findById = async search => {
    const isId = /^\d+$/.test(search);
    if (!isId) return null;

    try {
        return await api.adminUser(Number(search));
    } catch (error) {
        if (error.status === 401) throw error;
        return null;
    }
};

export async function showAdminUsers(root, user, onAuthLost) {
    root.innerHTML = `
        <div class="row">
            <h1>Users</h1>
            <a class="btn" href="#/admin/users/new">New user</a>
        </div>
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
        latestRequest += 1;
        const thisRequest = latestRequest;
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

        // the id match sits above the paged list on the first page, unless its username or email matched too.
        // it is kept out of the list so every page holds exactly the chosen size and the pager adds up.
        // the text check is on the account itself, since a text match can be on any page, not just this one
        const isExtraMatch = idMatch && !matchesText(idMatch, search);
        const showIdMatch = isExtraMatch && result.page === 0;

        const total = result.totalElements;
        const noun = total === 1 ? "user" : "users";
        const baseCount = search ? `${total} ${noun} found` : `${total} ${noun}`;
        countLabel.textContent = isExtraMatch ? `${baseCount}, plus 1 by ID` : baseCount;

        const idMatchSection = showIdMatch
            ? `
                <h2 class="meta">Matching ID</h2>
                ${createUserTable([idMatch])}
                ${result.content.length ? `<h2 class="meta">Matching username or email</h2>` : ""}
            `
            : "";
        const pagedList = result.content.length
            ? createUserTable(result.content) + createPager(result)
            : "";

        const hasAny = showIdMatch || result.content.length > 0;
        userList.innerHTML = hasAny
            ? idMatchSection + pagedList
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

// admin only: make an account by hand with any role, eg a throwaway traveller for testing
export function showNewUser(root, user, onAuthLost) {
    formPage(root, {
        title: "New user",
        cancelLink: "#/admin/users",
        onAuthLost,
        submitLabel: "Create",
        fieldsHtml: `
            <label for="username">Username</label>
            <input id="username" name="username" minlength="3" maxlength="30" required autocomplete="off"
                pattern="${USERNAME_PATTERN}" title="Letters, digits, '.', '_' and '-' only">
            <label for="email">Email</label>
            <input id="email" name="email" type="email" required autocomplete="off">
            <label for="password">Password (${PASSWORD_MIN} to ${PASSWORD_MAX} characters)</label>
            <input id="password" name="password" type="password" minlength="${PASSWORD_MIN}" required autocomplete="new-password">
            <label for="role">Role</label>
            <select id="role" name="role">
                <option value="USER" selected>USER</option>
                <option value="ADMIN">ADMIN</option>
            </select>
            <p class="meta hint">They log in with this email and password. Creating an account is recorded in the activity log.</p>
        `,
        onSubmit: async inputs => {
            const problem = passwordProblem(inputs.password.value);
            if (problem) throw new Error(problem);

            const created = await api.adminCreateUser({
                username: inputs.username.value.trim(),
                email: inputs.email.value.trim(),
                password: inputs.password.value,
                role: inputs.role.value,
            });
            flash(`${created.username}'s account was created.`);
            location.hash = `/admin/users/${Number(created.id)}`;
        },
    });
}
