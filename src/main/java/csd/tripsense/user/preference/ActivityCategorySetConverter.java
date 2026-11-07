package csd.tripsense.user.preference;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import csd.tripsense.activity.ActivityCategory;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores a set of categories in one text column, eg "FOOD_DINING,MUSEUM_GALLERY", so the
 * preferences stay in a single table with no join table.
 */
@Converter
public class ActivityCategorySetConverter implements AttributeConverter<Set<ActivityCategory>, String> {

    @Override
    public String convertToDatabaseColumn(Set<ActivityCategory> categories) {
        if (categories == null || categories.isEmpty()) {
            return "";
        }
        // sorted so the same set always gives the same string
        return categories.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    @Override
    public Set<ActivityCategory> convertToEntityAttribute(String db) {
        Set<ActivityCategory> result = new HashSet<>();
        if (db == null || db.isBlank()) {
            return result;
        }
        Arrays.stream(db.split(","))
                .map(String::trim)
                .forEach(name -> {
                    try {
                        result.add(ActivityCategory.valueOf(name));
                    } catch (IllegalArgumentException e) {
                        // a category renamed or removed later should not break every read of
                        // the preferences, so an unknown name is skipped
                    }
                });
        return result;
    }
}
