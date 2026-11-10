package csd.tripsense.admin;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityNotFoundException;
import csd.tripsense.activity.ActivityRepository;
import csd.tripsense.activity.ActivityService;
import csd.tripsense.alert.ImpactEvaluatedEvent;
import csd.tripsense.country.Country;
import csd.tripsense.country.CountryRepository;
import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactNotFoundException;
import csd.tripsense.impact.ImpactRepository;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryNotFoundException;
import csd.tripsense.itinerary.ItineraryRepository;
import csd.tripsense.itinerary.ItineraryService;
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
    private final ItineraryService itineraryService;
    private final ActivityService activityService;
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
                                ItineraryService itineraryService, ActivityService activityService,
                                ItineraryRepository itineraries, ActivityRepository activities,
                                ImpactRepository impacts, RecommendationRepository recommendations,
                                CountryRepository countries, NewsRepository news,
                                ActivityOptionRepository options, UserPreferenceRepository preferences,
                                ApplicationEventPublisher events) {
        this.users = users;
        this.userService = userService;
        this.itineraryService = itineraryService;
        this.activityService = activityService;
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
        markEditedByAdmin(itinerary);

        // the same path a traveller's new trip takes, so it is judged against recent news the same way
        return itineraryService.addItinerary(itinerary);
    }

    @Override
    @Transactional
    public AdminEdit<Itinerary> updateItinerary(Long itineraryId, AdminItineraryRequest request) {
        findItinerary(itineraryId);

        // summed up now, while every verdict is still there to look at
        List<Impact> before = impacts.findByItineraryId(itineraryId);
        List<ClearedVerdict> summaries = before.stream()
                .map(ClearedVerdict::of)
                .toList();

        // the same edit a traveller makes, so the itinerary service decides what the change makes
        // out of date and the impact package clears and judges it again
        Itinerary newDetails = new Itinerary();
        applyItinerary(newDetails, request);
        Itinerary saved = itineraryService.updateItinerary(itineraryId, newDetails, request.keepsImpacts());
        markEditedByAdmin(saved);

        List<ClearedVerdict> cleared = goneSince(summaries, impacts.findByItineraryId(itineraryId));
        return new AdminEdit<>(saved, cleared);
    }

    @Override
    @Transactional
    public Itinerary deleteItinerary(Long itineraryId) {
        Itinerary itinerary = findItinerary(itineraryId);

        // cascades to its activities and impacts, and from those to their alerts and recommendations
        itineraries.delete(itinerary);
        return itinerary;
    }

    @Override
    @Transactional
    public int deleteAllItineraries(Long userId) {
        findUser(userId);
        List<Itinerary> theirs = itineraries.findByUserId(userId);

        // same cascade as deleting one
        itineraries.deleteAll(theirs);
        return theirs.size();
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
    @Transactional(readOnly = true)
    public List<Activity> listUserActivities(Long userId) {
        findUser(userId);
        return activities.findByItineraryUserIdOrderByDateStart(userId);
    }

    @Override
    @Transactional
    public Activity createActivity(Long itineraryId, AdminActivityRequest request) {
        Itinerary itinerary = findItinerary(itineraryId);

        Activity activity = new Activity();
        activity.setItinerary(itinerary);
        applyActivity(activity, request);
        markEditedByAdmin(itinerary);

        // same as for a new itinerary. this also copies the itinerary's country onto the activity
        return activityService.addActivity(activity);
    }

    @Override
    @Transactional
    public AdminEdit<Activity> updateActivity(Long activityId, AdminActivityRequest request) {
        findActivity(activityId);

        List<Impact> before = impacts.findByActivityId(activityId);
        List<ClearedVerdict> summaries = before.stream()
                .map(ClearedVerdict::of)
                .toList();

        // no itinerary on the new details, so the activity stays on its trip
        Activity newDetails = new Activity();
        applyActivity(newDetails, request);
        Activity saved = activityService.updateActivity(activityId, newDetails, request.keepsImpacts());

        // the activity service leaves the category alone, nothing is judged on it
        saved.setCategory(request.category());
        markEditedByAdmin(saved.getItinerary());

        List<ClearedVerdict> cleared = goneSince(summaries, impacts.findByActivityId(activityId));
        return new AdminEdit<>(saved, cleared);
    }

    @Override
    @Transactional
    public Activity deleteActivity(Long activityId) {
        Activity activity = findActivity(activityId);
        markEditedByAdmin(activity.getItinerary());

        activities.delete(activity);   // cascades to its impacts, and their alerts and recommendations
        return activity;
    }

    @Override
    @Transactional
    public AdminBulkDelete deleteAllActivities(Long itineraryId) {
        Itinerary itinerary = findItinerary(itineraryId);
        List<Activity> onTrip = activities.findByItineraryIdOrderByDateStart(itineraryId);
        if (!onTrip.isEmpty()) {
            markEditedByAdmin(itinerary);
        }

        activities.deleteAll(onTrip);
        return new AdminBulkDelete(onTrip.size(), itinerary);
    }

    // the note on the traveller's trip. the itinerary is managed here, so it is saved on commit
    private static void markEditedByAdmin(Itinerary itinerary) {
        if (itinerary == null) {
            return;
        }
        itinerary.setAdminEditedAt(Instant.now());
    }

    // the verdicts StaleVerdictCleaner removed during the edit, so the controller can keep them in
    // the audit log: the news behind one may be too old to be judged again, so the log is all that
    // is left. the lookup after the edit flushes the delete first, so it only finds what stayed
    private static List<ClearedVerdict> goneSince(List<ClearedVerdict> before, List<Impact> after) {
        Set<Long> stillThere = after.stream()
                .map(Impact::getId)
                .collect(Collectors.toSet());

        return before.stream()
                .filter(verdict -> !stillThere.contains(verdict.impactId()))
                .toList();
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

        // the evaluator only ever reads the first verdict for a pair, so a second one would be
        // ignored and the two could disagree. edit the existing one instead
        Optional<Impact> existing = activity == null
                ? impacts.findFirstByNewsIdAndItineraryIdAndActivityIsNull(article.getId(), itinerary.getId())
                : impacts.findFirstByNewsIdAndActivityId(article.getId(), activity.getId());
        if (existing.isPresent()) {
            String on = activity == null ? "this trip" : "this activity";
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "impact " + existing.get().getId() + " already covers this news on " + on + ", edit that one instead");
        }

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
        stampAsCurrent(impact);
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
        String reason = blankToNull(request.reason());

        // an alert shows the reason, in the app and in its email. alertEligible covers one that is
        // still on its way, since the listener runs after commit. without a reason the row would also
        // count as not evaluated, and the evaluator would quietly replace this verdict with its own
        boolean raisedAnAlert = impact.getAlerts() != null && !impact.getAlerts().isEmpty();
        boolean alertOnItsWay = Boolean.TRUE.equals(impact.getAlertEligible());
        if ((raisedAnAlert || alertOnItsWay) && reason == null) {
            throw badRequest("this impact has or is about to raise an alert, so it needs a reason");
        }

        impact.setImpacted(request.impacted());
        impact.setReason(reason);
        impact.setRejected(Boolean.TRUE.equals(request.rejected()));
        stampAsCurrent(impact);
        return impacts.save(impact);
    }

    // without the current revision a verdict reads as judged on older details, so the evaluator
    // would replace it and advice could not be drafted from it. a new verdict key on every write
    // tells advice drafted from the old verdict that it has changed, see ImpactServiceImpl.findUnchanged
    private static void stampAsCurrent(Impact impact) {
        long revision = impact.getActivity() != null
                ? impact.getActivity().currentRevision()
                : impact.getItinerary().currentRevision();

        impact.setJudgedRevision(revision);
        impact.setPromptVersion(Impact.CURRENT_PROMPT_VERSION);
        impact.setVerdictKey(UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public Impact deleteImpact(Long impactId) {
        Impact impact = findImpact(impactId);

        impacts.delete(impact);   // cascades to its alert and recommendations
        return impact;
    }

    @Override
    @Transactional
    public AdminBulkDelete deleteAllImpacts(Long itineraryId) {
        Itinerary itinerary = findItinerary(itineraryId);

        // activity impacts carry the itinerary too, so this finds both kinds
        List<Impact> onTrip = impacts.findByItineraryId(itineraryId);

        impacts.deleteAll(onTrip);
        return new AdminBulkDelete(onTrip.size(), itinerary);
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

    @Override
    @Transactional
    public int deleteAllRecommendations(Long userId) {
        findUser(userId);
        List<Recommendation> theirs = recommendations.findByImpactUserIdOrderByIdDesc(userId);

        recommendations.deleteAll(theirs);
        return theirs.size();
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
        // and is only touched when the status really changes. a missing status keeps the current
        // one, which is PENDING for a new recommendation, so a partial edit never undoes a decision
        RecommendationStatus newStatus = request.status() == null ? recommendation.getStatus() : request.status();
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
