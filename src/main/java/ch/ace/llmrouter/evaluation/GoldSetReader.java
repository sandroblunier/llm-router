package ch.ace.llmrouter.evaluation;

import ch.ace.llmrouter.evaluation.EvaluationModel.GoldLabels;
import ch.ace.llmrouter.evaluation.EvaluationModel.GoldRow;
import ch.ace.llmrouter.model.Complexity;
import ch.ace.llmrouter.model.PrivacyLevel;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a gold-set Excel file in the annotation_pilot layout: sheet "Beschriftung", header row,
 * required columns nr and anfrage, optional label columns. Mirrors frontend gold-set.ts.
 */
public final class GoldSetReader {

    public static final String SHEET = "Beschriftung";

    private GoldSetReader() {
    }

    public static class GoldSetException extends RuntimeException {
        public GoldSetException(String message) {
            super(message);
        }
    }

    public static List<GoldRow> read(InputStream in) {
        try (Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheet(SHEET);
            if (sheet == null) {
                throw new GoldSetException("Blatt «" + SHEET + "» fehlt.");
            }
            return parse(sheet);
        } catch (IOException | RuntimeException e) {
            if (e instanceof GoldSetException g) throw g;
            throw new GoldSetException("Datei ist keine lesbare .xlsx-Datei: " + e.getMessage());
        }
    }

    static List<GoldRow> parse(Sheet sheet) {
        DataFormatter fmt = new DataFormatter(Locale.ROOT);
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        if (headerRow == null) {
            throw new GoldSetException("Blatt «" + SHEET + "» ist leer.");
        }
        Map<String, Integer> header = new HashMap<>();
        for (Cell c : headerRow) {
            header.put(fmt.formatCellValue(c).trim().toLowerCase(Locale.ROOT), c.getColumnIndex());
        }
        for (String required : List.of("nr", "anfrage")) {
            if (!header.containsKey(required)) {
                throw new GoldSetException("Spalte «" + required + "» fehlt im Blatt «" + SHEET + "».");
            }
        }
        List<GoldRow> rows = new ArrayList<>();
        for (int i = headerRow.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            String nr = text(fmt, row, header.get("nr"));
            String anfrage = text(fmt, row, header.get("anfrage"));
            if (nr.isEmpty() || anfrage.isEmpty()) continue;
            rows.add(new GoldRow(nr, anfrage, labels(fmt, row, header)));
        }
        if (rows.isEmpty()) {
            throw new GoldSetException("Keine Zeilen mit «nr» und «anfrage» gefunden.");
        }
        return rows;
    }

    static GoldLabels labels(DataFormatter fmt, Row row, Map<String, Integer> header) {
        String level = get(fmt, row, header, "datenschutzstufe").toUpperCase(Locale.ROOT);
        String complexity = get(fmt, row, header, "komplexitaet").toLowerCase(Locale.ROOT);
        String pii = get(fmt, row, header, "pii_arten");
        String grenzfall = get(fmt, row, header, "grenzfall").toLowerCase(Locale.ROOT);

        List<String> piiArten = null;
        if (header.containsKey("pii_arten") && (!pii.isEmpty() || !level.isEmpty())) {
            piiArten = pii.isEmpty() || pii.equalsIgnoreCase("keine") ? List.of()
                    : Arrays.stream(pii.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        return new GoldLabels(
                isPrivacyLevel(level) ? level : null,
                piiArten,
                isComplexity(complexity) ? complexity : null,
                grenzfall.equals("ja") ? Boolean.TRUE : grenzfall.equals("nein") ? Boolean.FALSE : null);
    }

    private static boolean isPrivacyLevel(String s) {
        return Arrays.stream(PrivacyLevel.values()).anyMatch(l -> l.name().equals(s));
    }

    private static boolean isComplexity(String s) {
        return Arrays.stream(Complexity.values()).anyMatch(c -> c.name().equals(s));
    }

    private static String get(DataFormatter fmt, Row row, Map<String, Integer> header, String column) {
        Integer i = header.get(column);
        return i == null ? "" : text(fmt, row, i);
    }

    private static String text(DataFormatter fmt, Row row, int column) {
        Cell c = row.getCell(column);
        return c == null ? "" : fmt.formatCellValue(c).trim();
    }
}
