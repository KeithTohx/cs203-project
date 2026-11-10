// admin console: one account's itineraries, activities, impacts and recommendations, with add,
// edit and delete for each. mostly for testing, eg making an impact by hand to see the alert and
// recommendation flow without waiting for the model.
//
// the itineraries table sums up what each trip holds. opening one shows its activities, with the
// impacts on each activity under it, and the impacts on the whole trip above them.
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

// the inputs cut off the time or the seconds, so an untouched input sends back the stored value.
// otherwise the backend would see a new start and clear the verdicts on a rename
const keepIfUntouched = (inputValue, original, toInput, fromInput) => {
    const untouched = original != null && inputValue === toInput(original);
    return untouched ? original : fromInput(inputValue);
};

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

// only on an edit form, a new record has no impacts to keep
const createKeepImpactsField = (record, whatClears) => {
    if (!record) return "";

    return `
        <div class="span-all">
            ${createCheckbox("keepImpacts", "Keep its impacts (for a small fix, like a typo)", false)}
            <p class="meta hint">Otherwise changing ${escapeHtml(whatClears)} clears its impacts and checks it against the news again.</p>
        </div>
    `;
};

// false on a create form, which has no checkbox
const keepsImpacts = inputs => Boolean(inputs.keepImpacts?.checked);

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

const countOf = (count, one, many) => `${count} ${count === 1 ? one : many}`;

// how many activity names an itinerary's summary lists before it says "+2 more"
const SUMMARY_NAMES = 3;

// ---------- shared by every section: a table of records, and an add or edit form ----------

// config:
//   key, noun           - eg "impacts" and "impact", used in buttons and messages
//   columns             - [{ label, cell: record => html, long }], long gives free text room to breathe
//   rowButtons(record)  - extra buttons for a row (optional)
//   fields(record, state) - the form fields, record is null when adding
//   readForm(inputs, record) - the request body, throwing an Error shows its message
//   create(body), update(id, body), remove(id)

// edit and delete for one record. data-kind keeps them apart when one card holds more than one
// kind of record, like an activity and the impacts under it
const createRecordButtons = (config, record) => `
    <button class="ghost small" type="button" data-kind="${config.key}" data-edit="${Number(record.id)}">Edit</button>
    <button class="ghost small danger" type="button" data-kind="${config.key}" data-delete="${Number(record.id)}"
            aria-label="Delete ${escapeHtml(config.noun)} ${Number(record.id)}">Delete</button>
`;

const createRow = (config, record) => {
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
                    ${createRecordButtons(config, record)}
                </div>
            </td>
        </tr>
    `;
};

const createTable = (config, rows) => {
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
                    ${rows.map(record => createRow(config, record)).join("")}
                </tbody>
            </table>
        </div>
    `;
};

// fills slot with an add form (record is null) or an edit form.
// onSaved runs after a successful save, onCancel when the form is closed without saving
function openRecordForm(slot, config, record, { state, onSaved, onCancel, onAuthLost }) {
    const isNew = record == null;
    const title = isNew ? `New ${config.noun}` : `Edit ${config.noun} #${Number(record.id)}`;

    slot.hidden = false;
    slot.innerHTML = `
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

    const form = slot.querySelector("form");
    const errorMessage = form.querySelector("[data-form-error]");
    const saveButton = form.querySelector("button[type=submit]");

    form.querySelector("[data-cancel]").addEventListener("click", onCancel);
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

        showToast(`${config.noun.charAt(0).toUpperCase()}${config.noun.slice(1)} saved.`);
        await onSaved();
    });
}

// arms every delete button for this kind of record inside container. two clicks, like everywhere
// else in the app
function wireDeleteButtons(container, config, { errorElement, onChange, onAuthLost }) {
    for (const button of container.querySelectorAll(`[data-delete][data-kind="${config.key}"]`)) {
        const id = Number(button.dataset.delete);

        confirmTwice(button, { confirmText: "Delete?", onConfirm: async () => {
            button.disabled = true;
            errorElement.textContent = "";
            try {
                await config.remove(id);
            } catch (error) {
                button.disabled = false;
                if (error.status === 401) {
                    onAuthLost();
                    return;
                }
                errorElement.textContent = error.message;
                return;
            }
            showToast(`Deleted ${config.noun} #${id}.`);
            await onChange();
        } });
    }
}

