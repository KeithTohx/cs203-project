// One screen per section. On success: show a message back on #/account.
import { api } from "../api.js";
import { escapeHtml, flash, formPage, passwordProblem, PASSWORD_MAX, PASSWORD_MIN } from "../util.js";

// same rules as the backend's UpdateProfileRequest, so the browser catches mistakes before saving.
// the "-" is escaped because pattern attributes use the strict "v" regex mode
const USERNAME_PATTERN = "[A-Za-z0-9_.\\-]+";

export function showEditDetails(root, user, reauth) {
    formPage(root, {
        title: "Edit details", cancelLink: "#/account", onAuthLost: reauth, fieldsHtml: `
    <label for="username">Username</label>
    <input id="username" name="username" value="${escapeHtml(user.username)}" minlength="3" maxlength="30" required
        pattern="${USERNAME_PATTERN}" title="Letters, digits, '.', '_' and '-' only" aria-describedby="username-hint">
    <p class="meta hint" id="username-hint">3 to 30 characters: letters, digits, ".", "_" and "-".</p>

    <dl>
        <dt>Email</dt>
        <dd>${escapeHtml(user.email)}</dd>
    </dl>
    <p class="meta hint">Your email is how you log in, so it can't be changed.</p>`,
        onSubmit: async inputs => {
            await api.updateMe({ username: inputs.username.value.trim() });
            flash("Details saved.");
            reauth("/account");            // refresh the cached user, then go to the summary
        },
    });
}

export function showChangePassword(root, user, reauth) {
    formPage(root, {
        title: "Change password", cancelLink: "#/account", onAuthLost: reauth, fieldsHtml: `
    <label for="currentPassword">Current password</label>
    <input id="currentPassword" name="currentPassword" type="password" autocomplete="current-password" required>
    <label for="newPassword">New password (${PASSWORD_MIN} to ${PASSWORD_MAX} characters)</label>
    <input id="newPassword" name="newPassword" type="password" autocomplete="new-password" minlength="${PASSWORD_MIN}" required>
    <p class="meta hint">You'll be logged out everywhere and asked to log in with the new password.</p>`,
        onSubmit: async inputs => {
            const problem = passwordProblem(inputs.newPassword.value);
            if (problem) throw new Error(problem);   // formPage shows it under the form

            await api.changePassword({ currentPassword: inputs.currentPassword.value, newPassword: inputs.newPassword.value });

            // the backend ends every session of this account, so the next step is logging in again.
            // the address is cleared first so logging in lands on the home page, not back on this form
            flash("Password changed. Please log in with your new password.");
            history.replaceState(null, "", location.pathname + location.search);
            reauth();
        },
    });
}
