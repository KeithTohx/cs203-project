package csd.tripsense.chat;

import csd.tripsense.activity.Activity;
import csd.tripsense.itinerary.Itinerary;
import java.time.LocalDateTime;
import java.util.List;

public record ChatItineraryView(String name, String country,
                                LocalDateTime start, LocalDateTime end,
                                List<ChatActivityView> activities) {

    public static ChatItineraryView from(Itinerary i, List<Activity> activities) {
        return new ChatItineraryView(
            i.getName(),
            i.getCountry().getName(),   // adjust to your Country getter
            i.getDateStart(),
            i.getDateEnd(),
            activities.stream().map(ChatActivityView::from).toList());
    }
}