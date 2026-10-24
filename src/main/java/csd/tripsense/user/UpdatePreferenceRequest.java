package csd.tripsense.user;

import jakarta.validation.constraints.Pattern;

import java.util.Set;

import csd.tripsense.user.preference.*;

public record UpdatePreferenceRequest(
        RiskTolerance riskTolerance,
        FlexibilityLevel flexibilityLevel,
        ExplanationDetail explanationDetail,
        TravelGroup travelGroup,
        Boolean travellingWithChildren,
        Boolean travellingWithElderly
) {}