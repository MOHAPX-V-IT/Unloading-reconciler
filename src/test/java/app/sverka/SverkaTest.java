package app.sverka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:sverka-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "sverka.demo=false", "sverka.user=", "sverka.password="
})
@AutoConfigureMockMvc
class SverkaTest {
    @Autowired Tables tables;
    @Autowired Reconcile reconcile;
    @Autowired RunStore runs;
    @Autowired MockMvc web;

    @Test
    void preservesLeadingZerosAcrossExcelAndRussianCsv() throws Exception {
        byte[] content;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Отчёт");
            sheet.createRow(0).createCell(0).setCellValue("Заголовок отчёта");
            var header = sheet.createRow(1);
            header.createCell(0).setCellValue("Номер");
            header.createCell(1).setCellValue("Сумма");
            var row = sheet.createRow(2);
            var code = row.createCell(0);
            code.setCellValue(1);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("000"));
            code.setCellStyle(style);
            row.createCell(1).setCellValue(120.5);
            workbook.write(output);
            content = output.toByteArray();
        }
        var left = tables.read(new MockMultipartFile("left", "excel.xlsx", "application/octet-stream", content), 2, "Отчёт", "AUTO");
        var right = tables.read(new MockMultipartFile("right", "crm.csv", "text/csv",
            "Номер;Сумма\r\n001;120,500\r\n1;120,5\r\n".getBytes(Charset.forName("windows-1251"))), 1, "", "AUTO");
        var report = compare(left, right);
        assertThat(left.rows().getFirst().number()).isEqualTo(3);
        assertThat(report.count(Reconcile.State.MATCH)).isEqualTo(1);
        assertThat(report.count(Reconcile.State.RIGHT_ONLY)).isEqualTo(1);
    }

    @Test
    void keepsAmbiguousAndMissingKeysSeparateAndPreservesRun() throws Exception {
        var left = tables.read(csv("left", "Код;Цена\nA;10\nB;20\nB;30\n;4\nC;не число\n"), 1, "", "AUTO");
        var right = tables.read(csv("right", "Код;Цена\nA;10,01\nB;20\nC;1\nD;77\n"), 1, "", "AUTO");
        var report = compare(left, right);
        assertThat(report.count(Reconcile.State.MATCH)).isEqualTo(1);
        assertThat(report.count(Reconcile.State.DUPLICATE)).isEqualTo(1);
        assertThat(report.count(Reconcile.State.MISSING_KEY)).isEqualTo(1);
        assertThat(report.count(Reconcile.State.INVALID_VALUE)).isEqualTo(1);
        assertThat(report.count(Reconcile.State.RIGHT_ONLY)).isEqualTo(1);
        String id = runs.save("tester", new Tables.Pair(left, right), report);
        assertThat(runs.read("tester", id).report()).isEqualTo(report);
        web.perform(get("/reports/{id}", id).with(user("tester"))).andExpect(status().isOk());
    }

    @Test
    void previewsUploadedTablesInNormalBrowserRoute() throws Exception {
        var result = web.perform(multipart("/upload").file(csv("left", "Код;Цена\nA;12\n"))
            .file(csv("right", "Код;Цена\nA;13\n")).with(user("tester")).with(csrf()))
            .andExpect(redirectedUrl("/configure")).andReturn();
        web.perform(get("/configure").with(user("tester")).session((MockHttpSession) result.getRequest().getSession(false)))
            .andExpect(status().isOk());
    }

    private Reconcile.Report compare(Tables.Table left, Tables.Table right) {
        return reconcile.compare(new Tables.Pair(left, right), new Reconcile.Rules(
            List.of(new Reconcile.MatchColumn(0, 0, Reconcile.Type.TEXT)),
            List.of(new Reconcile.MatchColumn(1, 1, Reconcile.Type.NUMBER)), false, new BigDecimal("0.01")));
    }

    private static MockMultipartFile csv(String name, String values) {
        return new MockMultipartFile(name, name + ".csv", "text/csv", values.getBytes(StandardCharsets.UTF_8));
    }
}
