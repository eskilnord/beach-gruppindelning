package se.klubb.groupplanner.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.capacity.CapacityResponse;
import se.klubb.groupplanner.capacity.CapacityService;

/** {@code GET /api/plans/{planId}/capacity} — pre-solve capacity analysis (spec §12.4). */
@RestController
public class CapacityController {

    private final CapacityService capacityService;

    public CapacityController(CapacityService capacityService) {
        this.capacityService = capacityService;
    }

    @GetMapping("/api/plans/{planId}/capacity")
    public CapacityResponse getCapacity(@PathVariable String planId) {
        return capacityService.compute(planId);
    }
}
