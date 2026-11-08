// admin console: one account's itineraries, activities, impacts and recommendations, with add,
// edit and delete for each. mostly for testing, eg making an impact by hand to see the alert and
// recommendation flow without waiting for the model.
//
// everything is loaded in one go and every section draws from that, since a change in one
// (deleting an itinerary) also removes rows in the others (its impacts and recommendations)
import { api } from "../api.js";
import { confirmTwice, dateRange, escapeHtml, formatDateTime, newsTitleOf, showError, showToast } from "../util.js";

// same values as the backend enums
const CATEGORIES = [
    { value: "SKI_SNOW", label: "Ski and snow" },
    { value: "BEACH_WATER", label: "Beach and water" },
    { value: "OUTDOOR_NATURE", label: "Outdoor and nature" },
    { value: "SIGHTSEEING", label: "Sightseeing" },
    { value: "MUSEUM_GALLERY", label: "Museum or gallery" },
    { value: "FOOD_DINING", label: "Food and dining" },
    { value: "SHOPPING", label: "Shopping" },
    { value: "WELLNESS_SPA", label: "Wellness and spa" },
];
const SEVERITIES = ["HIGH", "MEDIUM", "LOW"];
const ACTIONS = ["REPLACE", "RELOCATE", "RESCHEDULE", "MONITOR", "CANCEL"];
const STATUSES = ["PENDING", "ACCEPTED", "DISMISSED"];

// how many recent news articles the impact form offers
const NEWS_CHOICES = 100;

// ---------- small helpers ----------

// "2026-12-16T09:00:00" -> input values, and back. the backend uses LocalDateTime
const toDateInput = dateTime => dateTime ? String(dateTime).slice(0, 10) : "";
const toDateTimeInput = dateTime => dateTime ? String(dateTime).slice(0, 16) : "";
const fromDateInput = value => value ? `${value}T00:00:00` : null;
const fromDateTimeInput = value => value ? `${value}:00` : null;

const textOrNull = input => input.value.trim() || null;
const numberOrNull = input => input.value === "" ? null : Number(input.value);

const checkEndNotBeforeStart = (start, end) => {
    if (start && end && end < start) throw new Error("The end cannot be before the start.");
};

// a <select>. choices are { value, label }, and an emptyLabel adds a "nothing chosen" option first
const createSelect = (name, label, choices, selected, { emptyLabel = null, required = false } = {}) => {
    const empty = emptyLabel == null ? "" : `<option value="">${escapeHtml(emptyLabel)}</option>`;
    const options = choices.map(choice => {
        const isSelected = String(choice.value) === String(selected ?? "");
        return `<option value="${escapeHtml(choice.value)}"${isSelected ? " selected" : ""}>${escapeHtml(choice.label)}</option>`;
    }).join("");

    return `
        <label>
            ${escapeHtml(label)}
            <select name="${name}"${required ? " required" : ""}>
                ${empty}
                ${options}
            </select>
        </label>
    `;
};

const createInput = (name, label, attributes = "") => `
    <label>
        ${escapeHtml(label)}
        <input name="${name}" ${attributes}>
    </label>
`;

const createTextarea = (name, label, value, maxLength) => `
    <label class="span-all">
        ${escapeHtml(label)}
        <textarea name="${name}" rows="2" maxlength="${maxLength}">${escapeHtml(value ?? "")}</textarea>
    </label>
`;

const createCheckbox = (name, label, checked) => `
    <label class="check">
        <input type="checkbox" name="${name}"${checked ? " checked" : ""}>
        ${escapeHtml(label)}
    </label>
`;

const plainChoices = values => values.map(value => ({ value, label: value }));

const severityTag = severity => {
    const tagClass = { HIGH: "tag bad", MEDIUM: "tag warn", LOW: "tag" }[severity] ?? "tag";
    return `<span class="${tagClass}">${escapeHtml(severity)}</span>`;
};

const statusTag = status => {
    const tagClass = { ACCEPTED: "tag ok", DISMISSED: "tag" }[status] ?? "tag warn";
    return `<span class="${tagClass}">${escapeHtml(status)}</span>`;
};

// what an impact is about, for the table and the recommendation form
const impactTarget = impact => impact.activityName
    ? `${impact.itineraryName ?? "?"} › ${impact.activityName}`
    : `${impact.itineraryName ?? "?"} (whole trip)`;

