package se.klubb.groupplanner.explain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.explain.ExplanationDtos.GroupSizeChangeView;
import se.klubb.groupplanner.explain.ExplanationDtos.LevelSpreadChangeView;
import se.klubb.groupplanner.explain.ExplanationDtos.WhatIfMoveResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.WhatIfWhyNotResponse;
import se.klubb.groupplanner.explain.ExplanationService.RunContext;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;

/**
 * What-if move evaluation (task item 5, docs/design/04-solver.md §12, kravspec §18). Both endpoints
 * are thin wrappers over the SAME {@link MoveProbe#evaluate} + {@link ExplanationService} plumbing
 * the person-level {@code alternatives[]} list uses — "why is Grupp C rejected" must give the
 * identical answer whether it came from the automatic candidate-set or a manual "Testa flytt"/"Varför
 * inte grupp X?" click (design §11.5's own framing: why-not is "the guaranteed answer path for
 * arbitrary counterfactuals", built on the exact same machinery, never a separate code path).
 */
@Service
public class WhatIfService {

    private static final List<String> SUGGESTED_ACTIONS = List.of("KEEP", "MOVE_ANYWAY", "LOCK_AND_RESOLVE");

    private final ExplanationService explanationService;
    private final MoveProbe moveProbe;

    public WhatIfService(ExplanationService explanationService, MoveProbe moveProbe) {
        this.explanationService = explanationService;
        this.moveProbe = moveProbe;
    }

    /** {@code POST .../whatif/move} (design §14.2): full consequence report for moving {@code
     * participantProfileId} to {@code targetGroupId} ({@code null}/blank = move to the waitlist). */
    public WhatIfMoveResponse move(String planId, String runId, String participantProfileId, String targetGroupId) {
        RunContext ctx = explanationService.loadContext(planId, runId);
        PlayerAssignment target = explanationService.requireTarget(ctx, participantProfileId);
        Group targetGroup = (targetGroupId == null || targetGroupId.isBlank())
                ? null
                : explanationService.requireGroupFact(ctx, targetGroupId);

        MoveProbe.Result result = moveProbe.evaluate(ctx.solution(), ctx.baseAnalysis(), target, targetGroup, ctx.index());

        List<GroupSizeChangeView> sizeChanges = new ArrayList<>();
        List<LevelSpreadChangeView> spreadChanges = new ArrayList<>();
        addImpact(ctx, result.fromImpact(), sizeChanges, spreadChanges);
        addImpact(ctx, result.toImpact(), sizeChanges, spreadChanges);

        return new WhatIfMoveResponse(
                runId, ctx.run().planRevision(), ctx.currentRevision(), ctx.stale(),
                ExplanationService.toScoreDeltaView(result.scoreDelta()), result.wouldBreakHard(), sizeChanges, spreadChanges,
                result.newlyBroken(), result.newlyFixed(), SUGGESTED_ACTIONS);
    }

    private void addImpact(
            RunContext ctx, MoveProbe.GroupImpact impact, List<GroupSizeChangeView> sizeChanges, List<LevelSpreadChangeView> spreadChanges) {
        if (impact == null) {
            return;
        }
        String groupDbId = explanationService.groupDbId(ctx, impact.group());
        sizeChanges.add(new GroupSizeChangeView(groupDbId, impact.group().name(), impact.sizeBefore(), impact.sizeAfter(), impact.group().maxSize()));
        spreadChanges.add(new LevelSpreadChangeView(groupDbId, impact.group().name(), impact.spreadBefore(), impact.spreadAfter()));
    }

    /** {@code POST .../whatif/why-not} (design §11.5/§14.2): one what-if diff for ANY group the user
     * picks — not restricted to the automatic candidate set — returned in the exact same {@code
     * AlternativeGroupView} shape as {@code alternatives[i]} in the person-level response. */
    public WhatIfWhyNotResponse whyNot(String planId, String runId, String participantProfileId, String groupId) {
        RunContext ctx = explanationService.loadContext(planId, runId);
        PlayerAssignment target = explanationService.requireTarget(ctx, participantProfileId);
        Group group = explanationService.requireGroupFact(ctx, groupId);

        MoveProbe.Result result = moveProbe.evaluate(ctx.solution(), ctx.baseAnalysis(), target, group, ctx.index());
        var alternative = explanationService.toAlternativeView(ctx, group, result, new LinkedHashSet<>());

        return new WhatIfWhyNotResponse(runId, ctx.run().planRevision(), ctx.currentRevision(), ctx.stale(), alternative);
    }
}
