// Admin only: everything every account did, newest first, filtered by email and action.
// Filtering and paging happen on the backend, so only one page of entries is ever loaded.
import { api } from "../api.js";
import { createActionFilter, createAuditTable } from "../audit.js";
import { createPager, debounce, showError } from "../util.js";

export async function showAdminAudit(root, user, onAuthLost) {
    root.innerHTML = `
        <h1>Activity log</h1>
        <div class="card">
            <div class="row filters">
                <label for="email-filter" class="sr-only">Filter by email</label>
                <input id="email-filter" type="search" placeholder="Filter by email" autocomplete="off">
                ${createActionFilter("action-filter")}
            </div>
            <div id="audit-list">
                <div class="msg">Loading…</div>
            </div>
        </div>
    `;

    const emailFilter = root.querySelector("#email-filter");
    const actionFilter = root.querySelector("#action-filter");
    const auditList = root.querySelector("#audit-list");

    // a slow answer to an older filter must not replace the answer to a newer one
    let latestRequest = 0;

    const loadPage = async page => {
        const thisRequest = ++latestRequest;
        const filters = { email: emailFilter.value.trim(), action: actionFilter.value, page };

        let result;
        try {
            result = await api.auditLog(filters);
        } catch (error) {
            if (thisRequest === latestRequest) showError(auditList, error, onAuthLost);
            return;
        }
        if (thisRequest !== latestRequest) return;

        const isFiltered = filters.email || filters.action;
        auditList.innerHTML = !result.content.length && isFiltered
            ? `<p class="meta">Nothing matches these filters.</p>`
            : createAuditTable(result.content) + createPager(result);
    };

    // changing a filter always starts again from the first page
    emailFilter.addEventListener("input", debounce(() => loadPage(0)));
    actionFilter.addEventListener("change", () => loadPage(0));
    auditList.addEventListener("click", event => {
        const pageButton = event.target.closest("[data-page]");
        if (pageButton) loadPage(Number(pageButton.dataset.page));
    });

    loadPage(0);
}
