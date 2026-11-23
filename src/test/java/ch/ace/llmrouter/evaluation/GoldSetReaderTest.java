package ch.ace.llmrouter.evaluation;

import ch.ace.llmrouter.evaluation.EvaluationModel.GoldRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldSetReaderTest {

    /** Builds an xlsx in memory: first row header, then data rows (null = empty cell, Double = numeric). */
    static byte[] xlsx(String sheetName, Object[]... rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet(sheetName);
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v instanceof Double d) row.createCell(c).setCellValue(d);
                    else if (v != null) row.createCell(c).setCellValue(v.toString());
                }
            }
            wb.createSheet("Richtlinie");
            wb.write(out);
            return out.toByteArray();
        }
    }

    static final Object[] HEADER = {"nr", "anfrage", "datenschutzstufe", "pii_arten", "komplexitaet", "grenzfall", "bemerkung"};

    @Test
    void readsLabelledAndUnlabelledRows() throws IOException {
        List<GoldRow> rows = GoldSetReader.read(new ByteArrayInputStream(xlsx("Beschriftung", HEADER,
                new Object[]{"p-01", "Text eins", "d2", "Name, Gesundheit", "Anspruchsvoll", "ja"},
                new Object[]{"p-02", "Text zwei"},
                new Object[]{"p-03", "Text drei", "D0", "keine", "einfach", "nein"},
                new Object[]{null, "ohne nr"},
                new Object[]{7.0, "numerische nr"})));
        assertEquals(4, rows.size());
        assertEquals("D2", rows.get(0).gold().datenschutzstufe());
        assertEquals(List.of("Name", "Gesundheit"), rows.get(0).gold().piiArten());
        assertEquals("anspruchsvoll", rows.get(0).gold().komplexitaet());
        assertTrue(rows.get(0).gold().grenzfall());
        assertNull(rows.get(1).gold().datenschutzstufe());
        assertNull(rows.get(1).gold().piiArten());
        assertEquals(List.of(), rows.get(2).gold().piiArten());
        assertEquals("7", rows.get(3).nr());
    }

    @Test
    void rejectsWrongFiles() throws IOException {
        assertThrows(GoldSetReader.GoldSetException.class,
                () -> GoldSetReader.read(new ByteArrayInputStream(xlsx("Tabelle1", HEADER, new Object[]{"1", "x"}))));
        assertThrows(GoldSetReader.GoldSetException.class,
                () -> GoldSetReader.read(new ByteArrayInputStream(xlsx("Beschriftung", new Object[]{"nr", "text"}, new Object[]{"1", "x"}))));
        assertThrows(GoldSetReader.GoldSetException.class,
                () -> GoldSetReader.read(new ByteArrayInputStream("kein excel".getBytes())));
    }
}
