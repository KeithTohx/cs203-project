package csd.tripsense.itinerary;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;

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
    @org.springframework.transaction.annotation.Transactional
    public Itinerary addItinerary(Itinerary itinerary){
        Itinerary saved = itineraries.save(itinerary);
        events.publishEvent(new ItineraryCreatedEvent(saved.getId()));
        return saved;
    }

    @Override 
    @org.springframework.transaction.annotation.Transactional
    public Itinerary updateItinerary(Long id, Itinerary newItineraryInfo){
        return itineraries.findById(id).map(itinerary -> {
            Long oldCountry = itinerary.getCountry() == null ? null : itinerary.getCountry().getId();
            Long newCountry = newItineraryInfo.getCountry() == null ? null : newItineraryInfo.getCountry().getId();
            boolean countryChanged = !java.util.Objects.equals(oldCountry, newCountry);
            boolean changed = countryChanged
                    || !java.util.Objects.equals(itinerary.getName(), newItineraryInfo.getName())
                    || !java.util.Objects.equals(itinerary.getDateStart(), newItineraryInfo.getDateStart())
                    || !java.util.Objects.equals(itinerary.getDateEnd(), newItineraryInfo.getDateEnd());
            itinerary.setCountry(newItineraryInfo.getCountry());
            itinerary.setName(newItineraryInfo.getName());
            itinerary.setDateStart(newItineraryInfo.getDateStart());
            itinerary.setDateEnd(newItineraryInfo.getDateEnd());
            if (changed) itinerary.reviseAssessment();
            if (countryChanged && itinerary.getActivities() != null) {
                for (var activity : itinerary.getActivities()) {
                    activity.setCountry(itinerary.getCountry());
                    activity.reviseAssessment();
                }
            }
            Itinerary saved = itineraries.save(itinerary);
            if (changed) events.publishEvent(new ItineraryEditedEvent(saved.getId()));
            return saved;
        }).orElseThrow(() -> new ItineraryNotFoundException(id));
    }

    @Override 
    @org.springframework.transaction.annotation.Transactional
    public void deleteItinerary(Long id){
        if (!itineraries.existsById(id)) {
            throw new ItineraryNotFoundException(id);
        }
        var removed = itineraries.findById(id).orElseThrow();
        Long userId = removed.getUser().getId();
        itineraries.deleteById(id);
        events.publishEvent(new csd.tripsense.alert.AlertsChangedEvent(userId));
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
