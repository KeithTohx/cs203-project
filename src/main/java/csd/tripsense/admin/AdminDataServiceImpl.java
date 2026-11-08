package csd.tripsense.admin;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityCreatedEvent;
import csd.tripsense.activity.ActivityNotFoundException;
import csd.tripsense.activity.ActivityRepository;
import csd.tripsense.alert.ImpactEvaluatedEvent;
import csd.tripsense.country.Country;
import csd.tripsense.country.CountryRepository;
import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactNotFoundException;
import csd.tripsense.impact.ImpactRepository;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryCreatedEvent;
import csd.tripsense.itinerary.ItineraryNotFoundException;
import csd.tripsense.itinerary.ItineraryRepository;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsRepository;
import csd.tripsense.recommendation.ActivityOption;
import csd.tripsense.recommendation.ActivityOptionRepository;
import csd.tripsense.recommendation.Recommendation;
import csd.tripsense.recommendation.RecommendationRepository;
import csd.tripsense.recommendation.RecommendationServiceImpl;
import csd.tripsense.recommendation.RecommendationStatus;
import csd.tripsense.user.Role;
import csd.tripsense.user.User;
import csd.tripsense.user.UserNotFoundException;
import csd.tripsense.user.UserRepository;
import csd.tripsense.user.UserService;
import csd.tripsense.user.preference.UserPreference;
import csd.tripsense.user.preference.UserPreferenceRepository;

@Service
public class AdminDataServiceImpl implements AdminDataService {

    private final UserRepository users;
    private final UserService userService;
    private final ItineraryRepository itineraries;
    private final ActivityRepository activities;
    private final ImpactRepository impacts;
    private final RecommendationRepository recommendations;
    private final CountryRepository countries;
    private final NewsRepository news;
    private final ActivityOptionRepository options;
    private final UserPreferenceRepository preferences;
    private final ApplicationEventPublisher events;

    public AdminDataServiceImpl(UserRepository users, UserService userService,
                                ItineraryRepository itineraries, ActivityRepository activities,
                                ImpactRepository impacts, RecommendationRepository recommendations,
                                CountryRepository countries, NewsRepository news,
                                ActivityOptionRepository options, UserPreferenceRepository preferences,
                                ApplicationEventPublisher events) {
        this.users = users;
        this.userService = userService;
        this.itineraries = itineraries;
        this.activities = activities;
        this.impacts = impacts;
        this.recommendations = recommendations;
        this.countries = countries;
        this.news = news;
        this.options = options;
        this.preferences = preferences;
        this.events = events;
    }

    // ---------- accounts ----------

    @Override
    @Transactional
    public User createUser(CreateUserRequest request) {
        // register does the duplicate checks and the hashing, and always makes a USER
        User details = new User(request.email().trim(), request.username().trim(), request.password(), "USER");
        User created = userService.register(details);

        if (request.role() == Role.ADMIN) {
            created.setRole(Role.ADMIN);
            created = users.saveAndFlush(created);
        }
        return created;
    }

    // ---------- itineraries ----------

    @Override
    @Transactional(readOnly = true)
    public List<Itinerary> listItineraries(Long userId) {
        findUser(userId);
        List<Itinerary> found = itineraries.findByUserId(userId);

        return found.stream()
                .sorted(Comparator.comparing(Itinerary::getId))
                .toList();
    }

    @Override
    @Transactional
    public Itinerary createItinerary(Long userId, AdminItineraryRequest request) {
        User owner = findUser(userId);

        Itinerary itinerary = new Itinerary();
        itinerary.setUser(owner);
        applyItinerary(itinerary, request);
        Itinerary saved = itineraries.save(itinerary);

        // judged against recent news like a traveller's new trip. the listener runs after commit,
        // on its own thread, and gives up quietly when the model is down
        events.publishEvent(new ItineraryCreatedEvent(saved.getId()));
        return saved;
    }

    @Override
    @Transactional
    public Itinerary updateItinerary(Long itineraryId, AdminItineraryRequest request) {
        Itinerary itinerary = findItinerary(itineraryId);
        applyItinerary(itinerary, request);
        Itinerary saved = itineraries.save(itinerary);

        // activities carry their own copy of the country, so they follow the itinerary
        List<Activity> itsActivities = activities.findByItineraryIdOrderByDateStart(itineraryId);
        for (Activity activity : itsActivities) {
            activity.setCountry(saved.getCountry());
        }
        activities.saveAll(itsActivities);

        return saved;
    }

