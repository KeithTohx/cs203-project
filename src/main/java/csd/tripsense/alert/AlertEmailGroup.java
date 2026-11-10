package csd.tripsense.alert;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** A stable delivery identity independent of which member is still visible on a retry. */
@Entity
public class AlertEmailGroup {
    @Id private String id;
    private String deliveryKey;
    private Boolean delivered;
    private Boolean partial;
    private java.time.Instant sentAt;
    @jakarta.persistence.Column(name="itinerary_id")
    private Long itineraryId;
    private Long newsId;
    @jakarta.persistence.ManyToOne(fetch=jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name="itinerary_id",insertable=false,updatable=false)
    private csd.tripsense.itinerary.Itinerary itinerary;
    protected AlertEmailGroup() {}
    public AlertEmailGroup(Long tripId, Long newsId, String deliveryKey) {
        this.id = key(tripId, newsId); this.deliveryKey = deliveryKey;
        this.delivered = false;
        this.itineraryId=tripId; this.newsId=newsId;
    }
    public static String key(Long tripId, Long newsId) { return tripId + ":" + newsId; }
    public boolean isDelivered() { return Boolean.TRUE.equals(delivered); }
    public void markDelivered(java.time.Instant when) {
        delivered=true;
        if (sentAt==null) sentAt=when;
    }
    public boolean isPartial() { return Boolean.TRUE.equals(partial); }
    public void beginDelivery(String key) { deliveryKey=key; delivered=false; sentAt=null; }
    public void setPartial(boolean partial) { this.partial=partial; }
    public String getDeliveryKey() { return deliveryKey; }
}
