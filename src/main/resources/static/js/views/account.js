// Read-only summary. Each section links to its own edit page.
import { escapeHtml, takeFlash } from "../util.js";
export function showAccount(root, user) {
        const flashMessage = takeFlash();
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
    <section class="card">
        <div class="row">
            <h2>Your preferences</h2>
            <a class="btn" href="#/account/details">Edit</a>
        </div>
        to be added
    </section>
    <section class="card danger-zone">
        <div class="row">
            <h2>Delete account</h2>
            <a class="btn danger" href="#/account/delete">Delete</a>
        </div>
        <p class="meta">Permanently removes your account, itineraries and chat history.</p>
    </section>
    `;
}