    @Override
    @Transactional
    public Itinerary deleteItinerary(Long itineraryId) {
        Itinerary itinerary = findItinerary(itineraryId);

        // recommendations point at impacts but nothing cascades to them, so they go first.
        // the itinerary itself cascades to its activities, impacts and their alerts
        recommendations.deleteAll(recommendations.findByImpactItineraryId(itineraryId));
        recommendations.flush();

        itineraries.delete(itinerary);
        return itinerary;
    }

    private void applyItinerary(Itinerary itinerary, AdminItineraryRequest request) {
        requireEndNotBeforeStart(request.dateStart(), request.dateEnd());

        Country country = countries.findById(request.countryId())
                .orElseThrow(() -> badRequest("no country with id " + request.countryId()));

        itinerary.setName(request.name().trim());
        itinerary.setCountry(country);
        itinerary.setDateStart(request.dateStart());
        itinerary.setDateEnd(request.dateEnd());
    }

    // ---------- activities ----------

    @Override
    @Transactional(readOnly = true)
    public List<Activity> listActivities(Long itineraryId) {
        findItinerary(itineraryId);
        return activities.findByItineraryIdOrderByDateStart(itineraryId);
    }

    @Override
    @Transactional
    public Activity createActivity(Long itineraryId, AdminActivityRequest request) {
        Itinerary itinerary = findItinerary(itineraryId);

        Activity activity = new Activity();
        activity.setItinerary(itinerary);
        activity.setCountry(itinerary.getCountry());
        applyActivity(activity, request);
        Activity saved = activities.save(activity);

        // same as for a new itinerary, see above
        events.publishEvent(new ActivityCreatedEvent(saved.getId()));
        return saved;
    }

    @Override
    @Transactional
    public Activity updateActivity(Long activityId, AdminActivityRequest request) {
        Activity activity = findActivity(activityId);
        applyActivity(activity, request);
        return activities.save(activity);
    }

    @Override
    @Transactional
    public Activity deleteActivity(Long activityId) {
        Activity activity = findActivity(activityId);

        recommendations.deleteAll(recommendations.findByImpactActivityId(activityId));
        recommendations.flush();

        activities.delete(activity);   // cascades to its impacts and their alerts
        return activity;
    }

    private void applyActivity(Activity activity, AdminActivityRequest request) {
        requireEndNotBeforeStart(request.dateStart(), request.dateEnd());

        activity.setName(request.name().trim());
        activity.setAddress(blankToNull(request.address()));
        activity.setDateStart(request.dateStart());
        activity.setDateEnd(request.dateEnd());
        activity.setCategory(request.category());
    }

    // ---------- impacts ----------

    @Override
    @Transactional(readOnly = true)
    public List<Impact> listImpacts(Long userId) {
        findUser(userId);
        List<Impact> found = impacts.findByUserId(userId);

        return found.stream()
                .sorted(Comparator.comparing(Impact::getId).reversed())
                .toList();
    }

    @Override
    @Transactional
    public Impact createImpact(Long userId, AdminImpactRequest request) {
        User owner = findUser(userId);

        Itinerary itinerary = itineraries.findById(request.itineraryId())
                .orElseThrow(() -> badRequest("no itinerary with id " + request.itineraryId()));
        boolean ownsItinerary = Objects.equals(itinerary.getUser().getId(), owner.getId());
        if (!ownsItinerary) {
            throw badRequest("itinerary " + itinerary.getId() + " does not belong to user " + userId);
        }

        Activity activity = null;
        if (request.activityId() != null) {
            activity = activities.findById(request.activityId())
                    .orElseThrow(() -> badRequest("no activity with id " + request.activityId()));
            boolean onThisItinerary = Objects.equals(activity.getItinerary().getId(), itinerary.getId());
            if (!onThisItinerary) {
                throw badRequest("activity " + activity.getId() + " is not on itinerary " + itinerary.getId());
            }
        }

        News article = news.findById(request.newsId())
                .orElseThrow(() -> badRequest("no news with id " + request.newsId()));

        boolean impacted = request.impacted();
        boolean rejected = Boolean.TRUE.equals(request.rejected());
        String reason = blankToNull(request.reason());

        // the alert service quietly skips anything it would never show, so say why up front instead
        boolean wantsAlert = Boolean.TRUE.equals(request.sendAlert());
        if (wantsAlert && (!impacted || rejected || reason == null)) {
            throw badRequest("an alert needs an impacted, not rejected impact with a reason");
        }

        Impact impact = new Impact();
        impact.setUser(owner);
        impact.setItinerary(itinerary);
        impact.setActivity(activity);
        impact.setNews(article);
        impact.setImpacted(impacted);
        impact.setReason(reason);
        impact.setRejected(rejected);
        impact.setAlertEligible(wantsAlert);
        Impact saved = impacts.save(impact);

        // the alert listener runs after this transaction commits, the same way it does for the evaluator
        if (wantsAlert) {
            events.publishEvent(new ImpactEvaluatedEvent(saved.getId()));
        }
        return saved;
    }

