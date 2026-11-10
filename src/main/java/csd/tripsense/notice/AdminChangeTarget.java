package csd.tripsense.notice;

// what an admin change was about, so the traveller's alert can name the trip and the news and link
// to the trip. the names are copied rather than looked up later: the record has to read the same
// after the trip or article is deleted, like the audit log. every part is optional, eg "deleted all
// your trips" is about no one trip
public record AdminChangeTarget(Long itineraryId, String itineraryName, Long newsId, String newsTitle) {

    public static final AdminChangeTarget NONE = new AdminChangeTarget(null, null, null, null);

    public static AdminChangeTarget trip(Long itineraryId, String itineraryName) {
        return new AdminChangeTarget(itineraryId, itineraryName, null, null);
    }

    public AdminChangeTarget withNews(Long id, String title) {
        return new AdminChangeTarget(itineraryId, itineraryName, id, title);
    }
}