// ---------- a card holding one table, used for itineraries and recommendations ----------

// config also has rows(state) and emptyText
function mountSection(card, config, { getState, onChange, onAuthLost }) {
    const list = card.querySelector("[data-list]");
    const formSlot = card.querySelector("[data-form-slot]");
    const addButton = card.querySelector("[data-add]");
    const sectionError = card.querySelector("[data-section-error]");
    let shownRows = [];

    const closeForm = () => {
        formSlot.innerHTML = "";
        formSlot.hidden = true;
        addButton.hidden = getState() == null;
    };

    const openForm = record => {
        addButton.hidden = true;
        openRecordForm(formSlot, config, record, {
            state: getState(),
            onSaved: async () => {
                closeForm();
                await onChange();
            },
            onCancel: closeForm,
            onAuthLost,
        });
    };

    const draw = () => {
        const rows = config.rows(getState());
        shownRows = rows;

        if (!rows.length) {
            list.innerHTML = `<p class="meta">${escapeHtml(config.emptyText)}</p>`;
        } else {
            list.innerHTML = createTable(config, rows);
            wireDeleteButtons(list, config, { errorElement: sectionError, onChange, onAuthLost });
        }

        // an open form keeps its typing when the lists redraw. if its record was deleted meanwhile,
        // saving just shows the 404 under the form
        const formIsOpen = !formSlot.hidden;
        if (!formIsOpen) addButton.hidden = false;
    };

    addButton.addEventListener("click", () => openForm(null));
    list.addEventListener("click", event => {
        const editButton = event.target.closest(`[data-edit][data-kind="${config.key}"]`);
        if (!editButton) return;

        const record = shownRows.find(row => row.id === Number(editButton.dataset.edit));
        if (record) openForm(record);
    });

    closeForm();
    return { draw, closeForm };
}

const createSectionCard = (key, title, addLabel) => `
    <section class="card admin-data" id="admin-${key}">
        <div class="row">
            <h2>${escapeHtml(title)}</h2>
            <button class="ghost small" type="button" data-add>${escapeHtml(addLabel)}</button>
        </div>
        <p class="err" data-section-error role="alert"></p>
        <div data-form-slot hidden></div>
        <div data-list>
            <div class="msg">Loading…</div>
        </div>
    </section>
`;

// ---------- what each kind of record looks like ----------

// what an itinerary holds, shown in its row so the trips can be compared without opening each one
const createTripSummary = (state, itinerary) => {
    const itsActivities = state.activitiesByItinerary.get(itinerary.id) ?? [];
    const itsImpacts = state.impacts.filter(impact => impact.itineraryId === itinerary.id);
    const onWholeTrip = itsImpacts.filter(impact => impact.activityId == null).length;
    const live = itsImpacts.filter(impact => impact.impacted && !impact.rejected).length;

    let activityLine = "No activities";
    if (itsActivities.length) {
        const names = itsActivities.slice(0, SUMMARY_NAMES).map(activity => escapeHtml(activity.name)).join(", ");
        const hidden = itsActivities.length - SUMMARY_NAMES;
        const more = hidden > 0 ? `, +${hidden} more` : "";
        activityLine = `${countOf(itsActivities.length, "activity", "activities")}: ${names}${more}`;
    }

    let impactLine = "No impacts";
    if (itsImpacts.length) {
        impactLine = countOf(itsImpacts.length, "impact", "impacts");
        if (onWholeTrip) impactLine += `, ${onWholeTrip} on the whole trip`;
    }
    const liveTag = live ? `<span class="tag bad">${live} impacted</span>` : "";

    return `
        <div class="trip-summary">
            <span>${activityLine}</span>
            <span>${impactLine}${liveTag}</span>
        </div>
    `;
};

