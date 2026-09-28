package csd.tripsense.itinerary;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class ItineraryServiceImpl implements ItineraryService {
    private ItineraryRepository itineraries;

    public ItineraryServiceImpl(ItineraryRepository itineraries){
        this.itineraries = itineraries;
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

        if (itineraryList.isEmpty()) throw new ItineraryNotFoundException("User (ID: " + userId + ") has no existing itineraries.");
        for (Itinerary itinerary : itineraryList) {
            if (itinerary.getUser().getId().equals(userId)) {
                itinerariesByUser.add(itinerary);
            }
        }
        return itinerariesByUser;
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
        return itineraries.save(itinerary);
    }

    @Override 
    public Itinerary updateItinerary(Long id, Itinerary newItineraryInfo){
        return itineraries.findById(id).map(itinerary -> {
            itinerary.setUser(newItineraryInfo.getUser());
            itinerary.setCountry(newItineraryInfo.getCountry());
            itinerary.setName(newItineraryInfo.getName());
            itinerary.setDateStart(newItineraryInfo.getDateStart());
            itinerary.setDateEnd(newItineraryInfo.getDateEnd());
            return itineraries.save(itinerary);
        }).orElseThrow(() -> new ItineraryNotFoundException(id));
    }

    @Override 
    public void deleteItinerary(Long id){
        if (!itineraries.existsById(id)) {
            throw new ItineraryNotFoundException(id);
        }
        itineraries.deleteById(id);
    }

}
