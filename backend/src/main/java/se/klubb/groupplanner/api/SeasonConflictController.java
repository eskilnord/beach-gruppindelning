package se.klubb.groupplanner.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.season.ConflictService;
import se.klubb.groupplanner.season.SeasonConflict;

/**
 * {@code GET /api/seasons/{seasonId}/conflicts} (docs/design/04-solver.md §14.2, spec §19.2/§14.4).
 */
@RestController
@RequestMapping("/api/seasons")
public class SeasonConflictController {

    private final ConflictService conflictService;
    private final SeasonPlanRepository seasonPlanRepository;

    public SeasonConflictController(ConflictService conflictService, SeasonPlanRepository seasonPlanRepository) {
        this.conflictService = conflictService;
        this.seasonPlanRepository = seasonPlanRepository;
    }

    @GetMapping("/{seasonId}/conflicts")
    public List<SeasonConflict> conflicts(@PathVariable String seasonId) {
        if (seasonPlanRepository.findById(seasonId).isEmpty()) {
            throw new NotFoundException("Season plan not found: " + seasonId);
        }
        return conflictService.findConflicts(seasonId);
    }
}