const impactLabel = impact => `#${impact.id} ${newsTitleOf(impact.newsTitle)}, on ${impactTarget(impact)}`;

// ---------- one section: a table plus an add or edit form ----------

// config:
//   key, noun           - eg "itinerary", used in ids, buttons and messages
//   rows(state)         - the records to show, or null when the section is waiting on something
//   waitingText         - shown when rows gives null
//   emptyText           - shown when there are no rows
//   columns             - [{ label, cell: record => html, long }], long gives free text room to breathe
//   rowButtons(record)  - extra buttons for a row (optional)
//   fields(record, state) - the form fields, record is null when adding
//   wireForm(form, state) - hook up anything the fields need once drawn (optional)
//   readForm(inputs, record) - the request body, throwing an Error shows its message
//   create(body), update(id, body), remove(id)
function mountSection(card, config, { getState, onChange, onAuthLost }) {
    const list = card.querySelector("[data-list]");
    const formSlot = card.querySelector("[data-form-slot]");
    const addButton = card.querySelector("[data-add]");
    const sectionError = card.querySelector("[data-section-error]");
    let shownRows = [];

    const closeForm = () => {
        formSlot.innerHTML = "";
        formSlot.hidden = true;
        addButton.hidden = getState() == null || config.rows(getState()) == null;
    };

    const openForm = record => {
        const isNew = record == null;
        const state = getState();
        const title = isNew ? `New ${config.noun}` : `Edit ${config.noun} #${Number(record.id)}`;

        formSlot.hidden = false;
        addButton.hidden = true;
        formSlot.innerHTML = `
            <form class="admin-form">
                <h3>${escapeHtml(title)}</h3>
                <div class="field-grid">
                    ${config.fields(record, state)}
                </div>
                <p class="err" data-form-error role="alert"></p>
                <div class="actions">
                    <button type="submit" class="primary">Save</button>
                    <button type="button" class="ghost" data-cancel>Cancel</button>
                </div>
            </form>
        `;

        const form = formSlot.querySelector("form");
        const errorMessage = form.querySelector("[data-form-error]");
        const saveButton = form.querySelector("button[type=submit]");

        config.wireForm?.(form, state);
        form.querySelector("[data-cancel]").addEventListener("click", closeForm);
        form.querySelector("input, select, textarea")?.focus();

        form.addEventListener("submit", async event => {
            event.preventDefault();
            errorMessage.textContent = "";
            saveButton.disabled = true;

            try {
                const body = config.readForm(form.elements, record);
                if (isNew) {
                    await config.create(body);
                } else {
                    await config.update(record.id, body);
                }
            } catch (error) {
                saveButton.disabled = false;
                if (error.status === 401) {
                    onAuthLost();
                    return;
                }
                errorMessage.textContent = error.message;
                return;
            }

            closeForm();
            showToast(`${config.noun.charAt(0).toUpperCase()}${config.noun.slice(1)} saved.`);
            await onChange();
        });
    };

    const createRow = record => {
        const cells = config.columns.map(column => {
            const classAttribute = column.long ? ' class="long-text"' : "";
            return `<td${classAttribute}>${column.cell(record)}</td>`;
        }).join("");
        const extraButtons = config.rowButtons ? config.rowButtons(record) : "";

        return `
            <tr>
                ${cells}
                <td class="row-actions-cell">
                    <div class="row-actions">
                        ${extraButtons}
                        <button class="ghost small" type="button" data-edit="${Number(record.id)}">Edit</button>
                        <button class="ghost small danger" type="button" data-delete="${Number(record.id)}"
                                aria-label="Delete ${escapeHtml(config.noun)} ${Number(record.id)}">Delete</button>
                    </div>
                </td>
            </tr>
        `;
    };

    const createTable = rows => {
        const headings = config.columns.map(column => `<th scope="col">${escapeHtml(column.label)}</th>`).join("");

        return `
            <div class="table-scroll">
                <table class="data-table">
                    <thead>
                        <tr>
                            ${headings}
                            <th scope="col"><span class="sr-only">Actions</span></th>
                        </tr>
                    </thead>
                    <tbody>
                        ${rows.map(createRow).join("")}
                    </tbody>
                </table>
            </div>
        `;
    };

    // deleting needs two clicks, like everywhere else in the app
    const wireDeleteButtons = () => {
        for (const button of list.querySelectorAll("[data-delete]")) {
            const id = Number(button.dataset.delete);

            confirmTwice(button, { confirmText: "Delete?", onConfirm: async () => {
                button.disabled = true;
                sectionError.textContent = "";
                try {
                    await config.remove(id);
                } catch (error) {
                    button.disabled = false;
                    if (error.status === 401) {
                        onAuthLost();
                        return;
                    }
                    sectionError.textContent = error.message;
                    return;
                }
                showToast(`Deleted ${config.noun} #${id}.`);
                await onChange();
            } });
        }
    };

    const draw = () => {
        const rows = config.rows(getState());
        shownRows = rows ?? [];

        if (rows == null) {
            list.innerHTML = `<p class="meta">${escapeHtml(config.waitingText)}</p>`;
        } else if (!rows.length) {
            list.innerHTML = `<p class="meta">${escapeHtml(config.emptyText)}</p>`;
        } else {
            list.innerHTML = createTable(rows);
            wireDeleteButtons();
        }

        // an open form keeps its typing when the lists redraw. if its record was deleted meanwhile,
        // saving just shows the 404 under the form
        const formIsOpen = !formSlot.hidden;
        if (!formIsOpen) addButton.hidden = rows == null;
    };

    addButton.addEventListener("click", () => openForm(null));
    list.addEventListener("click", event => {
        const editButton = event.target.closest("[data-edit]");
        if (!editButton) return;

        const record = shownRows.find(row => row.id === Number(editButton.dataset.edit));
        if (record) openForm(record);
    });

    closeForm();
    return { draw, closeForm };
}

