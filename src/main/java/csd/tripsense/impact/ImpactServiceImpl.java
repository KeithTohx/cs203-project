package csd.tripsense.impact;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

@Service
public class ImpactServiceImpl implements ImpactService {
    private ImpactRepository impacts;

    public ImpactServiceImpl(ImpactRepository impacts){
        this.impacts = impacts;
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
    public Impact addImpact(Impact impact) {
        return impacts.save(impact);
    }

    @Override
    public Impact updateImpact(Long id, Impact newImpactInfo) {
        return impacts.findById(id).map(impact -> {
            impact.setUser(newImpactInfo.getUser());
            impact.setNews(newImpactInfo.getNews());
            impact.setItinerary(newImpactInfo.getItinerary());
            impact.setActivity(newImpactInfo.getActivity());
            impact.setImpacted(newImpactInfo.getImpacted());
            return impacts.save(impact);
        }).orElseThrow(() -> new ImpactNotFoundException(id));
    }

    @Override
    public void deleteImpact(Long id){
        if (!impacts.existsById(id)) {
            throw new ImpactNotFoundException(id);
        }
        
        impacts.deleteById(id);
    }
}
