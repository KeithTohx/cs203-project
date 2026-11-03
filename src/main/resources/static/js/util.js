import { icon } from "./icons.js";

// Makes text safe to put inside HTML, so user and news text can never inject tags or scripts.
export const escapeHtml = value => String(value ?? "").replace(/[&<>"']/g, char =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char]));
export const formatDate = date => date ? new Date(date).toLocaleDateString(undefined,
    { day: "numeric", month: "short", year: "numeric" }) : "";
export const formatDateTime = date => date ? new Date(date).toLocaleString(undefined,
    { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" }) : "";
export const dateRange = (start, end) => {
    if (!start) return "";
    return !end || formatDate(start) === formatDate(end) ? formatDate(start) : `${formatDate(start)} – ${formatDate(end)}`;
};
export const sortByStart = activities =>
    [...activities].sort((first, second) => new Date(first.dateStart ?? 0) - new Date(second.dateStart ?? 0));
// News URLs come from an external API: only allow http(s) links.
export const safeUrl = url => /^https?:\/\//i.test(url || "") ? escapeHtml(url) : "";
// the site name from a link, so gdelt news without a source still gets a label
export const hostnameOf = url => {
    try {
        return new URL(url).hostname.replace(/^www\./, "");
    } catch {
        return "";
    }
};
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
    const submitClassAttribute = ` class="${submitClass || "primary"}"`;
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
// previous and next for a backend page, each button carries its page in data-page
export const createPager = result => {
    if (result.totalPages <= 1) return "";

    const isFirst = result.page === 0;
    const isLast = result.page >= result.totalPages - 1;

    return `
        <div class="pager">
            <button class="ghost small" type="button" data-page="${result.page - 1}"${isFirst ? " disabled" : ""}>Previous</button>
            <span class="meta">Page ${result.page + 1} of ${result.totalPages}</span>
            <button class="ghost small" type="button" data-page="${result.page + 1}"${isLast ? " disabled" : ""}>Next</button>
        </div>
    `;
};
// waits until typing stops before running, so a search box does not send a request per key
export const debounce = (action, waitMs = 300) => {
    let timer;
    return (...args) => {
        clearTimeout(timer);
        timer = setTimeout(() => action(...args), waitMs);
    };
};
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

// the bottom corner every toast stacks in, made the first time one is shown
export const findToastShelf = () => {
    let shelf = document.getElementById("toasts");
    if (!shelf) {
        shelf = document.createElement("div");
        shelf.id = "toasts";
        shelf.setAttribute("role", "status");
        shelf.setAttribute("aria-live", "polite");
        document.body.append(shelf);
    }
    return shelf;
};

// a short message in the bottom corner that fades out by itself, read out by screen readers
export function showToast(message) {
    const shelf = findToastShelf();
    const toast = document.createElement("div");
    toast.className = "toast";
    toast.innerHTML = icon("check");
    const text = document.createElement("span");
    text.textContent = message;
    toast.append(text);
    shelf.append(toast);

    setTimeout(() => toast.classList.add("leaving"), 4000);
    setTimeout(() => toast.remove(), 4400);
}

// a message left by the previous page with flash(), shown as a toast
export const showFlashToast = () => {
    const message = takeFlash();
    if (message) showToast(message);
};

// "5 minutes ago" for the last week, the date after that, and the date for anything in the future
const relative = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
export const timeAgo = date => {
    const seconds = (Date.now() - new Date(date)) / 1000;
    if (seconds < 0 || seconds > 7 * 86400) return formatDateTime(date);
    if (seconds < 60) return "just now";
    if (seconds < 3600) return relative.format(-Math.floor(seconds / 60), "minute");
    if (seconds < 86400) return relative.format(-Math.floor(seconds / 3600), "hour");
    return relative.format(-Math.floor(seconds / 86400), "day");
};

// a <time> showing timeAgo, with the full date on hover
export const timeTag = date => {
    if (!date) return "";
    const iso = new Date(date).toISOString();
    return `<time datetime="${iso}" title="${escapeHtml(formatDateTime(date))}">${escapeHtml(timeAgo(date))}</time>`;
};

// country name -> flag emoji, from the browser's own region names plus the wikidata names it spells
// differently. windows has no flag emoji and shows two letters instead
const COUNTRY_CODE_ALIASES = {
    "antigua and barbuda": "AG", "bosnia and herzegovina": "BA", "the bahamas": "BS",
    "democratic republic of the congo": "CD", "republic of the congo": "CG", "ivory coast": "CI",
    "people's republic of china": "CN", "czech republic": "CZ", "federated states of micronesia": "FM",
    "the gambia": "GM", "saint kitts and nevis": "KN", "saint lucia": "LC", "myanmar": "MM",
    "kingdom of the netherlands": "NL", "palestine": "PS", "são tomé and príncipe": "ST", "turkey": "TR",
    "trinidad and tobago": "TT", "saint vincent and the grenadines": "VC", "hong kong": "HK",
    "macau": "MO", "curacao": "CW", "reunion": "RE",
};
const flagOfCode = code => String.fromCodePoint(...[...code].map(letter => 0x1f1a5 + letter.charCodeAt(0)));

let codesByName = null;
export const flagFor = countryName => {
    if (!codesByName) {
        codesByName = new Map(Object.entries(COUNTRY_CODE_ALIASES));
        const names = new Intl.DisplayNames(["en"], { type: "region" });
        for (let first = 65; first <= 90; first++) {
            for (let second = 65; second <= 90; second++) {
                const code = String.fromCharCode(first, second);
                const name = names.of(code);
                if (name && name !== code && !codesByName.has(name.toLowerCase())) codesByName.set(name.toLowerCase(), code);
            }
        }
    }
    const code = codesByName.get(String(countryName ?? "").toLowerCase());
    return code ? flagOfCode(code) : "";
};

// an empty list: an icon, what is missing, and what to do about it
export const emptyState = ({ iconHtml, title, text, action = "" }) => `
    <div class="empty-state">
        ${iconHtml}
        <h2>${escapeHtml(title)}</h2>
        <p class="meta">${escapeHtml(text)}</p>
        ${action}
    </div>
`;
