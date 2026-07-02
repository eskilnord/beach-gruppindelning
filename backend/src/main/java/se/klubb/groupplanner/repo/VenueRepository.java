package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.Venue;

/**
 * {@code venue} CRUD via {@link JdbcClient} (ADR-004, spec §7.7) — club-level, not scoped to any
 * season/activity plan.
 */
@Repository
public class VenueRepository {

    private final JdbcClient jdbcClient;

    public VenueRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Venue> findAll() {
        return jdbcClient.sql("SELECT * FROM venue ORDER BY id")
                .query(VenueRepository::mapRow)
                .list();
    }

    /** The first (oldest, by UUIDv7 sort order) venue, used as the implicit default. */
    public Optional<Venue> findFirst() {
        return jdbcClient.sql("SELECT * FROM venue ORDER BY id LIMIT 1")
                .query(VenueRepository::mapRow)
                .optional();
    }

    public Optional<Venue> findById(String id) {
        return jdbcClient.sql("SELECT * FROM venue WHERE id = :id")
                .param("id", id)
                .query(VenueRepository::mapRow)
                .optional();
    }

    public Venue insert(Venue venue) {
        jdbcClient.sql("INSERT INTO venue (id, name, notes) VALUES (:id, :name, :notes)")
                .param("id", venue.id())
                .param("name", venue.name())
                .param("notes", venue.notes())
                .update();
        return venue;
    }

    public Venue update(Venue venue) {
        jdbcClient.sql("UPDATE venue SET name = :name, notes = :notes WHERE id = :id")
                .param("id", venue.id())
                .param("name", venue.name())
                .param("notes", venue.notes())
                .update();
        return venue;
    }

    public boolean deleteById(String id) {
        int rows = jdbcClient.sql("DELETE FROM venue WHERE id = :id").param("id", id).update();
        return rows > 0;
    }

    private static Venue mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Venue(rs.getString("id"), rs.getString("name"), rs.getString("notes"));
    }
}
