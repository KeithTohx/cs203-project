package csd.tripsense.user.preference;

import java.util.Set;

import csd.tripsense.activity.ActivityCategory;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

public record UpdatePreferenceRequest(
        RiskTolerance riskTolerance,
        FlexibilityLevel flexibilityLevel,
        ExplanationDetail explanationDetail,
        TravelGroup travelGroup,
        Boolean travellingWithChildren,
        Boolean travellingWithElderly,
        @ArraySchema(
                arraySchema = @Schema(description = "Kinds of activity to suggest when something is disrupted. "
                        + "Indoor categories only. Send [] to clear, omit to leave unchanged."),
                schema = @Schema(allowableValues = { "MUSEUM_GALLERY", "FOOD_DINING", "SHOPPING", "WELLNESS_SPA" }))
        Set<ActivityCategory> preferredCategories
) {}
