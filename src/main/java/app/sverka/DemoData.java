package app.sverka;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class DemoData implements ApplicationRunner {
    private static final String SEED = "example-reports-1";
    private final Tables tables;
    private final Reconcile reconcile;
    private final RunStore runs;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    DemoData(Tables tables, Reconcile reconcile, RunStore runs, JdbcTemplate jdbc,
             @Value("${sverka.demo}") boolean enabled) {
        this.tables = tables;
        this.reconcile = reconcile;
        this.runs = runs;
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Override
    @Transactional(rollbackFor = IOException.class)
    public void run(ApplicationArguments args) throws IOException {
        if (!enabled || jdbc.queryForObject("SELECT COUNT(*) FROM demo_imports WHERE name = ?", Integer.class, SEED) > 0) {
            return;
        }
        save(new Tables.Pair(csv("stock-1c.csv", "Остатки из 1С.csv"),
            csv("stock-warehouse.csv", "Пересчёт склада.csv")));
        save(orders());
        jdbc.update("INSERT INTO demo_imports(name) VALUES (?)", SEED);
    }

    Tables.Pair orders() throws IOException {
        return new Tables.Pair(csv("orders-1c.csv", "Счета из 1С.csv"), csv("orders-crm.csv", "Счета из CRM.csv"));
    }

    private Tables.Table csv(String file, String title) throws IOException {
        try (var input = new ClassPathResource("demo/" + file).getInputStream()) {
            return tables.readDemoCsv(title, input);
        }
    }

    private void save(Tables.Pair pair) {
        var report = reconcile.compare(pair, Workspace.rules(Workspace.defaults(pair)));
        runs.save(Security.DEMO_LOGIN, pair, report);
    }
}