// ---------- the four sections ----------

const createSectionCard = (key, title, addLabel) => `
    <section class="card admin-data" id="admin-${key}">
        <div class="row">
            <h2 data-title>${escapeHtml(title)}</h2>
            <button class="ghost small" type="button" data-add>${escapeHtml(addLabel)}</button>
        </div>
        <p class="err" data-section-error role="alert"></p>
        <div data-form-slot hidden></div>
        <div data-list>
            <div class="msg">Loading…</div>
        </div>
    </section>
`;

const itineraryConfig = (userId, lookups, view) => ({
    key: "itineraries",
    noun: "itinerary",
    rows: state => state.itineraries,
    emptyText: "No itineraries yet.",
    columns: [
        { label: "ID", cell: itinerary => Number(itinerary.id) },
        { label: "Name", cell: itinerary => `<strong>${escapeHtml(itinerary.name)}</strong>` },
        { label: "Country", cell: itinerary => escapeHtml(itinerary.countryName) },
        { label: "Dates", cell: itinerary => `<span class="nowrap">${escapeHtml(dateRange(itinerary.dateStart, itinerary.dateEnd))}</span>` },
    ],
    rowButtons: itinerary => {
        const isSelected = itinerary.id === view.selectedItineraryId;
        return `
            <button class="ghost small" type="button" data-show-activities="${Number(itinerary.id)}"${isSelected ? ' aria-pressed="true"' : ""}>
                Activities
            </button>
        `;
    },
    fields: itinerary => {
        const countryChoices = lookups.countries.map(country => ({ value: country.id, label: country.name }));
        return `
            ${createInput("name", "Name", `value="${escapeHtml(itinerary?.name)}" maxlength="100" required`)}
            ${createSelect("countryId", "Country", countryChoices, itinerary?.countryId, { emptyLabel: "Pick a country", required: true })}
            ${createInput("dateStart", "Start date", `type="date" value="${toDateInput(itinerary?.dateStart)}"`)}
            ${createInput("dateEnd", "End date", `type="date" value="${toDateInput(itinerary?.dateEnd)}"`)}
        `;
    },
    readForm: inputs => {
        checkEndNotBeforeStart(inputs.dateStart.value, inputs.dateEnd.value);
        return {
            name: inputs.name.value.trim(),
            countryId: Number(inputs.countryId.value),
            dateStart: fromDateInput(inputs.dateStart.value),
            dateEnd: fromDateInput(inputs.dateEnd.value),
        };
    },
    create: body => api.adminAddItinerary(userId, body),
    update: (id, body) => api.adminUpdateItinerary(id, body),
    remove: id => api.adminDeleteItinerary(id),
});

