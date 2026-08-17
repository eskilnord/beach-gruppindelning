package se.klubb.groupplanner.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link ImportTemplateMappingCodec} round-trips every column index it's given, including the
 *  synthetic {@link ColumnMapping#BLOCK_GROUP_COLUMN_INDEX} (WP1) - so a saved template re-applies
 *  the block-group mapping on re-upload exactly like any real column. */
class ImportTemplateMappingCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void roundTripsANegativeColumnIndexKey() {
        List<ColumnMapping> mappings = List.of(
                new ColumnMapping(0, MappingTargetKind.DISPLAY_NAME, null),
                new ColumnMapping(1, MappingTargetKind.RANKING_POINTS, null),
                new ColumnMapping(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX, MappingTargetKind.PREVIOUS_GROUP_NAME, null));

        String json = ImportTemplateMappingCodec.encode(objectMapper, mappings);
        Map<Integer, String> decoded = ImportTemplateMappingCodec.decode(objectMapper, json);

        assertThat(decoded).containsEntry(0, "displayName");
        assertThat(decoded).containsEntry(1, "rankingPoints");
        assertThat(decoded).containsEntry(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX, "previousGroupName");
    }
}
