package se.klubb.groupplanner.importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.ImportTemplate;
import se.klubb.groupplanner.importer.parse.ParsedSheet;
import se.klubb.groupplanner.importer.parse.ParsedWorkbook;
import se.klubb.groupplanner.importer.parse.WorkbookParsers;
import se.klubb.groupplanner.repo.ImportTemplateRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * In-memory store of {@link ImportSession}s (docs/design/02-product-data-ui.md §2: "server-side
 * stateful ImportSession (in-memory map keyed by session id ...)"). Sessions expire after {@link
 * #SESSION_TTL} of inactivity (M3 brief); expired sessions are purged lazily on every access rather
 * than via a background scheduler, which keeps this class simple and its behaviour deterministic in
 * tests (see the package-private {@link Clock}-injecting constructor).
 *
 * <p>Nothing here touches the database except the read-only template-suggestion lookup on upload
 * (spec §8.3 step 5/8: "auto-suggest template on upload when header_hash matches") - the session
 * itself is pure in-memory state until {@code ImportCommitService.commit(...)} runs.
 */
@Service
public class ImportSessionService {

    static final Duration SESSION_TTL = Duration.ofHours(1);

    private final ConcurrentHashMap<String, ImportSession> sessions = new ConcurrentHashMap<>();
    private final ImportTemplateRepository importTemplateRepository;
    private final Clock clock;

    @Autowired
    public ImportSessionService(ImportTemplateRepository importTemplateRepository) {
        this(importTemplateRepository, Clock.systemUTC());
    }

    /** Package-private, for tests that need to simulate session expiry without sleeping. */
    ImportSessionService(ImportTemplateRepository importTemplateRepository, Clock clock) {
        this.importTemplateRepository = importTemplateRepository;
        this.clock = clock;
    }

    public record SheetSummary(String name, int rowCount, String suggestedTemplateId, String suggestedTemplateName) {
    }

    public record CreatedSession(String sessionId, List<SheetSummary> sheets) {
    }

    public CreatedSession createSession(String activityPlanId, String fileName, InputStream inputStream) {
        pruneExpired();

        ParsedWorkbook workbook;
        try {
            workbook = WorkbookParsers.parse(fileName, inputStream);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read uploaded file: " + fileName, e);
        } catch (IllegalArgumentException e) {
            // Covers unsupported extensions AND corrupt/mislabeled xlsx content (POI's
            // NotOfficeXmlFileException family extends IllegalArgumentException; XlsxParser
            // rewraps POIXMLException the same way) - a user upload problem (400), not a 500
            // (M3 review finding 7).
            throw new BadRequestException(e.getMessage());
        }
        if (workbook.sheets().isEmpty()) {
            throw new BadRequestException("File contains no sheets: " + fileName);
        }

        Instant now = clock.instant();
        ImportSession session =
                new ImportSession(Uuid7.generate(), activityPlanId, fileName, workbook, now, now.plus(SESSION_TTL));

        List<SheetSummary> summaries = workbook.sheets().stream()
                .map(sheet -> summarize(session, sheet))
                .toList();

        sessions.put(session.id(), session);
        return new CreatedSession(session.id(), summaries);
    }

    private SheetSummary summarize(ImportSession session, ParsedSheet sheet) {
        int headerRowIndex = session.headerRowIndex(sheet.name());
        String headerHash = HeaderHash.computeForSheet(sheet, headerRowIndex);
        return importTemplateRepository.findFirstByHeaderHash(headerHash)
                .map(template -> {
                    session.setTemplateMatch(sheet.name(), new ImportSession.TemplateMatch(template.id(), template.name()));
                    return new SheetSummary(sheet.name(), sheet.rowCount(), template.id(), template.name());
                })
                .orElseGet(() -> new SheetSummary(sheet.name(), sheet.rowCount(), null, null));
    }

    public ImportSession get(String sessionId) {
        pruneExpired();
        ImportSession session = sessions.get(sessionId);
        if (session == null) {
            throw new NotFoundException("Import session not found or expired: " + sessionId);
        }
        session.renewExpiry(clock.instant().plus(SESSION_TTL));
        return session;
    }

    /**
     * Like {@link #get}, but additionally asserts the session was uploaded for {@code
     * activityPlanId} (400 on mismatch) - a session created for plan A must never preview into,
     * validate against, or commit into plan B (M3 review finding 4).
     */
    public ImportSession getForPlan(String sessionId, String activityPlanId) {
        ImportSession session = get(sessionId);
        if (!session.activityPlanId().equals(activityPlanId)) {
            throw new BadRequestException(
                    "Import session " + sessionId + " belongs to another activity plan");
        }
        return session;
    }

    public void delete(String sessionId) {
        sessions.remove(sessionId);
    }

    /** Package-private for tests: how many sessions are currently held, after purging expired ones. */
    int activeSessionCount() {
        pruneExpired();
        return sessions.size();
    }

    private void pruneExpired() {
        Instant now = clock.instant();
        sessions.values().removeIf(session -> session.isExpired(now));
    }
}
