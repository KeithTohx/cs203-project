// The choices for each travel preference, in the order they are shown.
// value must match the backend enum, label is what the user reads. to add an option
// (e.g. a new travel group), add one line here and it shows up on the form and the summary
export const PREFERENCE_OPTIONS = {
    riskTolerance: [
        { value: "LOW", label: "Cautious: tell me about anything that might affect my trip" },
        { value: "MEDIUM", label: "Balanced: tell me about things likely to affect my trip" },
        { value: "HIGH", label: "Relaxed: only tell me about things that would change my plans" },
    ],
    flexibilityLevel: [
        { value: "FIXED_DATES", label: "My dates are fixed" },
        { value: "SOMEWHAT_FLEXIBLE", label: "My dates can shift by a day or two" },
        { value: "VERY_FLEXIBLE", label: "I am happy to change my plans" },
    ],
    explanationDetail: [
        { value: "BRIEF", label: "Brief: a few sentences" },
        { value: "DETAILED", label: "Detailed: the full picture" },
    ],
    travelGroup: [
        { value: "SOLO", label: "On my own" },
        { value: "FAMILY", label: "With family" },
    ],
};

// the text for a saved value, or the raw value if the backend sends one this file does not know yet
export const labelFor = (field, value) => {
    const option = PREFERENCE_OPTIONS[field].find(candidate => candidate.value === value);
    return option ? option.label : value;
};
