package app.sverka;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
class Tables {
    private static final int MAX_COLUMNS = 80;
    private static final int MAX_ROWS = 20_000;
    private static final int MAX_CELLS = 250_000;
    private static final int MAX_CELL_LENGTH = 2_000;

    record Record(long number, List<String> cells) {
        String at(int index) { return cells.get(index); }
    }

    record Table(String name, String source, List<String> headers, List<Record> rows, int cachedFormulas) {
        public List<Record> preview() { return rows.subList(0, Math.min(5, rows.size())); }
    }
    record Pair(Table left, Table right) {}

    Table read(MultipartFile file, int headerRow, String sheetName, String separator) {
        if (file.isEmpty()) throw new InputProblem("Выберите непустой файл.");
        if (headerRow < 1 || headerRow > 50) throw new InputProblem("Строка заголовков должна быть от 1 до 50.");
        String name = fileName(file);
        String lower = name.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".csv") || lower.endsWith(".tsv")) {
                return csv(name, decode(file.getBytes()), headerRow, delimiter(separator, lower));
            }
            if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                try (var in = file.getInputStream(); Workbook workbook = WorkbookFactory.create(in)) {
                    return spreadsheet(name, workbook, headerRow, sheetName);
                }
            }
        } catch (IOException | UncheckedIOException | IllegalArgumentException exception) {
            throw new InputProblem("Не удалось прочитать файл «" + name + "». Проверьте формат и убедитесь, что он не защищён паролем.");
        } catch (org.apache.poi.EncryptedDocumentException | org.apache.poi.ooxml.POIXMLException exception) {
            throw new InputProblem("Файл «" + name + "» защищён паролем, повреждён или слишком сложен.");
        }
        throw new InputProblem("Загрузите CSV, TSV, XLS или XLSX.");
    }

    Table readDemoCsv(String name, InputStream file) throws IOException {
        return csv(name, decode(file.readAllBytes()), 1, null);
    }

    private Table csv(String name, Decoded text, int headerRow, Character delimiter) throws IOException {
        char separator = delimiter == null ? detect(text.value(), headerRow) : delimiter;
        try (var parser = format(separator).parse(new StringReader(text.value()))) {
            List<String> headers = null;
            var rows = new ArrayList<Record>();
            for (CSVRecord record : parser) {
                if (record.getRecordNumber() < headerRow) continue;
                if (record.getRecordNumber() == headerRow) {
                    headers = headers(record.toList());
                    continue;
                }
                add(rows, headers, record.getRecordNumber(), record.toList());
            }
            if (headers == null) throw new InputProblem("Указанная строка заголовков отсутствует.");
            return new Table(name, text.encoding() + " · " + printable(separator), headers, List.copyOf(rows), 0);
        }
    }

    private Table spreadsheet(String name, Workbook workbook, int headerRow, String sheetName) {
        Sheet sheet = workbook.getSheet(sheetName == null ? "" : sheetName.strip());
        if (sheetName == null || sheetName.isBlank()) {
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                if (!workbook.isSheetHidden(index) && !workbook.isSheetVeryHidden(index)) {
                    sheet = workbook.getSheetAt(index);
                    break;
                }
            }
        }
        if (sheet == null) throw new InputProblem("Лист не найден. Укажите его название или оставьте поле пустым.");
        DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("ru-RU"));
        formatter.setUseCachedValuesForFormulaCells(true);
        Row header = sheet.getRow(headerRow - 1);
        if (header == null) throw new InputProblem("Указанная строка заголовков отсутствует.");
        List<String> headers = headers(cells(header, formatter, null));
        var rows = new ArrayList<Record>();
        int[] formulas = new int[1];
        for (Row row : sheet) {
            if (row.getRowNum() < headerRow) continue;
            add(rows, headers, row.getRowNum() + 1L, cells(row, formatter, formulas));
        }
        return new Table(name, "Лист «" + sheet.getSheetName() + "»", headers, List.copyOf(rows), formulas[0]);
    }

    private static List<String> cells(Row row, DataFormatter formatter, int[] formulas) {
        if (row.getLastCellNum() > MAX_COLUMNS) throw new InputProblem("Поддерживается до 80 столбцов.");
        var cells = new ArrayList<String>();
        for (int index = 0; index < row.getLastCellNum(); index++) {
            Cell cell = row.getCell(index);
            if (cell == null) { cells.add(""); continue; }
            CellType type = cell.getCellType();
            if (type == CellType.FORMULA) {
                type = cell.getCachedFormulaResultType();
                if (formulas != null) formulas[0]++;
            }
            if (type == CellType.ERROR) {
                throw new InputProblem("Ошибка Excel в строке " + (row.getRowNum() + 1) + ", столбец " + (index + 1) + ".");
            }
            String text = type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)
                ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                : formatter.formatCellValue(cell);
            cells.add(text);
        }
        return cells;
    }

    private static List<String> headers(List<String> raw) {
        int end = lastValue(raw);
        if (end == 0 || end > MAX_COLUMNS) throw new InputProblem("Заголовок должен содержать от 1 до 80 заполненных столбцов.");
        var names = new ArrayList<String>();
        var used = new HashSet<String>();
        for (int index = 0; index < end; index++) {
            String name = raw.get(index).strip();
            if (name.isEmpty() || name.length() > 120 || !used.add(name.toLowerCase(Locale.ROOT))) {
                throw new InputProblem("Проверьте название столбца " + (index + 1) + ". Заголовки должны быть заполнены и уникальны.");
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    private static void add(List<Record> rows, List<String> headers, long number, List<String> values) {
        int end = lastValue(values);
        if (end == 0) return;
        if (end > headers.size()) throw new InputProblem("В строке " + number + " больше значений, чем столбцов в заголовке.");
        if (rows.size() >= MAX_ROWS || (rows.size() + 1L) * headers.size() > MAX_CELLS) {
            throw new InputProblem("Лимит файла: 20 000 непустых строк и 250 000 ячеек.");
        }
        var cells = new ArrayList<String>(headers.size());
        for (int index = 0; index < headers.size(); index++) {
            String text = index < end ? values.get(index) : "";
            if (text.length() > MAX_CELL_LENGTH) throw new InputProblem("Значение длиннее 2000 символов в строке " + number + ".");
            cells.add(text);
        }
        rows.add(new Record(number, List.copyOf(cells)));
    }

    private static int lastValue(List<String> values) {
        int end = values.size();
        while (end > 0 && values.get(end - 1).isBlank()) end--;
        return end;
    }

    private record Decoded(String value, String encoding) {}

    private static Decoded decode(byte[] bytes) throws CharacterCodingException {
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)
                || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff))) {
            return new Decoded(StandardCharsets.UTF_16.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(), "UTF-16");
        }
        int offset = bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf ? 3 : 0;
        try {
            String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
            if (value.indexOf(0) >= 0) throw new InputProblem("Сохраните текстовый файл в UTF-8 или Windows-1251.");
            return new Decoded(value, "UTF-8");
        } catch (CharacterCodingException exception) {
            if (offset != 0) throw exception;
            return new Decoded(Charset.forName("windows-1251").newDecoder().decode(ByteBuffer.wrap(bytes)).toString(), "Windows-1251");
        }
    }

    private static Character delimiter(String option, String lower) {
        return switch (option) {
            case "SEMICOLON" -> ';';
            case "COMMA" -> ',';
            case "TAB" -> '\t';
            case "AUTO" -> lower.endsWith(".tsv") ? Character.valueOf('\t') : null;
            default -> throw new InputProblem("Некорректный разделитель.");
        };
    }

    private static char detect(String value, int header) {
        char selected = ';';
        int bestScore = 0;
        for (char candidate : new char[] {';', '\t', ','}) {
            try (var parser = format(candidate).parse(new StringReader(value))) {
                int columns = 0;
                int score = 0;
                for (CSVRecord line : parser) {
                    if (line.getRecordNumber() == header) columns = lastValue(line.toList());
                    if (line.getRecordNumber() > header + 5L) break;
                    if (columns > 1 && lastValue(line.toList()) == columns) score++;
                }
                if (score > bestScore) { bestScore = score; selected = candidate; }
            } catch (IOException | UncheckedIOException ignored) {
                // Try the remaining separators before reporting a malformed file.
            }
        }
        return selected;
    }

    private static CSVFormat format(char delimiter) {
        return CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setIgnoreEmptyLines(false).get();
    }

    private static String printable(char separator) {
        return separator == '\t' ? "табуляция" : "разделитель «" + separator + "»";
    }

    private static String fileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) return "выгрузка";
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (name.length() > 180) throw new InputProblem("Имя файла дольше 180 символов.");
        return name;
    }
}
