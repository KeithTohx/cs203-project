package csd.tripsense.chat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import csd.tripsense.activity.Activity;
import csd.tripsense.impact.Impact;
import csd.tripsense.news.News;

// unit tests for ChatImpactView.from, which shapes one impact for the chatbot prompt.
// the news summary is untrusted text from the web, so it is capped at 400 characters
@DisplayName("ChatImpactView Unit Tests")
class ChatImpactViewTest {

    private static Impact impactWithSummary(String summary, Activity activity) {
        News news = new News();
        news.setTitle("Earthquake near Hokkaido");
        news.setDescription(summary);

        Impact impact = new Impact();
        impact.setNews(news);
        impact.setActivity(activity);
        return impact;
    }

    @Test
    @DisplayName("Should keep a summary of exactly 400 characters as it is")
    void from_ShouldKeepSummary_WhenExactly400Characters() {
        // Arrange
        String longestKept = "a".repeat(400);

        // Act
        ChatImpactView view = ChatImpactView.from(impactWithSummary(longestKept, null));

        // Assert
        assertEquals(longestKept, view.newsSummary());
    }

    @Test
    @DisplayName("Should cut a summary of 401 characters to 400 and mark it as cut")
    void from_ShouldCutSummary_WhenOver400Characters() {
        // Arrange
        String oneTooLong = "a".repeat(401);

        // Act
        ChatImpactView view = ChatImpactView.from(impactWithSummary(oneTooLong, null));

        // Assert
        assertEquals("a".repeat(400) + "…", view.newsSummary());
    }

    @Test
    @DisplayName("Should say the whole itinerary is affected when the impact has no activity")
    void from_ShouldNameWholeItinerary_WhenImpactHasNoActivity() {
        // Act
        ChatImpactView view = ChatImpactView.from(impactWithSummary(null, null));

        // Assert
        assertEquals("Whole itinerary", view.affectedActivity());
        assertNull(view.newsSummary());
    }
}
