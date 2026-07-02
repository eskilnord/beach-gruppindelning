package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.Person;

/**
 * {@code person} CRUD via {@link JdbcClient} (ADR-004).
 */
@Repository
public class PersonRepository {

    private final JdbcClient jdbcClient;

    public PersonRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Person> findAll() {
        return jdbcClient.sql("SELECT * FROM person ORDER BY last_name, first_name, id")
                .query(PersonRepository::mapRow)
                .list();
    }

    public Optional<Person> findById(String id) {
        return jdbcClient.sql("SELECT * FROM person WHERE id = :id")
                .param("id", id)
                .query(PersonRepository::mapRow)
                .optional();
    }

    /**
     * Lookup by the UNIQUE {@code external_id} (source-system member id). Used by the import
     * wizard commit (M3) so a re-import of the same members merges onto the existing person rows
     * instead of tripping the UNIQUE constraint (M3 review finding 1).
     */
    public Optional<Person> findByExternalId(String externalId) {
        return jdbcClient.sql("SELECT * FROM person WHERE external_id = :externalId")
                .param("externalId", externalId)
                .query(PersonRepository::mapRow)
                .optional();
    }

    public Person insert(Person person) {
        jdbcClient.sql("""
                        INSERT INTO person
                            (id, first_name, last_name, display_name, email, phone, external_id,
                             can_be_participant, can_be_coach, notes, created_at, updated_at)
                        VALUES
                            (:id, :firstName, :lastName, :displayName, :email, :phone, :externalId,
                             :canBeParticipant, :canBeCoach, :notes, :createdAt, :updatedAt)
                        """)
                .param("id", person.id())
                .param("firstName", person.firstName())
                .param("lastName", person.lastName())
                .param("displayName", person.displayName())
                .param("email", person.email())
                .param("phone", person.phone())
                .param("externalId", person.externalId())
                .param("canBeParticipant", person.canBeParticipant() ? 1 : 0)
                .param("canBeCoach", person.canBeCoach() ? 1 : 0)
                .param("notes", person.notes())
                .param("createdAt", person.createdAt().toString())
                .param("updatedAt", person.updatedAt().toString())
                .update();
        return person;
    }

    public Person update(Person person) {
        jdbcClient.sql("""
                        UPDATE person
                        SET first_name = :firstName, last_name = :lastName, display_name = :displayName,
                            email = :email, phone = :phone, external_id = :externalId,
                            can_be_participant = :canBeParticipant, can_be_coach = :canBeCoach,
                            notes = :notes, updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("id", person.id())
                .param("firstName", person.firstName())
                .param("lastName", person.lastName())
                .param("displayName", person.displayName())
                .param("email", person.email())
                .param("phone", person.phone())
                .param("externalId", person.externalId())
                .param("canBeParticipant", person.canBeParticipant() ? 1 : 0)
                .param("canBeCoach", person.canBeCoach() ? 1 : 0)
                .param("notes", person.notes())
                .param("updatedAt", person.updatedAt().toString())
                .update();
        return person;
    }

    public boolean deleteById(String id) {
        int rows = jdbcClient.sql("DELETE FROM person WHERE id = :id").param("id", id).update();
        return rows > 0;
    }

    private static Person mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Person(
                rs.getString("id"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("external_id"),
                rs.getInt("can_be_participant") != 0,
                rs.getInt("can_be_coach") != 0,
                rs.getString("notes"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }
}
