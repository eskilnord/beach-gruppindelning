package se.klubb.groupplanner.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;

/**
 * Group generation (docs/design/04-solver.md §7) — not explicitly listed among M6a's REST endpoints
 * in the design doc's §14.2 (which only covers solve/explain/what-if), but a necessary addition:
 * without a way to create {@code training_group} rows, {@code SolverInputAssembler} has nothing to
 * assign players into and the solve loop has no groups to test end-to-end. Mirrors the shape of the
 * existing generation endpoint, {@code PUT /api/plans/{planId}/time-slots/{slotId}/courts}.
 */
@RestController
public class GroupController {

    private final GroupGenerator groupGenerator;
    private final TrainingGroupRepository trainingGroupRepository;

    public GroupController(GroupGenerator groupGenerator, TrainingGroupRepository trainingGroupRepository) {
        this.groupGenerator = groupGenerator;
        this.trainingGroupRepository = trainingGroupRepository;
    }

    /** (Re)generates groups per §7's policy: count = clamp(ceil(active/target), 1, activeBlocks);
     * refuses (409) if any existing group/assignment is locked. */
    @PostMapping("/api/plans/{planId}/groups/generate")
    public List<TrainingGroup> generate(@PathVariable String planId) {
        return groupGenerator.generate(planId);
    }

    @GetMapping("/api/plans/{planId}/groups")
    public List<TrainingGroup> list(@PathVariable String planId) {
        return trainingGroupRepository.findByActivityPlanId(planId);
    }
}
