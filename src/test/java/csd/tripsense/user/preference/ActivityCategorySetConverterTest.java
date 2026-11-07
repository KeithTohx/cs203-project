package csd.tripsense.user.preference;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import csd.tripsense.activity.ActivityCategory;

// the converter that keeps a set of categories in one text column. plain logic, no spring and no database
class ActivityCategorySetConverterTest {

    private final ActivityCategorySetConverter converter = new ActivityCategorySetConverter();

    // ----- set to column -----

    @Test
    void nullAndEmptySetsAreStoredAsAnEmptyString() {
        // the column is not null, and "" is how "no preference" is kept
        assertThat(converter.convertToDatabaseColumn(null)).isEmpty();
        assertThat(converter.convertToDatabaseColumn(Set.of())).isEmpty();
    }

    @Test
    void aSingleCategoryIsStoredByName() {
        assertThat(converter.convertToDatabaseColumn(Set.of(ActivityCategory.SHOPPING)))
                .isEqualTo("SHOPPING");
    }

    @Test
    void severalCategoriesAreStoredSortedAndCommaSeparated() {
        assertThat(converter.convertToDatabaseColumn(
                Set.of(ActivityCategory.SHOPPING, ActivityCategory.FOOD_DINING)))
                .isEqualTo("FOOD_DINING,SHOPPING");
    }

    @Test
    void theSameSetGivesTheSameStringWhateverOrderItWasBuiltIn() {
        // a different string for an equal set would look like a change to hibernate and cause a needless update
        Set<ActivityCategory> oneWay = new LinkedHashSet<>(
                java.util.List.of(ActivityCategory.SHOPPING, ActivityCategory.FOOD_DINING));
        Set<ActivityCategory> otherWay = new LinkedHashSet<>(
                java.util.List.of(ActivityCategory.FOOD_DINING, ActivityCategory.SHOPPING));

        assertThat(converter.convertToDatabaseColumn(oneWay))
                .isEqualTo(converter.convertToDatabaseColumn(otherWay));
    }

    // ----- column to set -----

    @Test
    void nullBlankAndEmptyColumnsGiveAnEmptySet() {
        // rows created before the column existed come back as the default ''
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
        assertThat(converter.convertToEntityAttribute("   ")).isEmpty();
    }

    @Test
    void aStoredStringBecomesTheSameCategories() {
        assertThat(converter.convertToEntityAttribute("FOOD_DINING,SHOPPING"))
                .containsExactlyInAnyOrder(ActivityCategory.FOOD_DINING, ActivityCategory.SHOPPING);
    }

    @Test
    void spacesAroundNamesAreIgnored() {
        assertThat(converter.convertToEntityAttribute(" FOOD_DINING , SHOPPING "))
                .containsExactlyInAnyOrder(ActivityCategory.FOOD_DINING, ActivityCategory.SHOPPING);
    }

    @Test
    void aNameThatIsNoLongerACategoryIsSkippedRatherThanBreakingTheRead() {
        // a category renamed or removed later must not make every read of the preferences fail
        assertThat(converter.convertToEntityAttribute("SHOPPING,BUNGEE_JUMPING"))
                .containsExactly(ActivityCategory.SHOPPING);
    }

    @Test
    void aColumnOfOnlyUnknownNamesGivesAnEmptySet() {
        assertThat(converter.convertToEntityAttribute("BUNGEE_JUMPING,KARAOKE")).isEmpty();
    }

    @Test
    void emptySegmentsAreSkipped() {
        assertThat(converter.convertToEntityAttribute(",SHOPPING,,"))
                .containsExactly(ActivityCategory.SHOPPING);
    }

    @Test
    void repeatedNamesCollapseToOne() {
        assertThat(converter.convertToEntityAttribute("SHOPPING,SHOPPING"))
                .containsExactly(ActivityCategory.SHOPPING);
    }

    @Test
    void namesMustMatchTheEnumExactly() {
        // the converter only ever reads what it wrote, so a lower case name is treated as unknown
        assertThat(converter.convertToEntityAttribute("shopping")).isEmpty();
    }

    // ----- both ways -----

    @Test
    void everyCategoryOnItsOwnSurvivesTheRoundTrip() {
        for (ActivityCategory category : ActivityCategory.values()) {
            Set<ActivityCategory> original = EnumSet.of(category);

            assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(original)))
                    .isEqualTo(original);
        }
    }

    @Test
    void theWholeSetSurvivesTheRoundTrip() {
        Set<ActivityCategory> all = EnumSet.allOf(ActivityCategory.class);

        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(all)))
                .isEqualTo(all);
    }

    @Test
    void theStoredStringFitsTheColumn() {
        // the column is VARCHAR(255). every category at once is the longest it can get
        assertThat(converter.convertToDatabaseColumn(EnumSet.allOf(ActivityCategory.class)).length())
                .isLessThanOrEqualTo(255);
    }
}
