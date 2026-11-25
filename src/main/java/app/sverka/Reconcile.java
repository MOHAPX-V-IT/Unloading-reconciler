package app.sverka;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
class Reconcile {
    private static final DateTimeFormatter DOT_DATE = DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+|[0-9]{1,3}(?: [0-9]{3})+)(?:[.,][0-9]+)?");

    enum State {
        CHANGED("Различия", "warning"), LEFT_ONLY("Только слева", "blue"), RIGHT_ONLY("Только справа", "violet"),
        DUPLICATE("Дубликат ключа", "danger"), MISSING_KEY("Пустой ключ", "danger"),
        INVALID_VALUE("Ошибка формата", "danger"), MATCH("Совпадение", "success");

        private final String label;
        private final String tone;
        State(String label, String tone) { this.label = label; this.tone = tone; }
        public String label() { return label; }
        public String tone() { return tone; }
    }

    enum Type {
        TEXT("Текст"), NUMBER("Число"), DATE("Дата");
        private final String label;
        Type(String label) { this.label = label; }
        public String label() { return label; }
    }

    record MatchColumn(int left, int right, Type type) {}
    record Rules(List<MatchColumn> keys, List<MatchColumn> compared, boolean ignoreCase, BigDecimal tolerance) {}
    record FieldDelta(String field, String left, String right, String error) {}
    record Item(String key, State state, List<Long> leftRows, List<Long> rightRows, List<FieldDelta> fields) {
        public String formattedLeftRows() { return rowNumbers(leftRows); }
        public String formattedRightRows() { return rowNumbers(rightRows); }
    }
    record Report(int leftRows, int rightRows, Map<State, Integer> counts, List<Item> items,
                  List<String> keys, List<String> compared, boolean ignoreCase, String tolerance) {
        public int count(State state) { return counts.getOrDefault(state, 0); }
        public int mismatches() { return items.size() - count(State.MATCH); }
    }

    private record Index(Map<List<String>, List<Tables.Record>> byKey, List<Tables.Record> missing) {}

    Report compare(Tables.Pair pair, Rules rules) {
        validate(pair, rules);
        Index left = index(pair.left(), rules.keys(), true, rules.ignoreCase());
        Index right = index(pair.right(), rules.keys(), false, rules.ignoreCase());
        var items = new ArrayList<Item>();
        var all = new LinkedHashSet<>(left.byKey().keySet());
        all.addAll(right.byKey().keySet());
        for (List<String> key : all) {
            var leftRecords = left.byKey().getOrDefault(key, List.of());
            var rightRecords = right.byKey().getOrDefault(key, List.of());
            String label = String.join(" / ", key);
            if (leftRecords.size() > 1 || rightRecords.size() > 1) {
                items.add(item(label, State.DUPLICATE, leftRecords, rightRecords, List.of()));
            } else if (leftRecords.isEmpty()) {
                items.add(item(label, State.RIGHT_ONLY, leftRecords, rightRecords, List.of()));
            } else if (rightRecords.isEmpty()) {
                items.add(item(label, State.LEFT_ONLY, leftRecords, rightRecords, List.of()));
            } else {
                List<FieldDelta> fields = differences(pair, rules, leftRecords.getFirst(), rightRecords.getFirst());
                State state = fields.isEmpty() ? State.MATCH
                    : fields.stream().anyMatch(field -> field.error() != null) ? State.INVALID_VALUE : State.CHANGED;
                items.add(item(label, state, leftRecords, rightRecords, fields));
            }
        }
        for (var row : left.missing()) items.add(item("", State.MISSING_KEY, List.of(row), List.of(), List.of()));
        for (var row : right.missing()) items.add(item("", State.MISSING_KEY, List.of(), List.of(row), List.of()));
        var counts = new EnumMap<State, Integer>(State.class);
        items.forEach(item -> counts.merge(item.state(), 1, Integer::sum));
        return new Report(pair.left().rows().size(), pair.right().rows().size(), Map.copyOf(counts), List.copyOf(items),
            rules.keys().stream().map(column -> label(pair, column)).toList(),
            rules.compared().stream().map(column -> label(pair, column) + " · " + column.type().label()).toList(),
            rules.ignoreCase(), rules.tolerance().toPlainString());
    }

    private static Index index(Tables.Table table, List<MatchColumn> keys, boolean left, boolean ignoreCase) {
        var byKey = new LinkedHashMap<List<String>, List<Tables.Record>>();
        var missing = new ArrayList<Tables.Record>();
        for (Tables.Record row : table.rows()) {
            List<String> key = keys.stream().map(column -> normalize(row.at(left ? column.left() : column.right()), ignoreCase)).toList();
            if (key.stream().anyMatch(String::isBlank)) missing.add(row);
            else byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        return new Index(byKey, missing);
    }

    private static List<FieldDelta> differences(Tables.Pair pair, Rules rules, Tables.Record left, Tables.Record right) {
        var result = new ArrayList<FieldDelta>();
        for (MatchColumn field : rules.compared()) {
            String leftValue = left.at(field.left());
            String rightValue = right.at(field.right());
            try {
                if (!equal(leftValue, rightValue, field.type(), rules)) {
                    result.add(new FieldDelta(label(pair, field), leftValue, rightValue, null));
                }
            } catch (NumberFormatException | DateTimeParseException exception) {
                result.add(new FieldDelta(label(pair, field), leftValue, rightValue,
                    field.type() == Type.DATE ? "Ожидается дата ГГГГ-ММ-ДД или ДД.ММ.ГГГГ." : "Ожидается число."));
            }
        }
        return List.copyOf(result);
    }

    private static boolean equal(String left, String right, Type type, Rules rules) {
        String a = normalize(left, rules.ignoreCase());
        String b = normalize(right, rules.ignoreCase());
        return switch (type) {
            case TEXT -> a.equals(b);
            case DATE -> {
                LocalDate dateA = a.isEmpty() ? null : date(a);
                LocalDate dateB = b.isEmpty() ? null : date(b);
                yield java.util.Objects.equals(dateA, dateB);
            }
            case NUMBER -> {
                BigDecimal numberA = a.isEmpty() ? null : number(a);
                BigDecimal numberB = b.isEmpty() ? null : number(b);
                yield numberA == null || numberB == null ? numberA == numberB
                    : numberA.subtract(numberB).abs().compareTo(rules.tolerance()) <= 0;
            }
        };
    }

    private static LocalDate date(String value) {
        return value.contains(".") ? LocalDate.parse(value, DOT_DATE) : LocalDate.parse(value);
    }

    static BigDecimal number(String value) {
        String clean = normalize(value, false).replace('\u2212', '-');
        if (clean.length() > 64 || !DECIMAL.matcher(clean).matches()) throw new NumberFormatException(value);
        return new BigDecimal(clean.replace(" ", "").replace(',', '.'));
    }

    private static String normalize(String value, boolean ignoreCase) {
        String clean = value.replace('\u00a0', ' ').replace('\u202f', ' ').strip();
        return ignoreCase ? clean.toLowerCase(Locale.ROOT) : clean;
    }

    private static void validate(Tables.Pair pair, Rules rules) {
        if (rules.keys().isEmpty() || rules.keys().size() > 2 || rules.compared().size() > 8
                || rules.tolerance().signum() < 0) throw new InputProblem("Проверьте ключи и допустимое отклонение.");
        for (var columns : List.of(rules.keys(), rules.compared())) {
            if (columns.stream().map(MatchColumn::left).distinct().count() != columns.size()
                || columns.stream().map(MatchColumn::right).distinct().count() != columns.size()) {
                throw new InputProblem("Каждый столбец можно выбрать один раз в ключе или списке сравнения.");
            }
            for (MatchColumn field : columns) {
                if (field.left() < 0 || field.right() < 0 || field.left() >= pair.left().headers().size()
                        || field.right() >= pair.right().headers().size()) throw new InputProblem("Столбец отсутствует в выгрузке.");
            }
        }
    }

    private static String label(Tables.Pair pair, MatchColumn field) {
        String left = pair.left().headers().get(field.left());
        String right = pair.right().headers().get(field.right());
        return left.equals(right) ? left : left + " / " + right;
    }

    private static Item item(String key, State state, List<Tables.Record> left, List<Tables.Record> right, List<FieldDelta> fields) {
        return new Item(key, state, left.stream().map(Tables.Record::number).toList(), right.stream().map(Tables.Record::number).toList(), fields);
    }

    private static String rowNumbers(List<Long> values) {
        return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
    }
}
