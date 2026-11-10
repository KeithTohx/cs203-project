// Create/edit screen for an itinerary and its activities, all saved with one Save. On success: show a message back on #/itineraries.
// The backend's PUT replaces the whole record, so related records (user, country, itinerary) are sent back as {id}.
import { api } from "../api.js";
import { confirmTwice, escapeHtml, flash, formPage, loading, showError, sortByStart } from "../util.js";
import { icon } from "../icons.js";

const ITINERARIES_LINK = "#/itineraries";
const showLoading = root => { root.innerHTML = loading(); };
const showNotFound = root => {
    root.innerHTML = `<div class="msg">That itinerary wasn't found. <a href="${ITINERARIES_LINK}">Back to itineraries</a></div>`;
};
// The user may only edit their own itineraries (when we know who they are).
const ownsItinerary = (user, itinerary) => itinerary && (user.id == null || itinerary.userId === user.id);

// "2026-12-16T09:00:00" -> input values, and back. Dates are LocalDateTime on the backend.
const toDateInput = dateTime => dateTime ? String(dateTime).slice(0, 10) : "";
const toDateTimeInput = dateTime => dateTime ? String(dateTime).slice(0, 16) : "";
const fromDateTimeInput = inputValue => inputValue ? inputValue + ":00" : null;

// new activities last this long unless the user picks an end
const DEFAULT_ACTIVITY_MINUTES = 60;
const DEFAULT_START_TIME = "09:00";

// "2026-12-16T09:00" moved by some minutes, as another datetime-local value (local time)
function addMinutes(dateTimeValue, minutes) {
    const date = new Date(dateTimeValue);
    date.setMinutes(date.getMinutes() + minutes);

    const pad = number => String(number).padStart(2, "0");
    const day = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
    const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
    return `${day}T${time}`;
}

function minutesBetween(start, end) {
    const milliseconds = new Date(end) - new Date(start);
    return Math.round(milliseconds / 60000);
}

// these values compare correctly as plain strings; an empty one means "no limit"
const laterOf = (first, second) => (!first ? second : !second ? first : first > second ? first : second);
const earlierOf = (first, second) => (!first ? second : !second ? first : first < second ? first : second);

function checkEndNotBeforeStart(start, end, what = "The end") {
    if (start && end && end < start) throw new Error(`${what} can't be before the start.`);
}

// Component: one editable activity on the timeline. Fields look like text until hovered or focused.
// `activity` is null for a new one; `key` keeps input ids unique.
// the same list as ActivityCategory on the server. an activity with no kind gets no
// alternatives suggested, so the form asks for one
const CATEGORIES = [
    ["SKI_SNOW", "Ski or snow"],
    ["BEACH_WATER", "Beach or water"],
    ["OUTDOOR_NATURE", "Outdoors or nature"],
    ["SIGHTSEEING", "Sightseeing"],
    ["MUSEUM_GALLERY", "Museum or gallery"],
    ["FOOD_DINING", "Food or dining"],
    ["SHOPPING", "Shopping"],
    ["WELLNESS_SPA", "Wellness or spa"],
];

const createCategorySelect = (activity, key) => `
    <label class="sr-only" for="activity-${key}-category">What kind of activity</label>
    <select id="activity-${key}-category" data-field="category" class="inline-edit category" required>
        <option value="">What kind of activity?</option>
        ${CATEGORIES.map(([value, label]) =>
            `<option value="${value}"${activity?.category === value ? " selected" : ""}>${label}</option>`).join("")}
    </select>`;

const createActivityRow = (activity, key) => {
    const field = (name, label, attributes) => `
        <label class="sr-only" for="activity-${key}-${name}">${label}</label>
        <input id="activity-${key}-${name}" data-field="${name}" class="inline-edit ${name}" ${attributes}>`;
    return `
        <li class="activity-row" data-id="${activity ? Number(activity.id) : ""}">
            <div class="activity-title">
                ${field("name", "Activity name", `value="${escapeHtml(activity?.name)}" placeholder="Activity name" maxlength="100" required`)}
                <button type="button" class="ghost icon-button" data-remove title="Remove activity"
                    aria-label="Remove ${escapeHtml(activity?.name || "new activity")}">×</button>
            </div>
            ${field("address", "Address", `value="${escapeHtml(activity?.address)}" placeholder="Add an address" maxlength="200"`)}
            ${createCategorySelect(activity, key)}
            <div class="inline-range">
                ${field("dateStart", "Starts", `type="datetime-local" value="${toDateTimeInput(activity?.dateStart)}"`)}
                <span aria-hidden="true">to</span>
                ${field("dateEnd", "Ends", `type="datetime-local" value="${toDateTimeInput(activity?.dateEnd)}"`)}
            </div>
        </li>`;
};

