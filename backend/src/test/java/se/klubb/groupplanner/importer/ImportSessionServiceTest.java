package se.klubb.groupplanner.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.ImportTemplate;
import se.klubb.groupplanner.repo.ImportTemplateRepository;

/** Unit tests for the in-memory session store: creation, expiry, and template auto-suggestion. */
class ImportSessionServiceTest {

    private static final byte[] SIMPLE_CSV = "Förnamn,Efternamn\nNils,Åström\n".getBytes(StandardCharsets.UTF_8);

    private final ImportTemplateRepository importTemplateRepository = mock(ImportTemplateRepository.class);
    private ImportAnalysisService importAnalysisService;

    @BeforeEach
    void stubAnalysis() {
        importAnalysisService = mock(ImportAnalysisService.class);
        when(importAnalysisService.analyzeAndPrepare(any(), anyString())).thenAnswer(invocation -> {
            ImportSession session = invocation.getArgument(0);
            String sheet = session.workbook().sheets().get(0).name();
            session.setHeaderRow(sheet, session.headerRowIndex(sheet));
            ImportAnalysis analysis = new ImportAnalysis(
                    false, sheet, 0, "test", 0.5, false, null, null, List.of(), 0, 0, 0, 0, 0, List.of());
            session.setAnalysis(analysis);
            return analysis;
        });
    }

    private ImportSessionService newService(Clock clock) {
        return new ImportSessionService(importTemplateRepository, importAnalysisService, clock);
    }

    @Test
    void createSessionReturnsSheetSummariesWithRowCounts() {
        when(importTemplateRepository.findFirstByHeaderHash(anyString())).thenReturn(Optional.empty());
        ImportSessionService service = newService(Clock.systemUTC());

        ImportSessionService.CreatedSession created = service.createSession("plan-1", "test.csv", new ByteArrayInputStream(SIMPLE_CSV));

        assertThat(created.sessionId()).isNotBlank();
        assertThat(created.sheets()).hasSize(1);
        assertThat(created.sheets().get(0).rowCount()).isEqualTo(2);
        assertThat(created.analysis()).isNotNull();
        assertThat(service.get(created.sessionId())).isNotNull();
    }

    @Test
    void unknownSessionIdThrowsNotFound() {
        ImportSessionService service = newService(Clock.systemUTC());

        assertThatThrownBy(() -> service.get("does-not-exist")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void sessionExpiresAfterOneHourOfInactivity() {
        when(importTemplateRepository.findFirstByHeaderHash(anyString())).thenReturn(Optional.empty());
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ImportSessionService service = newService(clock);

        ImportSessionService.CreatedSession created = service.createSession("plan-1", "test.csv", new ByteArrayInputStream(SIMPLE_CSV));
        assertThat(service.get(created.sessionId())).isNotNull();

        clock.advance(Duration.ofMinutes(61));

        assertThatThrownBy(() -> service.get(created.sessionId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void accessingASessionRenewsItsExpiry() {
        when(importTemplateRepository.findFirstByHeaderHash(anyString())).thenReturn(Optional.empty());
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ImportSessionService service = newService(clock);

        ImportSessionService.CreatedSession created = service.createSession("plan-1", "test.csv", new ByteArrayInputStream(SIMPLE_CSV));

        clock.advance(Duration.ofMinutes(45));
        service.get(created.sessionId()); // Renews expiry from this point.
        clock.advance(Duration.ofMinutes(45)); // 90 min since creation, but only 45 since last access.

        assertThat(service.get(created.sessionId())).isNotNull();
    }

    @Test
    void suggestsMatchingTemplateByHeaderHashOnUpload() {
        String expectedHash = HeaderHash.compute(java.util.List.of("förnamn", "efternamn"));
        ImportTemplate template = new ImportTemplate("tmpl-1", "Standardmall", expectedHash, "{}", Instant.now());
        when(importTemplateRepository.findFirstByHeaderHash(expectedHash)).thenReturn(Optional.of(template));

        ImportSessionService service = newService(Clock.systemUTC());
        ImportSessionService.CreatedSession created = service.createSession("plan-1", "test.csv", new ByteArrayInputStream(SIMPLE_CSV));

        assertThat(created.sheets().get(0).suggestedTemplateId()).isEqualTo("tmpl-1");
        assertThat(created.sheets().get(0).suggestedTemplateName()).isEqualTo("Standardmall");
    }

    @Test
    void sessionIsBoundToItsPlanAndRejectsOtherPlans() {
        when(importTemplateRepository.findFirstByHeaderHash(anyString())).thenReturn(Optional.empty());
        ImportSessionService service = newService(Clock.systemUTC());

        ImportSessionService.CreatedSession created =
                service.createSession("plan-1", "test.csv", new ByteArrayInputStream(SIMPLE_CSV));

        // The owning plan works; any other plan is rejected with 400 (M3 review finding 4).
        assertThat(service.getForPlan(created.sessionId(), "plan-1")).isNotNull();
        assertThatThrownBy(() -> service.getForPlan(created.sessionId(), "plan-2"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("another activity plan");
    }

    @Test
    void textFileRenamedToXlsxIsRejectedAsBadRequestNotServerError() {
        ImportSessionService service = newService(Clock.systemUTC());
        byte[] notAnXlsx = "Detta är bara en textfil, inte en arbetsbok.".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.createSession("plan-1", "fejk.xlsx", new ByteArrayInputStream(notAnXlsx)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void corruptZipRenamedToXlsxIsRejectedAsBadRequestNotServerError() {
        ImportSessionService service = newService(Clock.systemUTC());
        // A real zip header followed by garbage - gets past the "is it a zip?" sniff and dies
        // deeper inside POI (POIXMLException territory rather than NotOfficeXmlFileException).
        byte[] corruptZip = new byte[64];
        corruptZip[0] = 'P';
        corruptZip[1] = 'K';
        corruptZip[2] = 3;
        corruptZip[3] = 4;

        assertThatThrownBy(() -> service.createSession("plan-1", "trasig.xlsx", new ByteArrayInputStream(corruptZip)))
                .isInstanceOfAny(BadRequestException.class, java.io.UncheckedIOException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
