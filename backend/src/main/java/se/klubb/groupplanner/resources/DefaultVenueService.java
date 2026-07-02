package se.klubb.groupplanner.resources;

import org.springframework.stereotype.Service;
import se.klubb.groupplanner.domain.Venue;
import se.klubb.groupplanner.repo.VenueRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Resolves the "default venue" used whenever a court is needed but the caller didn't specify a
 * {@code venueId} — auto-creates one named "Hallen" the first time, per spec §12.2/docs/plan.md M5
 * row ("MVP-simple: auto-create a default venue on first court creation if none exists"). Shared by
 * {@code TrainingBlockGenerationService} (auto-creating "Bana 1".."Bana N") and {@code
 * CourtController} (direct {@code POST /api/courts} with no {@code venueId}).
 */
@Service
public class DefaultVenueService {

    private final VenueRepository venueRepository;

    public DefaultVenueService(VenueRepository venueRepository) {
        this.venueRepository = venueRepository;
    }

    public String resolveOrCreateDefaultVenueId() {
        return venueRepository.findFirst()
                .map(Venue::id)
                .orElseGet(() -> venueRepository.insert(new Venue(Uuid7.generate(), Venue.DEFAULT_VENUE_NAME, null)).id());
    }
}
