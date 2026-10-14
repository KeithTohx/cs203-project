// One screen per section. On success: show a message back on #/account.
import { api } from "../api.js";
import { escapeHtml, flash, formPage, passwordProblem, PASSWORD_MAX, PASSWORD_MIN } from "../util.js";

export function showEditDetails(root, user, reauth) {
    formPage(root, {
        title: "Edit details", cancelLink: "#/account", onAuthLost: reauth, fieldsHtml: `
    <label for="username">Username</label>
    <input id="username" name="username" value="${escapeHtml(user.username)}" minlength="3" maxlength="30" required>
    <label for="email">Email</label>
    <input id="email" name="email" type="email" value="${escapeHtml(user.email)}" required>`,
        onSubmit: async inputs => {
            await api.updateMe({ username: inputs.username.value.trim(), email: inputs.email.value.trim() });
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
    <input id="newPassword" name="newPassword" type="password" autocomplete="new-password" minlength="${PASSWORD_MIN}" required>`,
        onSubmit: async inputs => {
            const problem = passwordProblem(inputs.newPassword.value);
            if (problem) throw new Error(problem);   // formPage shows it under the form

            await api.changePassword({ currentPassword: inputs.currentPassword.value, newPassword: inputs.newPassword.value });
            flash("Password changed.");
            location.hash = "/account";
        },
    });
}