const itineraryConfig = (userId, lookups, view, getState) => ({
    key: "itineraries",
    noun: "itinerary",
    rows: state => state.itineraries,
    emptyText: "No itineraries yet.",
    columns: [
        { label: "ID", cell: itinerary => Number(itinerary.id) },
        { label: "Name", cell: itinerary => `<strong>${escapeHtml(itinerary.name)}</strong>` },
        { label: "Country", cell: itinerary => escapeHtml(itinerary.countryName) },
        { label: "Dates", cell: itinerary => `<span class="nowrap">${escapeHtml(dateRange(itinerary.dateStart, itinerary.dateEnd))}</span>` },
        { label: "Holds", long: true, cell: itinerary => createTripSummary(getState(), itinerary) },
    ],
    rowButtons: itinerary => {
        const isOpen = itinerary.id === view.selectedItineraryId;
        return `
            <button class="ghost small" type="button" data-open-itinerary="${Number(itinerary.id)}" aria-pressed="${isOpen}">
                Open
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
            ${createKeepImpactsField(itinerary, "the name, country or dates")}
        `;
    },
    readForm: (inputs, itinerary) => {
        checkEndNotBeforeStart(inputs.dateStart.value, inputs.dateEnd.value);
        return {
            name: inputs.name.value.trim(),
            countryId: Number(inputs.countryId.value),
            dateStart: keepIfUntouched(inputs.dateStart.value, itinerary?.dateStart, toDateInput, fromDateInput),
            dateEnd: keepIfUntouched(inputs.dateEnd.value, itinerary?.dateEnd, toDateInput, fromDateInput),
            keepImpacts: keepsImpacts(inputs),
        };
    },
    create: body => api.adminAddItinerary(userId, body),
    update: (id, body) => api.adminUpdateItinerary(id, body),
    remove: id => api.adminDeleteItinerary(id),
});

// the line under an activity's name: when, where and what kind
const activityDetails = activity => {
    const parts = [];
    if (activity.dateStart) parts.push(formatDateTime(activity.dateStart));
    if (activity.address) parts.push(activity.address);

    const category = CATEGORIES.find(choice => choice.value === activity.category);
    if (category) parts.push(category.label);

    return parts.map(escapeHtml).join(" · ");
};

const activityConfig = view => ({
    key: "activities",
    noun: "activity",
    fields: activity => `
        ${createInput("name", "Name", `value="${escapeHtml(activity?.name)}" maxlength="100" required`)}
        ${createInput("address", "Address", `value="${escapeHtml(activity?.address)}" maxlength="200"`)}
        ${createInput("dateStart", "Starts", `type="datetime-local" value="${toDateTimeInput(activity?.dateStart)}"`)}
        ${createInput("dateEnd", "Ends", `type="datetime-local" value="${toDateTimeInput(activity?.dateEnd)}"`)}
        ${createSelect("category", "Category", CATEGORIES, activity?.category, { emptyLabel: "None" })}
        ${createKeepImpactsField(activity, "the name, address or start")}
    `,
    readForm: (inputs, activity) => {
        checkEndNotBeforeStart(inputs.dateStart.value, inputs.dateEnd.value);
        return {
            name: inputs.name.value.trim(),
            address: textOrNull(inputs.address),
            dateStart: keepIfUntouched(inputs.dateStart.value, activity?.dateStart, toDateTimeInput, fromDateTimeInput),
            dateEnd: keepIfUntouched(inputs.dateEnd.value, activity?.dateEnd, toDateTimeInput, fromDateTimeInput),
            category: inputs.category.value || null,
            keepImpacts: keepsImpacts(inputs),
        };
    },
    create: body => api.adminAddActivity(view.selectedItineraryId, body),
    update: (id, body) => api.adminUpdateActivity(id, body),
    remove: id => api.adminDeleteActivity(id),
});

