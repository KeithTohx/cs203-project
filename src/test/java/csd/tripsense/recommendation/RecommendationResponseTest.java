package csd.tripsense.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityCategory;
import csd.tripsense.impact.Impact;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.news.News;

// what the api returns. a plain mapping, so no spring and no database
class RecommendationResponseTest {

    private final ActivityOption option = new ActivityOption(56759840L, "Thailand", "Thanyaburi",
            ActivityCategory.FOOD_DINING, "Chai Thung Coffee", "Thanyaburi, Thailand", 14.0, 100.7);

    // an impact with everything attached: itinerary, activity and news
    private Impact fullImpact() {
        Itinerary itinerary = new Itinerary();
        itinerary.setId(2L);

        Activity activity = mock(Activity.class);
        when(activity.getId()).thenReturn(4L);
        when(activity.getName()).thenReturn("Grand Palace visit");

        News news = mock(News.class);
        when(news.getId()).thenReturn(2L);
        when(news.getTitle()).thenReturn("Flooding closes Bangkok tourist areas");

        Impact impact = mock(Impact.class);
        when(impact.getId()).thenReturn(4L);
        when(impact.getReason()).thenReturn("The Grand Palace is in central Bangkok.");
        when(impact.getItinerary()).thenReturn(itinerary);
        when(impact.getActivity()).thenReturn(activity);
        when(impact.getNews()).thenReturn(news);
        return impact;
    }

    private Recommendation recommendation(Impact impact, ActivityOption suggested) {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(5L);
        recommendation.setImpact(impact);
        recommendation.setSeverity(Severity.MEDIUM);
        recommendation.setAction(Action.REPLACE);
        recommendation.setAdvice("Visit Chai Thung Coffee instead");
        recommendation.setSuggestedOption(suggested);
        return recommendation;
    }

    @Test
    void mapsEveryFieldIncludingTheKindOfPlaceSuggested() {
        RecommendationResponse response = RecommendationResponse.from(recommendation(fullImpact(), option));

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(response.action()).isEqualTo(Action.REPLACE);
        assertThat(response.advice()).isEqualTo("Visit Chai Thung Coffee instead");
        assertThat(response.impactId()).isEqualTo(4L);
        assertThat(response.reason()).isEqualTo("The Grand Palace is in central Bangkok.");
        assertThat(response.itineraryId()).isEqualTo(2L);
        assertThat(response.activityId()).isEqualTo(4L);
        assertThat(response.activityName()).isEqualTo("Grand Palace visit");
        assertThat(response.newsId()).isEqualTo(2L);
        assertThat(response.newsTitle()).isEqualTo("Flooding closes Bangkok tourist areas");
        assertThat(response.optionId()).isEqualTo(56759840L);
        assertThat(response.optionName()).isEqualTo("Chai Thung Coffee");
        // the category is what lets a client (or a tester) see whether the suggestion matches the
        // traveller's preferred kind without running sql
        assertThat(response.optionCategory()).isEqualTo(ActivityCategory.FOOD_DINING);
        assertThat(response.optionArea()).isEqualTo("Thanyaburi");
        assertThat(response.optionAddress()).isEqualTo("Thanyaburi, Thailand");
    }

    @Test
    void theOptionFieldsAreNullWhenNothingWasOffered() {
        RecommendationResponse response = RecommendationResponse.from(recommendation(fullImpact(), null));

        assertThat(response.optionId()).isNull();
        assertThat(response.optionName()).isNull();
        assertThat(response.optionCategory()).isNull();
        assertThat(response.optionArea()).isNull();
        assertThat(response.optionAddress()).isNull();
        assertThat(response.advice()).isEqualTo("Visit Chai Thung Coffee instead");   // the rest is untouched
    }

    @Test
    void aWholeTripImpactHasNoActivityFields() {
        Impact wholeTrip = mock(Impact.class);
        when(wholeTrip.getId()).thenReturn(7L);
        when(wholeTrip.getActivity()).thenReturn(null);

        RecommendationResponse response = RecommendationResponse.from(recommendation(wholeTrip, null));

        assertThat(response.impactId()).isEqualTo(7L);
        assertThat(response.activityId()).isNull();
        assertThat(response.activityName()).isNull();
        // no itinerary or news attached either, and that must not throw
        assertThat(response.itineraryId()).isNull();
        assertThat(response.newsId()).isNull();
        assertThat(response.newsTitle()).isNull();
    }

    @Test
    void aRecommendationWithNoImpactStillMaps() {
        RecommendationResponse response = RecommendationResponse.from(recommendation(null, option));

        assertThat(response.impactId()).isNull();
        assertThat(response.reason()).isNull();
        assertThat(response.itineraryId()).isNull();
        assertThat(response.optionName()).isEqualTo("Chai Thung Coffee");
    }

    @Test
    void carriesTheStaleFlagAndThePreferencesVersionSoTheClientCanTellWhichAdviceNeedsRedoing() {
        Recommendation recommendation = recommendation(fullImpact(), option);
        recommendation.setStale(true);
        recommendation.setPreferencesVersion(Instant.parse("2026-11-01T10:00:00Z"));

        RecommendationResponse response = RecommendationResponse.from(recommendation);

        assertThat(response.stale()).isTrue();
        assertThat(response.preferencesVersion()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
    }

    @Test
    void aRecommendationIsNotStaleAndHasNoVersionUntilTheServiceSaysOtherwise() {
        RecommendationResponse response = RecommendationResponse.from(recommendation(fullImpact(), option));

        // the flag is never saved, so a recommendation the service has not marked is not stale
        assertThat(response.stale()).isFalse();
        // and a row made before versions were kept has none
        assertThat(response.preferencesVersion()).isNull();
    }
}
