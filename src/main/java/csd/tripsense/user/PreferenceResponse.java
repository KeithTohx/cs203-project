package csd.tripsense.user;

import java.time.Instant;
import java.util.Set;

import csd.tripsense.user.preference.*;

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
}