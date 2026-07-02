package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.Court;

/**
 * {@code court} CRUD via {@link JdbcClient} (ADR-004, spec §6.8/§7.8) — club-level, not scoped to
 * any season/activity plan.
 */
@Repository
public class CourtRepository {

    private final JdbcClient jdbcClient;

    public CourtRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Court> findAll() {
        return jdbcClient.sql("SELECT * FROM court ORDER BY venue_id, name, id")
                .query(CourtRepository::mapRow)
                .list();
    }

    public List<Court> findByVenueId(String venueId) {
        return jdbcClient.sql("SELECT * FROM court WHERE venue_id = :venueId ORDER BY name, id")
                .param("venueId", venueId)
                .query(CourtRepository::mapRow)
                .list();
    }

    public Optional<Court> findById(String id) {
        return jdbcClient.sql("SELECT * FROM court WHERE id = :id")
                .param("id", id)
                .query(CourtRepository::mapRow)
                .optional();
    }

    /** Used by block generation to find (or decide to create) the "Bana N" court for a venue. */
    public Optional<Court> findByVenueIdAndName(String venueId, String name) {
        return jdbcClient.sql("SELECT * FROM court WHERE venue_id = :venueId AND name = :name")
                .param("venueId", venueId)
                .param("name", name)
                .query(CourtRepository::mapRow)
                .optional();
    }

    public Court insert(Court court) {
        jdbcClient.sql("""
                        INSERT INTO court (id, venue_id, name, active, notes)
                        VALUES (:id, :venueId, :name, :active, :notes)
                        """)
                .param("id", court.id())
                .param("venueId", court.venueId())
                .param("name", court.name())
                .param("active", court.active() ? 1 : 0)
                .param("notes", court.notes())
                .update();
        return court;
    }

    public Court update(Court court) {
        jdbcClient.sql("""
                        UPDATE court SET name = :name, active = :active, notes = :notes WHERE id = :id
                        """)
                .param("id", court.id())
                .param("name", court.name())
                .param("active", court.active() ? 1 : 0)
                .param("notes", court.notes())
                .update();
        return court;
    }

    public boolean deleteById(String id) {
        int rows = jdbcClient.sql("DELETE FROM court WHERE id = :id").param("id", id).update();
        return rows > 0;
    }

    private static Court mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Court(
                rs.getString("id"),
                rs.getString("venue_id"),
                rs.getString("name"),
                rs.getInt("active") != 0,
                rs.getString("notes"));
    }
}
