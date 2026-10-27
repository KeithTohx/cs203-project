package csd.tripsense.user.preference;

import jakarta.persistence.*;

import java.time.Instant;

import csd.tripsense.user.User;


@Entity
@Table(name = "user_preference")
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RiskTolerance riskTolerance = RiskTolerance.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FlexibilityLevel flexibilityLevel = FlexibilityLevel.SOMEWHAT_FLEXIBLE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExplanationDetail explanationDetail = ExplanationDetail.BRIEF;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TravelGroup travelGroup = TravelGroup.SOLO;

    @Column(nullable = false)
    private boolean travellingWithChildren = false;

    @Column(nullable = false)
    private boolean travellingWithElderly = false;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }

    protected UserPreference() {}

    public UserPreference(User user) { this.user = user; }

    public Long getId() { return id; }
    public User getUser() { return user; }

    public RiskTolerance getRiskTolerance() { return riskTolerance; }
    public void setRiskTolerance(RiskTolerance riskTolerance) { this.riskTolerance = riskTolerance; }

    public FlexibilityLevel getFlexibilityLevel() { return flexibilityLevel; }
    public void setFlexibilityLevel(FlexibilityLevel flexibilityLevel) { this.flexibilityLevel = flexibilityLevel; }

    public ExplanationDetail getExplanationDetail() { return explanationDetail; }
    public void setExplanationDetail(ExplanationDetail explanationDetail) { this.explanationDetail = explanationDetail; }

    public TravelGroup getTravelGroup() { return travelGroup; }
    public void setTravelGroup(TravelGroup travelGroup) { this.travelGroup = travelGroup; }

    public boolean isTravellingWithChildren() { return travellingWithChildren; }
    public void setTravellingWithChildren(boolean v) { this.travellingWithChildren = v; }

    public boolean isTravellingWithElderly() { return travellingWithElderly; }
    public void setTravellingWithElderly(boolean v) { this.travellingWithElderly = v; }

    public Instant getUpdatedAt() { return updatedAt; }
}