const activityConfig = view => ({
    key: "activities",
    noun: "activity",
    rows: state => {
        if (view.selectedItineraryId == null) return null;
        return state.activitiesByItinerary.get(view.selectedItineraryId) ?? null;
    },
    waitingText: "Pick Activities on an itinerary above to see and change its activities.",
    emptyText: "This itinerary has no activities yet.",
    columns: [
        { label: "ID", cell: activity => Number(activity.id) },
        { label: "Name", cell: activity => `<strong>${escapeHtml(activity.name)}</strong>` },
        { label: "Address", cell: activity => escapeHtml(activity.address ?? "") },
        { label: "When", cell: activity => `<span class="nowrap">${escapeHtml(formatDateTime(activity.dateStart))}</span>` },
        { label: "Category", cell: activity => escapeHtml(CATEGORIES.find(category => category.value === activity.category)?.label ?? "") },
    ],
    fields: activity => `
        ${createInput("name", "Name", `value="${escapeHtml(activity?.name)}" maxlength="100" required`)}
        ${createInput("address", "Address", `value="${escapeHtml(activity?.address)}" maxlength="200"`)}
        ${createInput("dateStart", "Starts", `type="datetime-local" value="${toDateTimeInput(activity?.dateStart)}"`)}
        ${createInput("dateEnd", "Ends", `type="datetime-local" value="${toDateTimeInput(activity?.dateEnd)}"`)}
        ${createSelect("category", "Category", CATEGORIES, activity?.category, { emptyLabel: "None" })}
    `,
    readForm: inputs => {
        checkEndNotBeforeStart(inputs.dateStart.value, inputs.dateEnd.value);
        return {
            name: inputs.name.value.trim(),
            address: textOrNull(inputs.address),
            dateStart: fromDateTimeInput(inputs.dateStart.value),
            dateEnd: fromDateTimeInput(inputs.dateEnd.value),
            category: inputs.category.value || null,
        };
    },
    create: body => api.adminAddActivity(view.selectedItineraryId, body),
    update: (id, body) => api.adminUpdateActivity(id, body),
    remove: id => api.adminDeleteActivity(id),
});

// the activity dropdown only offers the chosen itinerary's activities
const activityChoicesFor = (state, itineraryId) => {
    const activities = state.activitiesByItinerary.get(Number(itineraryId)) ?? [];
    return activities.map(activity => ({ value: activity.id, label: `#${activity.id} ${activity.name}` }));
};

