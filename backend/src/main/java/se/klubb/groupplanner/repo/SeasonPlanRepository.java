package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.SeasonPlan;

/**
 * {@code season_plan} CRUD via {@link JdbcClient} (ADR-004: explicit SQL and row mappers, no
 * JPA/Hibernate).
 */
@Repository
public class SeasonPlanRepository {

    private final JdbcClient jdbcClient;

    public SeasonPlanRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SeasonPlan> findAll() {
        return jdbcClient.sql("SELECT * FROM season_plan ORDER BY created_at, id")
                .query(SeasonPlanRepository::mapRow)
                .list();
    }

    public Optional<SeasonPlan> findById(String id) {
        return jdbcClient.sql("SELECT * FROM season_plan WHERE id = :id")
                .param("id", id)
                .query(SeasonPlanRepository::mapRow)
                .optional();
    }

    public SeasonPlan insert(SeasonPlan seasonPlan) {
        jdbcClient.sql("""
                        INSERT INTO season_plan (id, name, start_date, end_date, status, created_at, updated_at)
                        VALUES (:id, :name, :startDate, :endDate, :status, :createdAt, :updatedAt)
                        """)
                .param("id", seasonPlan.id())
                .param("name", seasonPlan.name())
                .param("startDate", toText(seasonPlan.startDate()))
                .param("endDate", toText(seasonPlan.endDate()))
                .param("status", seasonPlan.status())
                .param("createdAt", seasonPlan.createdAt().toString())
                .param("updatedAt", seasonPlan.updatedAt().toString())
                .update();
        return seasonPlan;
    }

    public SeasonPlan update(SeasonPlan seasonPlan) {
        jdbcClient.sql("""
                        UPDATE season_plan
                        SET name = :name, start_date = :startDate, end_date = :endDate,
                            status = :status, updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("id", seasonPlan.id())
                .param("name", seasonPlan.name())
                .param("startDate", toText(seasonPlan.startDate()))
                .param("endDate", toText(seasonPlan.endDate()))
                .param("status", seasonPlan.status())
                .param("updatedAt", seasonPlan.updatedAt().toString())
                .update();
        return seasonPlan;
    }

    public boolean deleteById(String id) {
        int rows = jdbcClient.sql("DELETE FROM season_plan WHERE id = :id").param("id", id).update();
        return rows > 0;
    }

    private static String toText(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static SeasonPlan mapRow(ResultSet rs, int rowNum) throws SQLException {
        String startDate = rs.getString("start_date");
        String endDate = rs.getString("end_date");
        return new SeasonPlan(
                rs.getString("id"),
                rs.getString("name"),
                startDate == null ? null : LocalDate.parse(startDate),
                endDate == null ? null : LocalDate.parse(endDate),
                rs.getString("status"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }
}
