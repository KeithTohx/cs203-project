package csd.tripsense.impact;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityService;
import csd.tripsense.chat.ChatService;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsService;

@Service
public class ImpactServiceImpl implements ImpactService {
    private ImpactRepository impacts;
    private ChatService chatService;
    private ItineraryService itineraryService;
    private ActivityService activityService;
    private NewsService newsService;

    public ImpactServiceImpl(ImpactRepository impacts, ChatService chatService,
                             ItineraryService itineraryService, ActivityService activityService,
                             NewsService newsService){
        this.impacts = impacts;
        this.chatService = chatService;
        this.itineraryService = itineraryService;
        this.activityService = activityService;
        this.newsService = newsService;
    }

    // the shape we ask the model to answer in
    private record Verdict(boolean impacted, String reason) {
    }

    @Override
    public List<Impact> listImpacts() {
        return impacts.findAll();
    }
    
    @Override
    public Impact getImpact(Long id) {
        return impacts.findById(id).map(impact -> {
            return impact;
        }).orElseThrow(() -> new ImpactNotFoundException(id));
    }

    @Override
    public List<Impact> getImpactsByUser(Long userId) {
        return impacts.findAll().stream()
                .filter(impact -> impact.getUser().getId().equals(userId))
                .collect(Collectors.toList());
    }

    @Override
    public List<Impact> getImpactsByItinerary(Long itineraryId) {
        return impacts.findAll().stream()
                .filter(impact -> impact.getItinerary() != null
                        && impact.getItinerary().getId().equals(itineraryId))
                .collect(Collectors.toList());
    }

    @Override
    public List<Impact> getImpactsByActivity(Long activityId) {
        return impacts.findAll().stream()
                .filter(impact -> impact.getActivity() != null
                        && impact.getActivity().getId().equals(activityId))
                .collect(Collectors.toList());
    }

    @Override
    public void deleteImpact(Long id){
        if (!impacts.existsById(id)) {
            throw new ImpactNotFoundException(id);
        }
        
        impacts.deleteById(id);
    }

    @Override
    public EvaluateResponse evaluate(Long itineraryId, Long newsId) {
        // these throw their own not found exceptions, which spring turns into a 404
        Itinerary itinerary = itineraryService.getItineraryById(itineraryId);
        News news = newsService.getNewsById(newsId);
        List<Activity> activities = activityService.getActivitiesByItinerary(itineraryId);

        List<EvaluateResponse.ActivityResult> results = new ArrayList<>();
        for (Activity activity : activities) {
            results.add(evaluateActivity(itinerary, news, activity));
        }

        boolean anyImpacted = results.stream().anyMatch(EvaluateResponse.ActivityResult::impacted);

        return new EvaluateResponse(itinerary.getId(), news.getId(), news.getTitle(),
                anyImpacted, results);
    }

    /**
     * Returns the stored verdict for this activity if we already have one, otherwise asks
     * the model and stores the answer.
     *
     * A stored row only counts as already evaluated if it has a reason. Rows that came from
     * the seed data have no reason, so they get evaluated properly the first time round.
     */
    private EvaluateResponse.ActivityResult evaluateActivity(Itinerary itinerary, News news,
                                                             Activity activity) {
        Optional<Impact> stored = findStored(news, activity);
        if (stored.isPresent() && stored.get().getReason() != null) {
            Impact impact = stored.get();
            return new EvaluateResponse.ActivityResult(activity.getId(), activity.getName(),
                    Boolean.TRUE.equals(impact.getImpacted()), impact.getReason());
        }

        try {
            // one call per activity, so a failure on one still leaves the others usable
            Verdict verdict = chatService.ask(buildPrompt(news, activity), Verdict.class);
            save(stored.orElseGet(Impact::new), itinerary, news, activity, verdict);
            return new EvaluateResponse.ActivityResult(activity.getId(), activity.getName(),
                    verdict.impacted(), verdict.reason());
        } catch (Exception e) {
            // nothing is saved on failure, so the next call retries instead of serving the error
            return new EvaluateResponse.ActivityResult(activity.getId(), activity.getName(),
                    false, "Could not evaluate: " + e.getMessage());
        }
    }

    // an impact already recorded for this news and activity, if there is one
    private Optional<Impact> findStored(News news, Activity activity) {
        return getImpactsByActivity(activity.getId()).stream()
                .filter(impact -> impact.getNews().getId().equals(news.getId()))
                .findFirst();
    }

    private String buildPrompt(News news, Activity activity) {
        return """
                You are helping a traveller work out whether a news story affects one planned activity.

                News headline: %s
                News details: %s

                Activity: %s
                Location: %s
                Planned for: %s

                Decide whether this activity is affected. Only say it is affected if the news is about
                the same place and around the same time. Being in a different city or country means it
                is not affected. Give one short sentence of reasoning that refers to the activity.
                """
                .formatted(news.getTitle(), news.getDescription(),
                        activity.getName(), activity.getAddress(), activity.getDateStart());
    }

    // updates the row we found, or the blank one we made, so a pair is never stored twice
    private void save(Impact impact, Itinerary itinerary, News news, Activity activity,
                      Verdict verdict) {
        impact.setUser(itinerary.getUser());
        impact.setNews(news);
        impact.setItinerary(itinerary);
        impact.setActivity(activity);
        impact.setImpacted(verdict.impacted());
        impact.setReason(verdict.reason());
        impacts.save(impact);
    }
}