    @Override
    @Transactional
    public Impact updateImpact(Long impactId, AdminImpactUpdateRequest request) {
        Impact impact = findImpact(impactId);

        impact.setImpacted(request.impacted());
        impact.setReason(blankToNull(request.reason()));
        impact.setRejected(Boolean.TRUE.equals(request.rejected()));
        return impacts.save(impact);
    }

    @Override
    @Transactional
    public Impact deleteImpact(Long impactId) {
        Impact impact = findImpact(impactId);

        recommendations.deleteAll(recommendations.findByImpactIdOrderByIdAsc(impactId));
        recommendations.flush();

        impacts.delete(impact);   // cascades to its alert
        return impact;
    }

    // ---------- recommendations ----------

    @Override
    @Transactional(readOnly = true)
    public List<Recommendation> listRecommendations(Long userId) {
        findUser(userId);
        List<Recommendation> found = recommendations.findByImpactUserIdOrderByIdDesc(userId);

        // stale is not stored, so it is worked out here the same way the traveller sees it.
        // the repository and not PreferenceService, which would make a row for anyone without one.
        // someone who never saved preferences has nothing to be out of date with
        Optional<UserPreference> saved = preferences.findByUserId(userId);
        if (saved.isPresent()) {
            for (Recommendation recommendation : found) {
                boolean stale = RecommendationServiceImpl.isStale(recommendation, saved.get());
                recommendation.setStale(stale);
            }
        }
        return found;
    }

    @Override
    @Transactional
    public Recommendation createRecommendation(Long impactId, AdminRecommendationRequest request) {
        Impact impact = findImpact(impactId);

        Recommendation recommendation = new Recommendation();
        recommendation.setImpact(impact);
        recommendation.setCreatedAt(LocalDateTime.now());
        recommendation.setStatus(RecommendationStatus.PENDING);
        applyRecommendation(recommendation, request);
        return recommendations.save(recommendation);
    }

    @Override
    @Transactional
    public Recommendation updateRecommendation(Long recommendationId, AdminRecommendationRequest request) {
        Recommendation recommendation = findRecommendation(recommendationId);
        applyRecommendation(recommendation, request);
        return recommendations.save(recommendation);
    }

    @Override
    @Transactional
    public Recommendation deleteRecommendation(Long recommendationId) {
        Recommendation recommendation = findRecommendation(recommendationId);
        recommendations.delete(recommendation);
        return recommendation;
    }

    private void applyRecommendation(Recommendation recommendation, AdminRecommendationRequest request) {
        ActivityOption option = null;
        if (request.optionId() != null) {
            option = options.findById(request.optionId())
                    .orElseThrow(() -> badRequest("no activity option with id " + request.optionId()));
        }

        recommendation.setSeverity(request.severity());
        recommendation.setAction(request.action());
        recommendation.setAdvice(blankToNull(request.advice()));
        recommendation.setSuggestedOption(option);

        // decidedAt follows the status the same way it does when a traveller decides,
        // and is only touched when the status really changes
        RecommendationStatus newStatus = request.status() == null ? RecommendationStatus.PENDING : request.status();
        boolean statusChanged = newStatus != recommendation.getStatus();
        if (statusChanged) {
            recommendation.setStatus(newStatus);
            recommendation.setDecidedAt(newStatus == RecommendationStatus.PENDING ? null : LocalDateTime.now());
        }
    }

    // ---------- lookups ----------

    private User findUser(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new UserNotFoundException("No user found with id " + id));
    }

    private Itinerary findItinerary(Long id) {
        return itineraries.findById(id).orElseThrow(() -> new ItineraryNotFoundException(id));
    }

    private Activity findActivity(Long id) {
        return activities.findById(id).orElseThrow(() -> new ActivityNotFoundException(id));
    }

    private Impact findImpact(Long id) {
        return impacts.findById(id).orElseThrow(() -> new ImpactNotFoundException(id));
    }

    private Recommendation findRecommendation(Long id) {
        return recommendations.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No recommendation with id " + id));
    }

    private static void requireEndNotBeforeStart(LocalDateTime start, LocalDateTime end) {
        boolean endsBeforeItStarts = start != null && end != null && end.isBefore(start);
        if (endsBeforeItStarts) {
            throw badRequest("the end cannot be before the start");
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
