package csd.tripsense.chat;

import csd.tripsense.activity.Activity;
import java.time.LocalDateTime;

public record ChatActivityView(String name, String address, LocalDateTime start, LocalDateTime end) {
    public static ChatActivityView from(Activity a) {
        return new ChatActivityView(a.getName(), a.getAddress(), a.getDateStart(), a.getDateEnd());
    }
}