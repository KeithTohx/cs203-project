package csd.tripsense.user.preference;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// unit tests for PreferenceResponse.toPromptBlock, the preferences part of the chatbot's system prompt.
// the text is fixed per setting, so these check that each setting picks its own instruction and that
// the rules about never changing the facts are always there, not the exact wording
@DisplayName("PreferenceResponse Unit Tests")
class PreferenceResponseTest {

    private static PreferenceResponse preferences(RiskTolerance risk, FlexibilityLevel flexibility,
                                                  ExplanationDetail detail, TravelGroup group,
                                                  boolean children, boolean elderly) {
        return new PreferenceResponse(risk, flexibility, detail, group, children, elderly, null);
    }

    // every value of the three enums appears in at least one row, so each choice is tested once
    @ParameterizedTest(name = "{0}, {1}, {2}")
    @CsvSource({
        "LOW,    FIXED_DATES,       BRIEF,    risk-averse, not rescheduling,    at most three sentences",
        "MEDIUM, SOMEWHAT_FLEXIBLE, DETAILED, balanced,    a day or two,        Explain what happened",
        "HIGH,   VERY_FLEXIBLE,     BRIEF,    relaxed,     may be recommended,  at most three sentences"
    })
    @DisplayName("Should give the instruction that matches each setting")
    void toPromptBlock_ShouldUseTheInstructionForEachSetting(RiskTolerance risk, FlexibilityLevel flexibility,
                                                             ExplanationDetail detail, String expectedRisk,
                                                             String expectedFlexibility, String expectedDetail) {
        // Arrange
        PreferenceResponse response = preferences(risk, flexibility, detail, TravelGroup.SOLO, false, false);

        // Act
        String block = response.toPromptBlock();

        // Assert
        assertTrue(block.contains(expectedRisk), "risk line missing: " + expectedRisk);
        assertTrue(block.contains(expectedFlexibility), "flexibility line missing: " + expectedFlexibility);
        assertTrue(block.contains(expectedDetail), "explanation line missing: " + expectedDetail);
    }

    @Test
    @DisplayName("Should always tell the model that preferences change emphasis, never the facts")
    void toPromptBlock_ShouldAlwaysKeepTheFactsRules() {
        // Arrange - the most relaxed traveller is the one most likely to get a danger downplayed
        PreferenceResponse response = preferences(RiskTolerance.HIGH, FlexibilityLevel.VERY_FLEXIBLE,
                ExplanationDetail.BRIEF, TravelGroup.SOLO, false, false);

        // Act
        String block = response.toPromptBlock();

        // Assert
        assertTrue(block.contains("never the facts"));
        assertTrue(block.contains("never downplay danger to life or safety"));
        assertTrue(block.contains("do not claim an event is irrelevant because of these preferences"));
        assertTrue(block.contains("Do not invent details about the traveller"));
    }

    @Test
    @DisplayName("Should not mention family, children or elderly travellers when none apply")
    void toPromptBlock_ShouldLeaveOutGroupLines_WhenTravellingSoloWithoutChildrenOrElderly() {
        // Arrange
        PreferenceResponse response = preferences(RiskTolerance.MEDIUM, FlexibilityLevel.SOMEWHAT_FLEXIBLE,
                ExplanationDetail.BRIEF, TravelGroup.SOLO, false, false);

        // Act
        String block = response.toPromptBlock();

        // Assert
        assertFalse(block.contains("family"));
        assertFalse(block.contains("Children"));
        assertFalse(block.contains("Elderly"));
    }

    @Test
    @DisplayName("Should mention family, children and elderly travellers when they apply")
    void toPromptBlock_ShouldIncludeGroupLines_WhenFamilyWithChildrenAndElderly() {
        // Arrange
        PreferenceResponse response = preferences(RiskTolerance.MEDIUM, FlexibilityLevel.SOMEWHAT_FLEXIBLE,
                ExplanationDetail.BRIEF, TravelGroup.FAMILY, true, true);

        // Act
        String block = response.toPromptBlock();

        // Assert
        assertTrue(block.contains("Travelling as a family"));
        assertTrue(block.contains("Children are travelling"));
        assertTrue(block.contains("Elderly travellers are in the group"));
    }
}
