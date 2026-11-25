package app.sverka;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class Workspace {
    private static final String STAGED = "staged-tables";
    private static final int PAGE_SIZE = 40;
    private final Tables tables;
    private final Reconcile reconcile;
    private final RunStore runs;
    private final DemoData demoData;
    private final boolean demo;

    record Staged(String id, Tables.Pair pair) {}
    record FieldPick(String left, String right, String type) {}
    record Options(String leftKey, String rightKey, String leftExtra, String rightExtra,
                   List<FieldPick> fields, boolean ignoreCase, String tolerance) {}

    Workspace(Tables tables, Reconcile reconcile, RunStore runs, DemoData demoData, @Value("${sverka.demo}") boolean demo) {
        this.tables = tables;
        this.reconcile = reconcile;
        this.runs = runs;
        this.demoData = demoData;
        this.demo = demo;
    }

    @ModelAttribute
    void context(Model model, Principal principal) {
        model.addAttribute("demo", demo);
        model.addAttribute("signedIn", principal != null);
        model.addAttribute("clock", this);
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }

    @GetMapping("/")
    String home(@RequestParam(defaultValue = "1") int page, Principal principal, Model model) {
        int current = Math.max(1, page);
        model.addAttribute("history", runs.history(principal.getName(), current));
        model.addAttribute("page", current);
        return "home";
    }

    @PostMapping("/upload")
    String upload(@RequestParam MultipartFile left, @RequestParam MultipartFile right,
                  @RequestParam(defaultValue = "1") int leftHeader, @RequestParam(defaultValue = "1") int rightHeader,
                  @RequestParam(defaultValue = "") String leftSheet, @RequestParam(defaultValue = "") String rightSheet,
                  @RequestParam(defaultValue = "AUTO") String leftSeparator, @RequestParam(defaultValue = "AUTO") String rightSeparator,
                  HttpSession session, RedirectAttributes flash) {
        boolean leftRead = false;
        try {
            var leftTable = tables.read(left, leftHeader, leftSheet, leftSeparator);
            leftRead = true;
            var rightTable = tables.read(right, rightHeader, rightSheet, rightSeparator);
            session.setAttribute(STAGED, new Staged(UUID.randomUUID().toString(), new Tables.Pair(leftTable, rightTable)));
            return "redirect:/configure";
        } catch (InputProblem problem) {
            flash.addFlashAttribute("warning", (leftRead ? "Правый файл. " : "Левый файл. ") + problem.getMessage());
            return "redirect:/";
        }
    }

    @PostMapping("/sample")
    String sample(HttpSession session) throws IOException {
        if (!demo) throw new InputProblem("Пример доступен в демонстрационном режиме.");
        session.setAttribute(STAGED, new Staged(UUID.randomUUID().toString(), demoData.orders()));
        return "redirect:/configure";
    }

    @GetMapping("/configure")
    String configure(HttpSession session, Model model) {
        var staged = (Staged) session.getAttribute(STAGED);
        return staged == null ? "redirect:/" : configureModel(staged, defaults(staged.pair()), model);
    }

    @PostMapping("/compare")
    String compare(@RequestParam String pairId, @RequestParam String leftKey, @RequestParam String rightKey,
                   @RequestParam(defaultValue = "") String leftExtra, @RequestParam(defaultValue = "") String rightExtra,
                   @RequestParam("leftField") String[] leftFields, @RequestParam("rightField") String[] rightFields,
                   @RequestParam("fieldType") String[] fieldTypes, @RequestParam(defaultValue = "false") boolean ignoreCase,
                   @RequestParam(defaultValue = "0") String tolerance, Principal principal, HttpSession session, Model model) {
        var staged = (Staged) session.getAttribute(STAGED);
        if (staged == null || !staged.id().equals(pairId)) throw new InputProblem("Выгрузки изменились. Откройте текущую настройку.");
        if (leftFields.length > 8 || leftFields.length != rightFields.length || leftFields.length != fieldTypes.length) {
            throw new InputProblem("Проверьте пары сравниваемых столбцов.");
        }
        var fields = new ArrayList<FieldPick>();
        for (int index = 0; index < leftFields.length; index++) {
            fields.add(new FieldPick(leftFields[index], rightFields[index], fieldTypes[index]));
        }
        var options = new Options(leftKey, rightKey, leftExtra, rightExtra, List.copyOf(fields), ignoreCase, tolerance);
        try {
            var report = reconcile.compare(staged.pair(), rules(options));
            String id = runs.save(principal.getName(), staged.pair(), report);
            return "redirect:/reports/" + id;
        } catch (InputProblem problem) {
            model.addAttribute("warning", problem.getMessage());
            return configureModel(staged, options, model);
        }
    }

    @GetMapping("/reports/{id}")
    String report(@PathVariable String id, @RequestParam(defaultValue = "problems") String filter,
                  @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "") String q,
                  Principal principal, Model model) {
        RunStore.Run run = runs.read(principal.getName(), id);
        String query = q.strip().toLowerCase(Locale.ROOT);
        var items = run.report().items().stream().filter(item -> matchesFilter(item, filter))
            .filter(item -> query.isEmpty() || item.key().toLowerCase(Locale.ROOT).contains(query)).toList();
        int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(1, Math.min(pages, page));
        int start = (current - 1) * PAGE_SIZE;
        model.addAttribute("run", run);
        model.addAttribute("items", items.subList(start, Math.min(start + PAGE_SIZE, items.size())));
        model.addAttribute("total", items.size());
        model.addAttribute("states", Reconcile.State.values());
        model.addAttribute("page", current);
        model.addAttribute("pages", pages);
        model.addAttribute("filter", filter);
        model.addAttribute("q", q);
        return "report";
    }

    @GetMapping(value = "/reports/{id}/report.csv", produces = "text/csv;charset=UTF-8")
    ResponseEntity<String> export(@PathVariable String id, @RequestParam(defaultValue = "problems") String filter, Principal principal) {
        RunStore.Run run = runs.read(principal.getName(), id);
        var csv = new StringBuilder("\ufeffКлюч;Статус;Строки слева;Строки справа;Поле;Слева;Справа;Комментарий\r\n");
        run.report().items().stream().filter(item -> matchesFilter(item, filter)).forEach(item -> {
            var fields = item.fields().isEmpty() ? List.of(new Reconcile.FieldDelta("", "", "", "")) : item.fields();
            for (var field : fields) {
                csv.append(List.of(item.key(), item.state().label(), item.formattedLeftRows(), item.formattedRightRows(),
                    field.field(), field.left(), field.right(), field.error() == null ? "" : field.error()).stream()
                    .map(Workspace::csvCell).collect(Collectors.joining(";"))).append("\r\n");
            }
        });
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sverka.csv\"").body(csv.toString());
    }

    @PostMapping("/reports/{id}/delete")
    String delete(@PathVariable String id, Principal principal, RedirectAttributes flash) {
        runs.remove(principal.getName(), id);
        flash.addFlashAttribute("notice", "Отчёт удалён из истории.");
        return "redirect:/";
    }

    @ExceptionHandler(InputProblem.class)
    String problem(InputProblem exception, Model model) {
        model.addAttribute("message", exception.getMessage());
        return "problem";
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    String oversized(Model model) {
        model.addAttribute("message", "Размер каждого файла должен быть до 6 МБ.");
        return "problem";
    }

    public String formatTime(OffsetDateTime date) {
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Moscow")).format(date);
    }

    private static String configureModel(Staged staged, Options options, Model model) {
        model.addAttribute("draft", staged);
        model.addAttribute("previews", List.of(staged.pair().left(), staged.pair().right()));
        model.addAttribute("options", options);
        model.addAttribute("types", Reconcile.Type.values());
        return "configure";
    }

    static Options defaults(Tables.Pair pair) {
        var left = pair.left().headers();
        var right = pair.right().headers();
        int start = IntStream.range(0, left.size()).filter(index -> right.stream().anyMatch(left.get(index)::equalsIgnoreCase))
            .findFirst().orElse(0);
        int other = IntStream.range(0, right.size()).filter(index -> left.get(start).equalsIgnoreCase(right.get(index))).findFirst().orElse(0);
        var fields = new ArrayList<FieldPick>(8);
        for (int i = 0; i < left.size() && fields.size() < 4; i++) {
            if (i == start) continue;
            String name = left.get(i);
            var rightIndex = IntStream.range(0, right.size()).filter(j -> j != other && right.get(j).equalsIgnoreCase(name)).findFirst();
            if (rightIndex.isEmpty()) continue;
            String normalized = name.toLowerCase(Locale.ROOT);
            var type = normalized.matches(".*(сумм|цен[аы]|стоимост|amount|total|quantity|количество).*")
                ? Reconcile.Type.NUMBER : normalized.matches(".*(дата|date).*") ? Reconcile.Type.DATE : Reconcile.Type.TEXT;
            fields.add(new FieldPick(String.valueOf(i), String.valueOf(rightIndex.getAsInt()), type.name()));
        }
        while (fields.size() < 8) fields.add(new FieldPick("", "", "TEXT"));
        return new Options(String.valueOf(start), String.valueOf(other), "", "", List.copyOf(fields), false, "0");
    }

    static Reconcile.Rules rules(Options options) {
        var keys = new ArrayList<Reconcile.MatchColumn>();
        keys.add(columns(options.leftKey(), options.rightKey(), "TEXT"));
        if (!options.leftExtra().isEmpty() || !options.rightExtra().isEmpty()) {
            keys.add(columns(options.leftExtra(), options.rightExtra(), "TEXT"));
        }
        List<Reconcile.MatchColumn> fields = options.fields().stream()
            .filter(field -> !field.left().isEmpty() || !field.right().isEmpty())
            .map(field -> columns(field.left(), field.right(), field.type())).toList();
        BigDecimal tolerance;
        try {
            tolerance = options.tolerance().isBlank() ? BigDecimal.ZERO : Reconcile.number(options.tolerance());
        } catch (NumberFormatException exception) {
            throw new InputProblem("Укажите числовое отклонение, например 0,01.");
        }
        return new Reconcile.Rules(List.copyOf(keys), fields, options.ignoreCase(), tolerance);
    }

    private static Reconcile.MatchColumn columns(String left, String right, String type) {
        try {
            return new Reconcile.MatchColumn(Integer.parseInt(left), Integer.parseInt(right), Reconcile.Type.valueOf(type));
        } catch (IllegalArgumentException exception) {
            throw new InputProblem("В каждой выбранной паре укажите оба столбца.");
        }
    }

    private static boolean matchesFilter(Reconcile.Item item, String filter) {
        return "all".equals(filter) || ("problems".equals(filter) && item.state() != Reconcile.State.MATCH)
            || item.state().name().equals(filter);
    }

    private static String csvCell(String value) {
        String probe = value.stripLeading();
        if (!probe.isEmpty() && "=+-@".indexOf(probe.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
