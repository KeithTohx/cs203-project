// Makes text safe to put inside HTML, so user and news text can never inject tags or scripts.
export const escapeHtml = value => String(value ?? "").replace(/[&<>"']/g, char =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char]));
export const formatDate = date => date ? new Date(date).toLocaleDateString(undefined,
    { day: "numeric", month: "short", year: "numeric" }) : "";
export const dateRange = (start, end) => {
    if (!start) return "";
    return !end || formatDate(start) === formatDate(end) ? formatDate(start) : `${formatDate(start)} – ${formatDate(end)}`;
};
export const sortByStart = activities =>
    [...activities].sort((first, second) => new Date(first.dateStart ?? 0) - new Date(second.dateStart ?? 0));
// News URLs come from an external API: only allow http(s) links.
export const safeUrl = url => /^https?:\/\//i.test(url || "") ? escapeHtml(url) : "";
// Shared error handler: a 401 means the session ended, so go back to login.
export function showError(container, error, onAuthLost) {
    if (error.status === 401) return onAuthLost();
    container.innerHTML = `<div class="msg err" role="alert">${escapeHtml(error.message)}</div>`;
}
// same limits as the backend's User entity. bcrypt only reads the first 72 characters, so longer is rejected
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 72;
export function passwordProblem(password) {
    if (password.length < PASSWORD_MIN) return `Password must be at least ${PASSWORD_MIN} characters.`;
    if (password.length > PASSWORD_MAX) return `Password can't be longer than ${PASSWORD_MAX} characters.`;
    return null;
}
// One-shot message shown on the next screen (e.g. "Details saved.").
export const flash = message => { try { sessionStorage.setItem("flash", message); } catch { } };
export const takeFlash = () => {
    try {
        const message = sessionStorage.getItem("flash");
        sessionStorage.removeItem("flash");
        return message;
    } catch { return null; }
};
// Shared edit-form screen. onSubmit gets the form's inputs by name (inputs.email.value);
// throwing an error shows its message under the form.
// Returns { form, errorMessage } so a view can wire up extra buttons (e.g. Delete).
// formClass sets the form's width: "narrow" (default) or "wide".
// submitLabel and submitClass change the main button, for forms that do something other than save.
export function formPage(root, { title, fieldsHtml, onSubmit, onAuthLost, cancelLink, extraActions = "", formClass = "narrow",
                                 submitLabel = "Save", submitClass = "" }) {
    const submitClassAttribute = submitClass ? ` class="${submitClass}"` : "";
    root.innerHTML = `
    <h1>${escapeHtml(title)}</h1>
    <form class="card ${formClass}" id="edit-form">
    ${fieldsHtml}
        <p class="err" id="form-error" role="alert"></p>
        <div class="actions">
            <button type="submit"${submitClassAttribute}>${escapeHtml(submitLabel)}</button>
            <a class="btn" href="${cancelLink}">Cancel</a>
            ${extraActions}
        </div>
    </form>`;
    const form = root.querySelector("#edit-form");
    const errorMessage = root.querySelector("#form-error");
    const saveButton = form.querySelector("button[type=submit]");
    form.onsubmit = async event => {
        event.preventDefault();
        errorMessage.textContent = "";
        saveButton.disabled = true;
        try { await onSubmit(form.elements); }
        catch (error) {
            saveButton.disabled = false;
            error.status === 401 ? onAuthLost() : (errorMessage.textContent = error.message);
        }
    };
    return { form, errorMessage };
}
// Two-click confirm for buttons that remove things, so a misclick doesn't lose anything.
// The first click shows `confirmText`; a second click runs onConfirm. It goes back to normal when the mouse
// leaves the button or focus moves away; touch and keyboard (which have no "leave") get a 4 second timeout.
// Returns a function that puts the button back to normal, for buttons that stay on the page after confirming.
export function confirmTwice(button, { confirmText, onConfirm, onArm = () => { }, onDisarm = () => { } }) {
    const original = { text: button.textContent, label: button.getAttribute("aria-label") };
    let timer;
    const disarm = () => {
        clearTimeout(timer);
        if (!button.dataset.confirming) return;
        delete button.dataset.confirming;
        button.textContent = original.text;
        if (original.label == null) button.removeAttribute("aria-label");
        else button.setAttribute("aria-label", original.label);
        onDisarm();
    };
    button.addEventListener("click", event => {
        if (!button.dataset.confirming) {
            button.dataset.confirming = "1";
            button.textContent = confirmText;
            const action = original.label ?? original.text;   // "Remove Ski at Niseko" -> "Click again to remove Ski at Niseko"
            button.setAttribute("aria-label", `Click again to ${action.charAt(0).toLowerCase()}${action.slice(1)}`);
            onArm();
            if (event.pointerType !== "mouse") timer = setTimeout(disarm, 4000);
            return;
        }
        clearTimeout(timer);
        onConfirm();
    });
    button.addEventListener("blur", disarm);
    // Touch is left out: a finger "leaves" the button the moment it lifts, which would cancel every tap.
    button.addEventListener("pointerleave", event => { if (event.pointerType === "mouse") disarm(); });
    return disarm;
}
