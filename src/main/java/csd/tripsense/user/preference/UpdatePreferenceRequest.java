package csd.tripsense.user.preference;

public record UpdatePreferenceRequest(
        RiskTolerance riskTolerance,
        FlexibilityLevel flexibilityLevel,
        ExplanationDetail explanationDetail,
        TravelGroup travelGroup,
        Boolean travellingWithChildren,
        Boolean travellingWithElderly
) {}