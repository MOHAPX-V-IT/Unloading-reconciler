package app.sverka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RunStore {
    static final int PAGE_SIZE = 15;
    record Summary(String id, OffsetDateTime time, String left, String right, int mismatches) {}
    record Run(Summary summary, Reconcile.Report report) {}
    record Page(List<Summary> summaries, boolean next) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    RunStore(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    String save(String owner, Tables.Pair pair, Reconcile.Report report) {
        String id = UUID.randomUUID().toString();
        try {
            String body = json.writeValueAsString(report);
            if (body.length() > 12_000_000) throw new InputProblem("Отчёт слишком большой. Разделите исходные выгрузки на части.");
            jdbc.update("""
                INSERT INTO reconciliation_runs(id, owner, created_at, left_name, right_name, differences, report_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, owner, OffsetDateTime.now(clock), pair.left().name(), pair.right().name(), report.mismatches(), body);
            return id;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode a report", exception);
        }
    }

    Page history(String owner, int page) {
        var items = jdbc.query("""
            SELECT id, created_at, left_name, right_name, differences FROM reconciliation_runs
            WHERE owner = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?
            """, (row, index) -> summary(row), owner, PAGE_SIZE + 1, PAGE_SIZE * (long) (page - 1));
        return new Page(items.subList(0, Math.min(items.size(), PAGE_SIZE)), items.size() > PAGE_SIZE);
    }

    Run read(String owner, String id) {
        return jdbc.query("SELECT * FROM reconciliation_runs WHERE id = ? AND owner = ?", (row, index) -> {
            try {
                return new Run(summary(row), json.readValue(row.getString("report_json"), Reconcile.Report.class));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Could not decode a report", exception);
            }
        }, id, owner).stream().findFirst().orElseThrow(() -> new InputProblem("Отчёт не найден."));
    }

    void remove(String owner, String id) {
        if (jdbc.update("DELETE FROM reconciliation_runs WHERE id = ? AND owner = ?", id, owner) != 1) {
            throw new InputProblem("Отчёт не найден.");
        }
    }

    private static Summary summary(ResultSet row) throws SQLException {
        return new Summary(row.getString("id"), row.getObject("created_at", OffsetDateTime.class),
            row.getString("left_name"), row.getString("right_name"), row.getInt("differences"));
    }
}
