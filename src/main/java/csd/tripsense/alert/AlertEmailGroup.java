package csd.tripsense.alert;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** A stable delivery identity independent of which member is still visible on a retry. */
@Entity
public class AlertEmailGroup {
    @Id private String id;
    private String deliveryKey;
    protected AlertEmailGroup() {}
    public AlertEmailGroup(Long tripId, Long newsId, String deliveryKey) {
        this.id = key(tripId, newsId); this.deliveryKey = deliveryKey;
    }
    public static String key(Long tripId, Long newsId) { return tripId + ":" + newsId; }
    public String getDeliveryKey() { return deliveryKey; }
}
