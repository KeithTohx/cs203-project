package csd.tripsense.user.preference;

import java.util.Set;

import csd.tripsense.activity.ActivityCategory;

public record UpdatePreferenceRequest(
        RiskTolerance riskTolerance,
        FlexibilityLevel flexibilityLevel,
        ExplanationDetail explanationDetail,
        TravelGroup travelGroup,
        Boolean travellingWithChildren,
        Boolean travellingWithElderly,
        Set<ActivityCategory> preferredCategories
) {}