const impactConfig = (userId, lookups) => ({
    key: "impacts",
    noun: "impact",
    rows: state => state.impacts,
    emptyText: "No impacts yet. Add one by hand to test alerts and recommendations without the model.",
    columns: [
        { label: "ID", cell: impact => Number(impact.id) },
        { label: "News", long: true, cell: impact => escapeHtml(newsTitleOf(impact.newsTitle)) },
        { label: "On", cell: impact => escapeHtml(impactTarget(impact)) },
        {
            label: "Verdict",
            cell: impact => {
                const verdict = impact.impacted ? `<span class="tag bad">Impacted</span>` : `<span class="tag ok">Not impacted</span>`;
                const rejected = impact.rejected ? ` <span class="tag">Rejected</span>` : "";
                return verdict + rejected;
            },
        },
        { label: "Reason", long: true, cell: impact => escapeHtml(impact.reason ?? "") },
    ],
    fields: (impact, state) => {
        // once made, an impact is only re-judged, so what it is about is shown but not editable
        if (impact) {
            return `
                <dl class="span-all">
                    <dt>News</dt>
                    <dd>${escapeHtml(newsTitleOf(impact.newsTitle))}</dd>
                    <dt>On</dt>
                    <dd>${escapeHtml(impactTarget(impact))}</dd>
                </dl>
                ${createCheckbox("impacted", "Impacted", impact.impacted)}
                ${createCheckbox("rejected", "Rejected by the traveller", impact.rejected)}
                ${createTextarea("reason", "Reason", impact.reason, 500)}
            `;
        }

        const itineraryChoices = state.itineraries.map(itinerary => ({ value: itinerary.id, label: `#${itinerary.id} ${itinerary.name}` }));
        const newsChoices = lookups.news.map(article => ({ value: article.id, label: `#${article.id} ${newsTitleOf(article.title)}` }));
        const firstItineraryId = state.itineraries[0]?.id;

        return `
            ${createSelect("itineraryId", "Itinerary", itineraryChoices, firstItineraryId, { required: true })}
            ${createSelect("activityId", "Activity", activityChoicesFor(state, firstItineraryId), "", { emptyLabel: "Whole trip" })}
            <div class="span-all">
                ${createSelect("newsId", "News", newsChoices, "", { emptyLabel: "Pick a news article", required: true })}
            </div>
            ${createCheckbox("impacted", "Impacted", true)}
            ${createCheckbox("rejected", "Rejected by the traveller", false)}
            ${createTextarea("reason", "Reason (leave blank and the evaluator will judge it again later)", "", 500)}
            <div class="span-all">
                ${createCheckbox("sendAlert", "Raise an alert for the traveller (this also emails them)", false)}
            </div>
        `;
    },
    wireForm: (form, state) => {
        const itinerarySelect = form.elements.itineraryId;
        const activitySelect = form.elements.activityId;
        if (!itinerarySelect || !activitySelect) return;

        itinerarySelect.addEventListener("change", () => {
            const choices = activityChoicesFor(state, itinerarySelect.value);
            const options = choices.map(choice => `<option value="${escapeHtml(choice.value)}">${escapeHtml(choice.label)}</option>`).join("");
            activitySelect.innerHTML = `<option value="">Whole trip</option>${options}`;
        });
    },
    readForm: (inputs, impact) => {
        const verdict = {
            impacted: inputs.impacted.checked,
            reason: textOrNull(inputs.reason),
            rejected: inputs.rejected.checked,
        };
        if (impact) return verdict;

        return {
            ...verdict,
            itineraryId: Number(inputs.itineraryId.value),
            activityId: numberOrNull(inputs.activityId),
            newsId: Number(inputs.newsId.value),
            sendAlert: inputs.sendAlert.checked,
        };
    },
    create: body => api.adminAddImpact(userId, body),
    update: (id, body) => api.adminUpdateImpact(id, body),
    remove: id => api.adminDeleteImpact(id),
});

const recommendationConfig = () => ({
    key: "recommendations",
    noun: "recommendation",
    rows: state => state.recommendations,
    emptyText: "No recommendations yet.",
    columns: [
        { label: "ID", cell: recommendation => Number(recommendation.id) },
        { label: "Impact", cell: recommendation => `#${Number(recommendation.impactId)}` },
        // severity and status share a cell so all eight things fit without scrolling sideways
        {
            label: "Severity, status",
            cell: recommendation => `
                <div class="tag-stack">
                    ${severityTag(recommendation.severity)}
                    ${statusTag(recommendation.status)}
                </div>
            `,
        },
        { label: "Action", cell: recommendation => escapeHtml(recommendation.action) },
        { label: "Option", cell: recommendation => escapeHtml(recommendation.optionName ?? "") },
        { label: "Advice", long: true, cell: recommendation => escapeHtml(recommendation.advice ?? "") },
    ],
    fields: (recommendation, state) => {
        const impactField = recommendation
            ? `
                <dl class="span-all">
                    <dt>Impact</dt>
                    <dd>#${Number(recommendation.impactId)}</dd>
                </dl>
            `
            : `
                <div class="span-all">
                    ${createSelect("impactId", "Impact", state.impacts.map(impact => ({ value: impact.id, label: impactLabel(impact) })), "", { emptyLabel: "Pick an impact", required: true })}
                </div>
            `;

        return `
            ${impactField}
            ${createSelect("severity", "Severity", plainChoices(SEVERITIES), recommendation?.severity ?? "MEDIUM", { required: true })}
            ${createSelect("action", "Action", plainChoices(ACTIONS), recommendation?.action ?? "MONITOR", { required: true })}
            ${createSelect("status", "Status", plainChoices(STATUSES), recommendation?.status ?? "PENDING", { required: true })}
            ${createInput("optionId", "Activity option ID (optional)", `type="number" min="1" value="${recommendation?.optionId ?? ""}"`)}
            ${createTextarea("advice", "Advice", recommendation?.advice, 500)}
        `;
    },
    readForm: inputs => ({
        // only on create, the edit form has no impact field
        impactId: inputs.impactId ? Number(inputs.impactId.value) : null,
        severity: inputs.severity.value,
        action: inputs.action.value,
        status: inputs.status.value,
        optionId: numberOrNull(inputs.optionId),
        advice: textOrNull(inputs.advice),
    }),
    create: ({ impactId, ...body }) => api.adminAddRecommendation(impactId, body),
    update: (id, { impactId, ...body }) => api.adminUpdateRecommendation(id, body),
    remove: id => api.adminDeleteRecommendation(id),
});

