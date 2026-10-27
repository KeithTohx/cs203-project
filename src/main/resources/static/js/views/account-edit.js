// One screen per section. On success: show a message back on #/account.
import { api } from "../api.js";
import { PREFERENCE_OPTIONS } from "../preference-options.js";
import { escapeHtml, flash, formPage, passwordProblem, PASSWORD_MAX, PASSWORD_MIN, showError } from "../util.js";

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

// one dropdown for a preference, with the saved value already chosen
const createPreferenceSelect = (field, labelText, savedValue) => {
    const options = PREFERENCE_OPTIONS[field].map(option => `
        <option value="${escapeHtml(option.value)}"${option.value === savedValue ? " selected" : ""}>
            ${escapeHtml(option.label)}
        </option>
    `).join("");

    return `
        <label for="${field}">${labelText}</label>
        <select id="${field}" name="${field}">
            ${options}
        </select>
    `;
};

// one tick box for a preference that is either on or off
const createPreferenceCheckbox = (field, labelText, isChecked) => `
    <label class="check" for="${field}">
        <input id="${field}" name="${field}" type="checkbox"${isChecked ? " checked" : ""}>
        ${labelText}
    </label>
`;

// the preferences only shape how the AI assistant explains news, so the page says that up front.
// the saved values are loaded first so the form opens with them already filled in
export async function showEditPreferences(root, user, reauth) {
    root.innerHTML = `<div class="msg">Loading…</div>`;

    let saved;
    try {
        saved = await api.preferences();
    } catch (error) {
        return showError(root, error, reauth);
    }

    const { form } = formPage(root, {
        title: "Travel preferences", cancelLink: "#/account", onAuthLost: reauth, fieldsHtml: `
    <p class="meta">
        These shape how the AI assistant explains news about your trips.
        They never hide news that affects your itinerary.
    </p>

    ${createPreferenceSelect("riskTolerance", "How cautious are you?", saved.riskTolerance)}
    ${createPreferenceSelect("flexibilityLevel", "How flexible are your plans?", saved.flexibilityLevel)}
    ${createPreferenceSelect("explanationDetail", "How much detail do you want?", saved.explanationDetail)}
    ${createPreferenceSelect("travelGroup", "Who do you usually travel with?", saved.travelGroup)}

    <fieldset id="companions">
        <legend>Travelling with</legend>
        ${createPreferenceCheckbox("travellingWithChildren", "Children", saved.travellingWithChildren)}
        ${createPreferenceCheckbox("travellingWithElderly", "Elderly travellers", saved.travellingWithElderly)}
        <p class="meta hint" id="companions-hint">Only for family trips.</p>
    </fieldset>`,
        onSubmit: async inputs => {
            await api.updatePreferences({
                riskTolerance: inputs.riskTolerance.value,
                flexibilityLevel: inputs.flexibilityLevel.value,
                explanationDetail: inputs.explanationDetail.value,
                travelGroup: inputs.travelGroup.value,
                travellingWithChildren: inputs.travellingWithChildren.checked,
                travellingWithElderly: inputs.travellingWithElderly.checked,
            });
            flash("Preferences saved.");
            location.hash = "/account";
        },
    });

    // the backend refuses children or elderly on a solo trip, so the boxes are cleared and
    // switched off while "On my own" is picked, instead of letting the save fail
    const travelGroup = form.elements.travelGroup;
    const companions = form.querySelector("#companions");
    const updateCompanions = () => {
        const isSolo = travelGroup.value === "SOLO";
        companions.disabled = isSolo;
        if (isSolo) {
            form.elements.travellingWithChildren.checked = false;
            form.elements.travellingWithElderly.checked = false;
        }
    };
    travelGroup.addEventListener("change", updateCompanions);
    updateCompanions();
}

// the current password is the confirmation, the same way changing the password works,
// so a stolen session or a stray click cannot delete anything on its own
export function showDeleteAccount(root, user, reauth) {
    formPage(root, {
        title: "Delete account", cancelLink: "#/account", onAuthLost: reauth,
        submitLabel: "Delete my account", submitClass: "destructive", fieldsHtml: `
    <p>
        This permanently deletes the account <strong>${escapeHtml(user.username)}</strong>
        along with your itineraries, their activities, the news found to affect them and your chat history.
    </p>
    <p class="err">This cannot be undone.</p>

    <label for="currentPassword">Current password</label>
    <input id="currentPassword" name="currentPassword" type="password" autocomplete="current-password" required>
    <p class="meta hint">You will be logged out everywhere once it is done.</p>`,
        onSubmit: async inputs => {
            await api.deleteMe(inputs.currentPassword.value);

            // the account is gone and so is the session. the address is cleared first so the
            // login screen does not try to send anyone back to this form
            flash("Your account has been deleted.");
            history.replaceState(null, "", location.pathname + location.search);
            reauth();
        },
    });
}