// Same screen for creating and editing: `itineraryId` is null when creating.
// `startWithNewActivity` opens the screen with a blank activity row ready to fill in.
export async function showEditItinerary(root, user, onAuthLost, itineraryId, startWithNewActivity = false) {
    showLoading(root);
    const isNew = itineraryId == null;
    let itinerary, countries, activities;
    try {
        [itinerary, countries, activities] = await Promise.all([
            isNew ? { userId: user.id } : api.itinerary(itineraryId),
            api.countries(),
            isNew ? [] : api.itineraryActivities(itineraryId),
        ]);
    } catch (error) { return showError(root, error, onAuthLost); }
    if (!ownsItinerary(user, itinerary)) return showNotFound(root);
    // Set once the itinerary exists, so retrying after a failed activity save doesn't create it twice.
    let savedId = isNew ? null : Number(itinerary.id);

    // the country field is a search box over a datalist, so typing narrows the list down.
    // names are matched ignoring case, then turned back into the id the backend wants
    const countryList = countries ?? [];
    const countryIdsByName = new Map(countryList.map(country => [country.name.toLowerCase(), Number(country.id)]));
    const currentCountry = countryList.find(country => country.id === itinerary.countryId);
    const countryOptions = countryList
        .map(country => `<option value="${escapeHtml(country.name)}"></option>`)
        .join("");
    const countryIdFor = name => countryIdsByName.get(name.trim().toLowerCase());
    const sortedActivities = sortByStart(activities ?? []);
    let nextKey = sortedActivities.length;
    const deletedIds = new Set();

    const { form, errorMessage } = formPage(root, {
        title: isNew ? "New itinerary" : "Edit itinerary", cancelLink: ITINERARIES_LINK, onAuthLost, formClass: "wide itinerary-form",
        extraActions: isNew ? "" : `<button type="button" class="ghost danger" id="delete-button">Delete itinerary</button>`,
        fieldsHtml: `
        <label class="sr-only" for="name">Itinerary name</label>
        <input id="name" name="name" class="inline-edit title" value="${escapeHtml(itinerary.name)}"
            placeholder="Name your trip" maxlength="100" required>
        <div class="itinerary-meta">
            <label class="sr-only" for="country">Country</label>
            ${icon("mapPin")}
            <input id="country" name="country" class="inline-edit country" list="country-options"
                value="${escapeHtml(currentCountry?.name)}" placeholder="Search for a country" autocomplete="off" required>
            <datalist id="country-options">
                ${countryOptions}
            </datalist>
            <span class="inline-range">
                ${icon("calendar")}
                <label class="sr-only" for="dateStart">Start date</label>
                <input id="dateStart" name="dateStart" type="date" class="inline-edit" value="${toDateInput(itinerary.dateStart)}" required>
                <span aria-hidden="true">to</span>
                <label class="sr-only" for="dateEnd">End date</label>
                <input id="dateEnd" name="dateEnd" type="date" class="inline-edit" value="${toDateInput(itinerary.dateEnd)}" required>
            </span>
        </div>
        <h2 class="form-section">Activities</h2>
        <ol class="activity-editor timeline">${sortedActivities.map(createActivityRow).join("")}</ol>
        <p class="meta" id="no-activities">No activities planned yet.</p>
        <button type="button" class="ghost small add" id="add-activity">${icon("plus")}Add activity</button>`,
        onSubmit: async inputs => {
            const countryId = countryIdFor(inputs.country.value);
            if (countryId == null) throw new Error("Pick a country from the list.");
            checkEndNotBeforeStart(inputs.dateStart.value, inputs.dateEnd.value);
            const rows = activityRows().map(row => {
                const value = name => row.querySelector(`[data-field="${name}"]`).value;
                const activityName = value("name").trim();
                checkEndNotBeforeStart(value("dateStart"), value("dateEnd"), `The end of "${activityName}"`);
                return {
                    row, id: row.dataset.id ? Number(row.dataset.id) : null, data: {
                        itinerary: { id: null },   // filled in once the itinerary is saved
                        name: activityName,
                        address: value("address").trim() || null,
                        category: value("category") || null,
                        dateStart: fromDateTimeInput(value("dateStart")),
                        dateEnd: fromDateTimeInput(value("dateEnd")),
                    },
                };
            });

            const itineraryData = {
                user: { id: itinerary.userId },
                country: { id: countryId },
                name: inputs.name.value.trim(),
                dateStart: inputs.dateStart.value + "T00:00:00",
                dateEnd: inputs.dateEnd.value + "T00:00:00",
            };
            if (savedId == null) {
                const created = await api.addItinerary(itineraryData);
                if (created?.id == null && rows.length) throw new Error("The itinerary was saved, but its activities couldn't be added.");
                savedId = created?.id == null ? null : Number(created.id);
            } else {
                await api.updateItinerary(savedId, itineraryData);
            }
            for (const { data } of rows) data.itinerary.id = savedId;
            // Each finished change is recorded on the page, so retrying after an error doesn't repeat it.
            await Promise.all([...deletedIds].map(async id => {
                await api.deleteActivity(id);
                deletedIds.delete(id);
            }));
            await Promise.all(rows.map(async ({ row, id, data }) => {
                if (id == null) {
                    const created = await api.addActivity(data);
                    if (created?.id != null) row.dataset.id = Number(created.id);
                } else {
                    await api.updateActivity(id, data);
                }
            }));
            flash(isNew ? "Itinerary created." : "Itinerary saved.");
            location.hash = "/itineraries";
        },
    });

    const activityList = form.querySelector(".activity-editor");
    const emptyMessage = form.querySelector("#no-activities");
    const activityRows = () => [...activityList.querySelectorAll(".activity-row")];

    // the first and last moment an activity can happen, from the itinerary's dates
    const tripStart = () => form.elements.dateStart.value ? form.elements.dateStart.value + "T00:00" : "";
    const tripEnd = () => form.elements.dateEnd.value ? form.elements.dateEnd.value + "T23:59" : "";
    const dateInputs = row => ({
        startInput: row.querySelector('[data-field="dateStart"]'),
        endInput: row.querySelector('[data-field="dateEnd"]'),
    });

    // keep activity times inside the itinerary's dates, and each end no earlier than its start.
    // the pickers then open on an allowed day, and greyed-out days can't be picked
    const applyAllowedRange = () => {
        form.elements.dateEnd.min = form.elements.dateStart.value;

        for (const row of activityRows()) {
            const { startInput, endInput } = dateInputs(row);
            startInput.min = tripStart();
            startInput.max = tripEnd();
            endInput.min = laterOf(tripStart(), startInput.value);
            endInput.max = tripEnd();
        }
    };

    // keep a suggested time inside the trip's dates
    const clampToTrip = dateTime => earlierOf(laterOf(dateTime, tripStart()), tripEnd());

    // where an activity with no start should begin: when the latest other activity ends,
    // or at 9am on the trip's first day. empty when there are no trip dates to go on
    const suggestStart = row => {
        const otherEnds = activityRows()
            .filter(otherRow => otherRow !== row)
            .map(otherRow => dateInputs(otherRow).endInput.value)
            .filter(Boolean);
        const latestEnd = otherEnds.reduce(laterOf, "");
        const firstMorning = form.elements.dateStart.value ? `${form.elements.dateStart.value}T${DEFAULT_START_TIME}` : "";

        const suggestion = latestEnd || firstMorning;
        return suggestion ? clampToTrip(suggestion) : "";
    };

    // an empty start or end is filled in from whatever the row already has
    const suggestFor = (row, field) => {
        const { startInput, endInput } = dateInputs(row);

        if (field === "dateStart") {
            if (endInput.value) return clampToTrip(addMinutes(endInput.value, -DEFAULT_ACTIVITY_MINUTES));
            return suggestStart(row);
        }

        const start = startInput.value || suggestStart(row);
        return start ? clampToTrip(addMinutes(start, DEFAULT_ACTIVITY_MINUTES)) : "";
    };

    // moving an activity's start moves its end by the same amount, like a calendar app.
    // with no end yet, the end is filled in as start + the default length
    const wireDates = row => {
        const { startInput, endInput } = dateInputs(row);
        let previousStart = startInput.value;

        startInput.addEventListener("change", () => {
            const newStart = startInput.value;
            if (newStart) {
                const hadValidEnd = previousStart && endInput.value && endInput.value >= previousStart;
                const duration = hadValidEnd ? minutesBetween(previousStart, endInput.value) : DEFAULT_ACTIVITY_MINUTES;
                const newEnd = addMinutes(newStart, duration);
                endInput.value = earlierOf(newEnd, tripEnd());
            }
            previousStart = newStart;
            applyAllowedRange();
        });

        // the browser's picker only highlights a date on an empty field without filling it in,
        // so picking just a time leaves the field half done ("please enter a valid value").
        // filling in a suggestion as soon as the field is clicked means the picker opens on a full value
        for (const input of [startInput, endInput]) {
            input.addEventListener("focus", () => {
                if (input.value) return;

                const suggestion = suggestFor(row, input.dataset.field);
                if (!suggestion) return;

                input.value = suggestion;
                if (input === startInput) previousStart = suggestion;
                applyAllowedRange();
            });
        }
    };

    // a new activity gets a start and end straight away, so nothing needs picking unless it's wrong
    const fillDefaultTimes = row => {
        const { startInput, endInput } = dateInputs(row);
        startInput.value = suggestFor(row, "dateStart");
        endInput.value = suggestFor(row, "dateEnd");
    };
    const refresh = () => {
        emptyMessage.hidden = activityRows().length > 0;
        applyAllowedRange();
    };
    // flag a name that is not in the list straight away, rather than only on save
    const countryInput = form.elements.country;
    countryInput.addEventListener("input", () => {
        const typed = countryInput.value.trim();
        const isKnown = !typed || countryIdFor(typed) != null;
        countryInput.setCustomValidity(isKnown ? "" : "Pick a country from the list.");
    });
    countryInput.addEventListener("change", () => {
        // tidy the casing to match the list, eg "japan" -> "Japan"
        const id = countryIdFor(countryInput.value);
        const match = countryList.find(country => Number(country.id) === id);
        if (match) countryInput.value = match.name;
    });

    form.elements.dateStart.addEventListener("change", applyAllowedRange);
    form.elements.dateEnd.addEventListener("change", applyAllowedRange);

    const addActivity = () => {
        activityList.insertAdjacentHTML("beforeend", createActivityRow(null, nextKey++));
        const newRow = activityList.lastElementChild;
        fillDefaultTimes(newRow);
        wireRow(newRow);
        wireDates(newRow);
        refresh();
        activityList.lastElementChild.querySelector("input").focus();
    };
    form.querySelector("#add-activity").onclick = addActivity;

    // Removing needs a second click (see confirmTwice). The row is tinted while it waits.
    // Removing only takes effect when the form is saved.
    const wireRow = row => confirmTwice(row.querySelector("[data-remove]"), {
        confirmText: "Remove?",
        onArm: () => row.classList.add("removing"),
        onDisarm: () => row.classList.remove("removing"),
        onConfirm: () => {
            if (row.dataset.id) deletedIds.add(Number(row.dataset.id));
            row.remove();
            refresh();
        },
    });
    activityRows().forEach(wireRow);
    activityRows().forEach(wireDates);

    // Delete needs a second click, like removing an activity. The backend removes the itinerary's activities with it.
    const deleteButton = form.querySelector("#delete-button");
    if (deleteButton) confirmTwice(deleteButton, { confirmText: "Delete itinerary?", onConfirm: async () => {
        deleteButton.disabled = true;
        errorMessage.textContent = "";
        try {
            await api.deleteItinerary(savedId);
            flash("Itinerary deleted.");
            location.hash = "/itineraries";
        } catch (error) {
            deleteButton.disabled = false;
            error.status === 401 ? onAuthLost() : (errorMessage.textContent = error.message);
        }
    } });

    refresh();
    if (startWithNewActivity) addActivity();
}