// place is where a new impact goes: { itineraryId, itineraryName, activityId, activityName },
// with no activity for a whole trip impact. it comes from the group the add button was in, so
// the form does not need to ask
const impactConfig = (userId, lookups, place) => ({
    key: "impacts",
    noun: "impact",
    // which trip and activity it is on is shown by where the table sits
    columns: [
        { label: "ID", cell: impact => Number(impact.id) },
        { label: "News", long: true, cell: impact => escapeHtml(newsTitleOf(impact.newsTitle)) },
        {
            label: "Verdict",
            cell: impact => {
                const verdict = impact.impacted ? `<span class="tag bad">Impacted</span>` : `<span class="tag ok">Not impacted</span>`;
                const rejected = impact.rejected ? `<span class="tag">Rejected</span>` : "";
                return `
                    <div class="tag-stack">
                        ${verdict}
                        ${rejected}
                    </div>
                `;
            },
        },
        { label: "Reason", long: true, cell: impact => escapeHtml(impact.reason ?? "") },
    ],
    fields: impact => {
        // once made, an impact is only re-judged, so what it is about is shown but not editable
        const newsLine = impact
            ? `
                <dt>News</dt>
                <dd>${escapeHtml(newsTitleOf(impact.newsTitle))}</dd>
            `
            : "";
        const about = `
            <dl class="span-all">
                ${newsLine}
                <dt>On</dt>
                <dd>${escapeHtml(impactTarget(impact ?? place))}</dd>
            </dl>
        `;

        if (impact) {
            return `
                ${about}
                ${createCheckbox("impacted", "Impacted", impact.impacted)}
                ${createCheckbox("rejected", "Rejected by the traveller", impact.rejected)}
                ${createTextarea("reason", "Reason", impact.reason, 500)}
            `;
        }

        const newsChoices = lookups.news.map(article => ({ value: article.id, label: `#${article.id} ${newsTitleOf(article.title)}` }));
        return `
            ${about}
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
    readForm: (inputs, impact) => {
        const verdict = {
            impacted: inputs.impacted.checked,
            reason: textOrNull(inputs.reason),
            rejected: inputs.rejected.checked,
        };
        if (impact) return verdict;

        return {
            ...verdict,
            itineraryId: place.itineraryId,
            activityId: place.activityId,
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

// ---------- the opened itinerary: whole trip impacts, then each activity with its impacts ----------

const createTripCard = () => `
    <section class="card admin-data" id="admin-trip" hidden>
        <div class="row">
            <h2 data-title></h2>
            <div class="heading-actions">
                <button class="ghost small" type="button" data-add-activity>Add activity</button>
                <button class="ghost small" type="button" data-close-trip>Close</button>
            </div>
        </div>
        <p class="err" data-section-error role="alert"></p>
        <div data-body></div>
    </section>
`;

function mountTripDetail(card, { userId, lookups, view, getState, onChange, onAuthLost, onClose }) {
    const title = card.querySelector("[data-title]");
    const body = card.querySelector("[data-body]");
    const sectionError = card.querySelector("[data-section-error]");
    const activities = activityConfig(view);

    // the one form open in this card, or null. { key, recordId, activityId }: recordId is null when
    // adding, and activityId says which group a new impact goes in (null for the whole trip)
    let openForm = null;

    const selectedTrip = () => getState().itineraries.find(itinerary => itinerary.id === view.selectedItineraryId);
    const activitiesOf = trip => getState().activitiesByItinerary.get(trip.id) ?? [];

    const impactsOn = (trip, activityId) => getState().impacts.filter(impact => {
        const onThisTrip = impact.itineraryId === trip.id;
        const onThisActivity = (impact.activityId ?? null) === activityId;
        return onThisTrip && onThisActivity;
    });

    const placeFor = (trip, activity) => ({
        itineraryId: trip.id,
        itineraryName: trip.name,
        activityId: activity?.id ?? null,
        activityName: activity?.name ?? null,
    });

    // forms open inside the group they belong to: "new" for a new activity, "trip" for the whole
    // trip impacts, or an activity's id
    const groupOf = activityId => activityId == null ? "trip" : String(activityId);
    const slotOf = groupKey => body.querySelector(`[data-group="${groupKey}"] > [data-form-slot]`);

    const createImpactList = (trip, activity) => {
        const impacts = impactsOn(trip, activity?.id ?? null);
        const emptyText = activity ? "No impacts on this activity." : "No impacts on the whole trip.";
        const list = impacts.length
            ? createTable(impactConfig(userId, lookups, placeFor(trip, activity)), impacts)
            : `<p class="meta">${emptyText}</p>`;

        return `
            <div class="trip-impacts">
                ${list}
            </div>
        `;
    };

    const createWholeTripGroup = trip => `
        <div class="trip-group" data-group="trip">
            <div class="row">
                <div>
                    <h3>Whole trip</h3>
                    <p class="meta">Impacts on the trip itself rather than one activity.</p>
                </div>
                <div class="heading-actions">
                    <button class="ghost small" type="button" data-add-impact="">Add impact</button>
                </div>
            </div>
            <div data-form-slot hidden></div>
            ${createImpactList(trip, null)}
        </div>
    `;

    const createActivityGroup = (trip, activity) => `
        <div class="trip-group" data-group="${Number(activity.id)}">
            <div class="row">
                <div>
                    <h3>${escapeHtml(activity.name)} <span class="meta">#${Number(activity.id)}</span></h3>
                    <p class="meta">${activityDetails(activity) || "No time or place set."}</p>
                </div>
                <div class="heading-actions">
                    <button class="ghost small" type="button" data-add-impact="${Number(activity.id)}">Add impact</button>
                    ${createRecordButtons(activities, activity)}
                </div>
            </div>
            <div data-form-slot hidden></div>
            ${createImpactList(trip, activity)}
        </div>
    `;

    // puts the open form back after a redraw, in the group it belongs to. keptForm is the form from
    // before the redraw, moved over as it is so what was typed into it survives
    const showOpenForm = (trip, keptForm) => {
        if (!openForm) return;

        let config;
        let record = null;
        let groupKey;

        if (openForm.key === "activities") {
            config = activities;
            if (openForm.recordId != null) {
                record = activitiesOf(trip).find(activity => activity.id === openForm.recordId);
            }
            groupKey = openForm.recordId == null ? "new" : String(openForm.recordId);
        } else {
            if (openForm.recordId != null) {
                record = getState().impacts.find(impact => impact.id === openForm.recordId);
            }
            const activityId = record ? (record.activityId ?? null) : openForm.activityId;
            const activity = activitiesOf(trip).find(candidate => candidate.id === activityId) ?? null;
            config = impactConfig(userId, lookups, placeFor(trip, activity));
            groupKey = groupOf(activityId);
        }

        // what the form was for is gone, eg deleted from another tab
        const slot = slotOf(groupKey);
        const recordIsGone = openForm.recordId != null && !record;
        if (recordIsGone || !slot) {
            openForm = null;
            return;
        }

        slot.hidden = false;
        if (keptForm) {
            slot.append(keptForm);
            return;
        }

        // the form may have moved to a newer slot by the time it is closed, so this redraws
        // instead of emptying the slot it started in
        const closeThisForm = () => {
            openForm = null;
            draw();
        };

        openRecordForm(slot, config, record, {
            state: getState(),
            onSaved: async () => {
                openForm = null;
                await onChange();
            },
            onCancel: closeThisForm,
            onAuthLost,
        });
    };

    // freshForm is true when a form has just been opened, so the one from before is not carried over
    const draw = ({ freshForm = false } = {}) => {
        const trip = selectedTrip();
        card.hidden = trip == null;
        if (!trip) {
            openForm = null;
            return;
        }

        // taken out before the body is replaced, so the typing in it is not thrown away
        const keepForm = openForm != null && !freshForm;
        const keptForm = keepForm ? body.querySelector("[data-form-slot] > form") : null;
        keptForm?.remove();

        title.textContent = trip.name;

        const itsActivities = activitiesOf(trip);
        const activityGroups = itsActivities.length
            ? itsActivities.map(activity => createActivityGroup(trip, activity)).join("")
            : `<p class="meta">No activities yet.</p>`;

        body.innerHTML = `
            <div data-group="new">
                <div data-form-slot hidden></div>
            </div>
            ${createWholeTripGroup(trip)}
            <h3 class="trip-heading">Activities</h3>
            ${activityGroups}
        `;

        const deleteHooks = { errorElement: sectionError, onChange, onAuthLost };
        wireDeleteButtons(body, activities, deleteHooks);
        wireDeleteButtons(body, impactConfig(userId, lookups, null), deleteHooks);

        showOpenForm(trip, keptForm);
    };

    // one form at a time, so opening another one closes whatever was open
    const open = form => {
        openForm = form;
        sectionError.textContent = "";
        draw({ freshForm: true });
    };

    card.querySelector("[data-add-activity]").addEventListener("click", () => {
        open({ key: "activities", recordId: null });
    });
    card.querySelector("[data-close-trip]").addEventListener("click", onClose);

    body.addEventListener("click", event => {
        const addImpactButton = event.target.closest("[data-add-impact]");
        if (addImpactButton) {
            const value = addImpactButton.dataset.addImpact;
            const activityId = value === "" ? null : Number(value);
            open({ key: "impacts", recordId: null, activityId });
            return;
        }

        const editButton = event.target.closest("[data-edit]");
        if (editButton) {
            open({ key: editButton.dataset.kind, recordId: Number(editButton.dataset.edit) });
        }
    });

    // for switching to another itinerary, so a half filled form does not follow it there
    const reset = () => {
        openForm = null;
        sectionError.textContent = "";
    };

    return { draw, reset };
}

// ---------- loading ----------

async function loadState(userId) {
    // every activity up front in one request, so the summaries can count them without opening each trip
    const [itineraries, activities, impacts, recommendations] = await Promise.all([
        api.adminItineraries(userId),
        api.adminUserActivities(userId),
        api.adminImpacts(userId),
        api.adminRecommendations(userId),
    ]);

    // they come back earliest first, and stay in that order within each trip
    const activitiesByItinerary = new Map();
    for (const activity of activities) {
        const onTrip = activitiesByItinerary.get(activity.itineraryId) ?? [];
        onTrip.push(activity);
        activitiesByItinerary.set(activity.itineraryId, onTrip);
    }

    return { itineraries, activitiesByItinerary, impacts, recommendations };
}

// draws the console into container. onChange runs after every saved change, eg to refresh the audit list
export async function mountUserData(container, userId, onAuthLost, { onChange = () => { } } = {}) {
    container.innerHTML = `
        <h2 class="form-section">Trip data</h2>
        <p class="meta hint">
            Everything this account owns, for testing. Open an itinerary to see its activities, with the
            impacts on each one under it. Changes are recorded in the activity below, and the traveller
            gets an alert saying what an admin changed. New itineraries and activities are checked against
            recent news, like a traveller's, and edits work the same as a traveller's too. Changing a trip's
            name or dates clears its whole trip impacts, changing its country clears every impact on it, and
            changing an activity's name, address or start clears the impacts on that activity, unless you
            tick "Keep its impacts". Each is checked again.
            Each cleared impact, with its verdict and reason, is kept in the activity below.
        </p>
        ${createSectionCard("itineraries", "Itineraries", "Add itinerary")}
        ${createTripCard()}
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

    const itinerariesCard = container.querySelector("#admin-itineraries");
    const tripCard = container.querySelector("#admin-trip");

    const drawAll = () => {
        // the opened itinerary may have just been deleted
        const stillThere = state.itineraries.some(itinerary => itinerary.id === view.selectedItineraryId);
        if (!stillThere) view.selectedItineraryId = null;

        itinerarySection.draw();
        tripDetail.draw();
        recommendationSection.draw();
    };

    // only for the first load. once something is on screen a failed refresh just keeps it there
    const showLoadFailed = error => {
        const lists = container.querySelectorAll("#admin-itineraries [data-list], #admin-recommendations [data-list]");
        for (const list of lists) {
            list.innerHTML = `
                <div class="msg err">
                    Could not load this account's data: ${escapeHtml(error.message)}
                    <button class="ghost small" type="button" data-retry>Try again</button>
                </div>
            `;
        }
    };

    const refresh = async () => {
        try {
            state = await loadState(userId);
        } catch (error) {
            if (error.status === 401) return onAuthLost();
            if (state == null) {
                showLoadFailed(error);
            } else {
                showToast(error.message);
            }
            return;
        }
        drawAll();
    };

    container.addEventListener("click", event => {
        if (event.target.closest("[data-retry]")) refresh();
    });

    const afterChange = async () => {
        await refresh();
        onChange();
    };

    const showTrip = itineraryId => {
        view.selectedItineraryId = itineraryId;
        tripDetail.reset();
        drawAll();
    };

    const hooks = { getState, onChange: afterChange, onAuthLost };
    const itinerarySection = mountSection(itinerariesCard, itineraryConfig(userId, lookups, view, getState), hooks);
    const tripDetail = mountTripDetail(tripCard, {
        userId, lookups, view, getState, onChange: afterChange, onAuthLost,
        onClose: () => showTrip(null),
    });
    const recommendationSection = mountSection(container.querySelector("#admin-recommendations"), recommendationConfig(), hooks);

    // open swaps the trip card over to that itinerary, and pressing it again closes it
    itinerariesCard.addEventListener("click", event => {
        const button = event.target.closest("[data-open-itinerary]");
        if (!button) return;

        const itineraryId = Number(button.dataset.openItinerary);
        const isAlreadyOpen = view.selectedItineraryId === itineraryId;
        showTrip(isAlreadyOpen ? null : itineraryId);

        if (!isAlreadyOpen) tripCard.scrollIntoView({ behavior: "smooth", block: "nearest" });
    });

    await refresh();
}