// ---------- loading ----------

async function loadState(userId) {
    const [itineraries, impacts, recommendations] = await Promise.all([
        api.adminItineraries(userId),
        api.adminImpacts(userId),
        api.adminRecommendations(userId),
    ]);

    // every itinerary's activities up front, so the impact form can offer them without waiting
    const activityLists = await Promise.all(itineraries.map(itinerary => api.adminActivities(itinerary.id)));
    const activitiesByItinerary = new Map();
    itineraries.forEach((itinerary, index) => activitiesByItinerary.set(itinerary.id, activityLists[index]));

    return { itineraries, activitiesByItinerary, impacts, recommendations };
}

// draws the console into container. onChange runs after every saved change, eg to refresh the audit list
export async function mountUserData(container, userId, onAuthLost, { onChange = () => { } } = {}) {
    container.innerHTML = `
        <h2 class="form-section">Trip data</h2>
        <p class="meta hint">
            Everything this account owns, for testing. Changes are recorded in the activity below.
            New itineraries and activities are checked against recent news, like a traveller's.
        </p>
        ${createSectionCard("itineraries", "Itineraries", "Add itinerary")}
        ${createSectionCard("activities", "Activities", "Add activity")}
        ${createSectionCard("impacts", "Impacts", "Add impact")}
        ${createSectionCard("recommendations", "Recommendations", "Add recommendation")}
    `;

    let lookups;
    try {
        const [countries, news] = await Promise.all([
            api.countries(),
            api.news({ page: 0, size: NEWS_CHOICES }),
        ]);
        lookups = { countries: countries ?? [], news: news ?? [] };
    } catch (error) {
        return showError(container, error, onAuthLost);
    }

    let state = null;
    const view = { selectedItineraryId: null };
    const getState = () => state;

    const activitiesCard = container.querySelector("#admin-activities");
    const activitiesTitle = activitiesCard.querySelector("[data-title]");

    const drawAll = () => {
        // the picked itinerary may have just been deleted
        const stillThere = state.itineraries.some(itinerary => itinerary.id === view.selectedItineraryId);
        if (!stillThere) view.selectedItineraryId = null;

        const selected = state.itineraries.find(itinerary => itinerary.id === view.selectedItineraryId);
        activitiesTitle.textContent = selected ? `Activities in ${selected.name}` : "Activities";

        for (const section of sections) section.draw();
    };

    const refresh = async () => {
        try {
            state = await loadState(userId);
        } catch (error) {
            if (error.status === 401) return onAuthLost();
            showToast(error.message);
            return;
        }
        drawAll();
    };

    const afterChange = async () => {
        await refresh();
        onChange();
    };

    const hooks = { getState, onChange: afterChange, onAuthLost };
    const itinerarySection = mountSection(container.querySelector("#admin-itineraries"), itineraryConfig(userId, lookups, view), hooks);
    const activitySection = mountSection(activitiesCard, activityConfig(view), hooks);
    const sections = [
        itinerarySection,
        activitySection,
        mountSection(container.querySelector("#admin-impacts"), impactConfig(userId, lookups), hooks),
        mountSection(container.querySelector("#admin-recommendations"), recommendationConfig(), hooks),
    ];

    // picking an itinerary's activities swaps the activities section over to it
    container.querySelector("#admin-itineraries").addEventListener("click", event => {
        const button = event.target.closest("[data-show-activities]");
        if (!button) return;

        view.selectedItineraryId = Number(button.dataset.showActivities);
        activitySection.closeForm();
        drawAll();
        activitiesCard.scrollIntoView({ behavior: "smooth", block: "nearest" });
    });

    await refresh();
}
