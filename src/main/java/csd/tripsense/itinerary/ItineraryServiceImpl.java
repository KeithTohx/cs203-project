package csd.tripsense.itinerary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import csd.tripsense.activity.Activity;
import csd.tripsense.country.Country;

@Service
public class ItineraryServiceImpl implements ItineraryService {

    private ItineraryRepository itineraries;
    private ApplicationEventPublisher events;

    public ItineraryServiceImpl(ItineraryRepository itineraries, ApplicationEventPublisher events) {
        this.itineraries = itineraries;
        this.events = events;
    }

    @Override 
    public List<Itinerary> getAllItineraries(){
        return itineraries.findAll();
    }

    @Override 
    public Itinerary getItineraryById(Long id){
        return itineraries.findById(id).map(itinerary -> {
            return itinerary;
        }).orElseThrow(() -> new ItineraryNotFoundException(id));
    }

    @Override
    public List<Itinerary> getItinerariesByUser(Long userId){
        List<Itinerary> itinerariesByUser = new ArrayList<>();
        List<Itinerary> itineraryList = getAllItineraries();

        if (itineraryList.isEmpty()) throw new ItineraryNotFoundException("There are no existing itineraries.");
        for (Itinerary itinerary : itineraryList) {
            if (itinerary.getUser().getId().equals(userId)) {
                itinerariesByUser.add(itinerary);
            }
        }
        if (itinerariesByUser.isEmpty()) throw new ItineraryNotFoundException("User (ID: " + userId + ") has no existing itineraries.");
        return itinerariesByUser;
    }

    @Override
    public Itinerary getItineraryByIdAndUserId(Long id, Long userId){
        List<Itinerary> itinerariesByUserId = getItinerariesByUser(userId);

        for (Itinerary itinerary : itinerariesByUserId){
            if (itinerary.getId().equals(id)) return itinerary;
        }

        throw new ItineraryNotFoundException(id);
    }

    @Override
    public List<Itinerary> getItinerariesByCountry(Long countryId){
        List<Itinerary> itinerariesByCountry = new ArrayList<>();
        List<Itinerary> itineraryList = getAllItineraries();

        if (itineraryList.isEmpty()) throw new ItineraryNotFoundException("No existing itineraries for Country (ID: " + countryId + " ).");
        for (Itinerary itinerary : itineraryList) {
            if (itinerary.getCountry().getId().equals(countryId)) {
                itinerariesByCountry.add(itinerary);
            }
        }
        return itinerariesByCountry;
    }

    @Override
    public Itinerary addItinerary(Itinerary itinerary){
        Itinerary saved = itineraries.save(itinerary);
        events.publishEvent(new ItineraryCreatedEvent(saved.getId()));
        return saved;
    }

    // one transaction, so the activities' country, the cleared verdicts and the itinerary itself
    // are saved together, and the re-evaluation only starts once all of it has committed
    @Override
    @Transactional
    public Itinerary updateItinerary(Long id, Itinerary newItineraryInfo){
        return itineraries.findById(id).map(itinerary -> {
            Long oldCountryId = countryId(itinerary.getCountry());
            String oldName = itinerary.getName();
            LocalDateTime oldStart = itinerary.getDateStart();
            LocalDateTime oldEnd = itinerary.getDateEnd();

            itinerary.setCountry(newItineraryInfo.getCountry());
            itinerary.setName(newItineraryInfo.getName());
            itinerary.setDateStart(newItineraryInfo.getDateStart());
            itinerary.setDateEnd(newItineraryInfo.getDateEnd());
            Itinerary saved = itineraries.save(itinerary);

            boolean countryChanged = !Objects.equals(oldCountryId, countryId(saved.getCountry()));
            boolean renamed = !Objects.equals(oldName, saved.getName());
            boolean datesChanged = !Objects.equals(oldStart, saved.getDateStart())
                    || !Objects.equals(oldEnd, saved.getDateEnd());

            // activities keep their own copy of the country, and the news job matches on it
            if (countryChanged) {
                moveActivitiesTo(saved);
            }

            // the whole trip prompt shows the model the trip's name, country and dates
            if (countryChanged || renamed || datesChanged) {
                events.publishEvent(new ItineraryEditedEvent(saved.getId(), countryChanged));
            }
            return saved;
        }).orElseThrow(() -> new ItineraryNotFoundException(id));
    }

    // the activities are managed inside the transaction, so setting the field is enough to save it
    private static void moveActivitiesTo(Itinerary itinerary) {
        List<Activity> itsActivities = itinerary.getActivities();
        if (itsActivities == null) {
            return;
        }

        for (Activity activity : itsActivities) {
            activity.setCountry(itinerary.getCountry());
        }
    }

    private static Long countryId(Country country) {
        if (country == null) {
            return null;
        }
        return country.getId();
    }

    @Override 
    public void deleteItinerary(Long id){
        if (!itineraries.existsById(id)) {
            throw new ItineraryNotFoundException(id);
        }
        itineraries.deleteById(id);
    }

        @Override
        public List<Long> getCountryIdsWithActiveItineraries(LocalDateTime now) {
            return itineraries.findCountryIdsWithActiveItineraries(now);
        }

        @Override
        public List<Itinerary> getActiveItinerariesByCountry(Long countryId, LocalDateTime now) {
            return itineraries.findActiveByCountry(countryId, now);
        }

        @Override
        public List<Itinerary> getItinerariesPendingForNews(Long newsId, Long countryId, LocalDateTime now) {
            return itineraries.findPendingForNews(newsId, countryId, now);
        }

}
