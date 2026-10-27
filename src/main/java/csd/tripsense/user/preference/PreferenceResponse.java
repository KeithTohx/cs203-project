package csd.tripsense.user.preference;

import java.time.Instant;

public record PreferenceResponse(
        RiskTolerance riskTolerance,
        FlexibilityLevel flexibilityLevel,
        ExplanationDetail explanationDetail,
        TravelGroup travelGroup,
        boolean travellingWithChildren,
        boolean travellingWithElderly,
        Instant updatedAt
) {
    public static PreferenceResponse from(UserPreference p) {
        return new PreferenceResponse(
                p.getRiskTolerance(), p.getFlexibilityLevel(), p.getExplanationDetail(), 
                p.getTravelGroup(), p.isTravellingWithChildren(), p.isTravellingWithElderly(),
                p.getUpdatedAt());
    }

    /**
     * Turns the user's saved preferences into short block of plain instructions that gets inserted into the chatbot’s system prompt.
     * Fixed text chosen by enum, so no user-written text ever reaches the prompt.
     * User's preferences only change emphasis, not facts.
     */
    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder("Traveller Preferences (adjust emphasis and advice, never the facts):\n");

        sb.append("- Risk attitude: ").append(switch (riskTolerance) {
            case LOW -> "risk-averse. Treat borderline or uncertain events as relevant and say they are worth monitoring.";
            case MEDIUM -> "balanced. Flag events that plausibly affect the trip.";
            case HIGH -> "relaxed. Only escalate events that would materially change plans, "
                    + "but never downplay danger to life or safety.";
        }).append('\n');

        sb.append("- Flexibility: ").append(switch (flexibilityLevel) {
            case FIXED_DATES -> "dates cannot move. Suggest mitigations (other routes, transport, bookings), "
                    + "not rescheduling.";
            case SOMEWHAT_FLEXIBLE -> "dates can shift by a day or two. Rescheduling may be mentioned as an option.";
            case VERY_FLEXIBLE -> "rescheduling or changing plans is acceptable and may be recommended.";
        }).append('\n');

        sb.append("- Explanation style: ").append(switch (explanationDetail) {
            case BRIEF -> "brief. Reply in at most three sentences, conclusion first, with no lists."
                    + "State whether the trip is affected, name the affected activity, and say what to do.";
            case DETAILED -> "detailed. Explain what happened, which itinerary items it affects, and what to do.";
        }).append('\n');

        if (travelGroup == TravelGroup.FAMILY) {
            sb.append("- Travelling as a family.\n");
        }
        if (travellingWithChildren) {
            sb.append("- Children are travelling. Weigh long delays, overnight stranding, "
                    + "and loss of food or rest facilities more heavily.\n");
        }
        if (travellingWithElderly) {
            sb.append("- Elderly travellers are in the group. Weigh walking distances, long waits, "
                    + "extreme weather, and access to medical care more heavily.\n");
        }

        sb.append("Rules: do not claim an event is irrelevant because of these preferences if it affects the itinerary. ")
        .append("Do not invent details about the traveller beyond what is listed here.");
        return sb.toString();
    }

}