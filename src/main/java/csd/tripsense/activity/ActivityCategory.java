package csd.tripsense.activity;

/**
 * What kind of thing an activity is, used to pick sensible alternatives when one is
 * disrupted. See RecommendationRules.
 *
 * Indoor is a property of the category rather than a separate lookup, because it never
 * varies between two museums. It is the main lever: an outdoor plan hit by weather wants
 * an indoor replacement.
 */
public enum ActivityCategory {

    SKI_SNOW(false),
    BEACH_WATER(false),
    OUTDOOR_NATURE(false),
    SIGHTSEEING(false),

    MUSEUM_GALLERY(true),
    FOOD_DINING(true),
    SHOPPING(true),
    WELLNESS_SPA(true);

    private final boolean indoor;

    ActivityCategory(boolean indoor) {
        this.indoor = indoor;
    }

    public boolean isIndoor() {
        return indoor;
    }
}
