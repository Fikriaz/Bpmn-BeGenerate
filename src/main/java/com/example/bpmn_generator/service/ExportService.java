package com.example.bpmn_generator.service;

import com.itextpdf.text.Font;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

// Add these imports for PDF support
import com.itextpdf.text.*;
import com.itextpdf.text.pdf.*;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Date;

@Service
public class ExportService {

    // Updated method to handle each action step in separate rows
    public Resource generateExcelFromScenario(Map<String, Object> scenarioData) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Test Scenario");

            // Enhanced color definitions
            byte[] headerRgb = hexToRgb("#2c5aa0");
            byte[] cellRgb = hexToRgb("#e8f4fd");

            XSSFColor headerBgColor = new XSSFColor(headerRgb, null);
            XSSFColor cellBgColor = new XSSFColor(cellRgb, null);

            // Create styles
            XSSFCellStyle headerStyle = createHeaderStyle(workbook, headerBgColor);
            XSSFCellStyle dataStyle = createDataStyle(workbook, cellBgColor);

            int rowNum = 0;

            // Title
            Row titleRow = sheet.createRow(rowNum++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("TEST SCENARIO REPORT");
            titleCell.setCellStyle(headerStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowNum-1, rowNum-1, 0, 6));
            rowNum++; // Empty row

            // File Info
            Row infoRow = sheet.createRow(rowNum++);
            Cell infoCell = infoRow.createCell(0);
            infoCell.setCellValue("File: " + scenarioData.get("fileName") + " | Generated: " + new Date().toString());
            infoCell.setCellStyle(dataStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowNum-1, rowNum-1, 0, 6));
            rowNum++; // Empty row

            // Create header row
            Row headerRow = sheet.createRow(rowNum++);
            createHeaderCell(headerRow, 0, "Path ID", headerStyle);
            createHeaderCell(headerRow, 1, "Summary Step", headerStyle);
            createHeaderCell(headerRow, 2, "Action Step", headerStyle);
            createHeaderCell(headerRow, 3, "Data Uji", headerStyle);
            createHeaderCell(headerRow, 4, "Expected Result", headerStyle);
            createHeaderCell(headerRow, 5, "Actual Result", headerStyle);
            createHeaderCell(headerRow, 6, "Tester", headerStyle);

            @SuppressWarnings("unchecked")
            List<String> actionSteps = (List<String>) scenarioData.get("actionSteps");

            // Prepare common data
            String pathId = (String) scenarioData.get("pathId");
            String description = (String) scenarioData.get("description");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> testData = (List<Map<String, Object>>) scenarioData.get("testData");
            String formattedTestData = formatTestDataForTable(testData);

            String expectedResult = (String) scenarioData.get("expectedResult");

            String finalTesterName = "";
            Boolean includeTester = (Boolean) scenarioData.get("includeTester");
            String testerName = (String) scenarioData.get("testerName");
            if (includeTester != null && includeTester && testerName != null && !testerName.trim().isEmpty()) {
                finalTesterName = testerName.trim();
            }

            // Create rows for each action step
            if (actionSteps != null && !actionSteps.isEmpty()) {
                for (int i = 0; i < actionSteps.size(); i++) {
                    Row dataRow = sheet.createRow(rowNum++);

                    // Path ID (only show on first row)
                    if (i == 0) {
                        createDataCell(dataRow, 0, pathId, dataStyle);
                    } else {
                        createDataCell(dataRow, 0, "", dataStyle);
                    }

                    // Summary Step (only show on first row)
                    if (i == 0) {
                        createDataCell(dataRow, 1, description, dataStyle);
                    } else {
                        createDataCell(dataRow, 1, "", dataStyle);
                    }

                    // Action Step (show current step)
                    createDataCell(dataRow, 2, "Step " + (i + 1) + ": " + actionSteps.get(i), dataStyle);

                    // Data Uji (only show on first row)
                    if (i == 0) {
                        createDataCell(dataRow, 3, formattedTestData, dataStyle);
                    } else {
                        createDataCell(dataRow, 3, "", dataStyle);
                    }

                    // Expected Result (only show on first row)
                    if (i == 0) {
                        createDataCell(dataRow, 4, expectedResult, dataStyle);
                    } else {
                        createDataCell(dataRow, 4, "", dataStyle);
                    }

                    // Actual Result (empty)
                    createDataCell(dataRow, 5, "", dataStyle);

                    // Tester (only show on first row)
                    if (i == 0) {
                        createDataCell(dataRow, 6, finalTesterName, dataStyle);
                    } else {
                        createDataCell(dataRow, 6, "", dataStyle);
                    }
                }

                // Merge cells for Path ID, Summary, Data Uji, Expected Result, and Tester
                int firstDataRow = rowNum - actionSteps.size();
                int lastDataRow = rowNum - 1;

                if (actionSteps.size() > 1) {
                    // Merge Path ID cells
                    sheet.addMergedRegion(new CellRangeAddress(firstDataRow, lastDataRow, 0, 0));
                    // Merge Summary Step cells
                    sheet.addMergedRegion(new CellRangeAddress(firstDataRow, lastDataRow, 1, 1));
                    // Merge Data Uji cells
                    sheet.addMergedRegion(new CellRangeAddress(firstDataRow, lastDataRow, 3, 3));
                    // Merge Expected Result cells
                    sheet.addMergedRegion(new CellRangeAddress(firstDataRow, lastDataRow, 4, 4));
                    // Merge Tester cells
                    sheet.addMergedRegion(new CellRangeAddress(firstDataRow, lastDataRow, 6, 6));
                }

            } else {
                // If no action steps, create one row
                Row dataRow = sheet.createRow(rowNum++);
                createDataCell(dataRow, 0, pathId, dataStyle);
                createDataCell(dataRow, 1, description, dataStyle);
                createDataCell(dataRow, 2, "-", dataStyle);
                createDataCell(dataRow, 3, formattedTestData, dataStyle);
                createDataCell(dataRow, 4, expectedResult, dataStyle);
                createDataCell(dataRow, 5, "", dataStyle);
                createDataCell(dataRow, 6, finalTesterName, dataStyle);
            }

            // Set column widths for better readability
            sheet.setColumnWidth(0, 2500);  // Path ID
            sheet.setColumnWidth(1, 8000);  // Summary Step
            sheet.setColumnWidth(2, 8000);  // Action Step
            sheet.setColumnWidth(3, 5000);  // Data Uji
            sheet.setColumnWidth(4, 6000);  // Expected Result
            sheet.setColumnWidth(5, 4000);  // Actual Result
            sheet.setColumnWidth(6, 3000);  // Tester

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);

            return new ByteArrayResource(outputStream.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // NEW METHOD: Generate Excel for all scenarios with vertical steps
    public Resource generateExcelFromAllScenarios(Map<String, Object> allScenariosData) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("All Test Scenarios");

            // Enhanced color definitions
            byte[] headerRgb = hexToRgb("#2c5aa0");
            byte[] cellRgb = hexToRgb("#e8f4fd");
            byte[] alternateRgb = hexToRgb("#f8fbff");

            XSSFColor headerBgColor = new XSSFColor(headerRgb, null);
            XSSFColor cellBgColor = new XSSFColor(cellRgb, null);
            XSSFColor alternateBgColor = new XSSFColor(alternateRgb, null);

            // Create styles
            XSSFCellStyle headerStyle = createHeaderStyle(workbook, headerBgColor);
            XSSFCellStyle dataStyle = createDataStyle(workbook, cellBgColor);
            XSSFCellStyle alternateDataStyle = createDataStyle(workbook, alternateBgColor);

            int rowNum = 0;

            // Title
            Row titleRow = sheet.createRow(rowNum++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("ALL TEST SCENARIOS REPORT");
            titleCell.setCellStyle(headerStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowNum-1, rowNum-1, 0, 6));
            rowNum++; // Empty row

            // File Info
            Row infoRow = sheet.createRow(rowNum++);
            Cell infoCell = infoRow.createCell(0);
            infoCell.setCellValue("File: " + allScenariosData.get("fileName") + " | Generated: " + new Date().toString());
            infoCell.setCellStyle(dataStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowNum-1, rowNum-1, 0, 6));
            rowNum++; // Empty row

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scenarios = (List<Map<String, Object>>) allScenariosData.get("scenarios");

            if (scenarios == null || scenarios.isEmpty()) {
                Row noDataRow = sheet.createRow(rowNum);
                Cell noDataCell = noDataRow.createCell(0);
                noDataCell.setCellValue("No scenarios available");
                noDataCell.setCellStyle(dataStyle);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                workbook.write(outputStream);
                return new ByteArrayResource(outputStream.toByteArray());
            }

            // Create header row
            Row headerRow = sheet.createRow(rowNum++);
            createHeaderCell(headerRow, 0, "Path ID", headerStyle);
            createHeaderCell(headerRow, 1, "Summary Step", headerStyle);
            createHeaderCell(headerRow, 2, "Action Steps", headerStyle);
            createHeaderCell(headerRow, 3, "Data Uji", headerStyle);
            createHeaderCell(headerRow, 4, "Expected Result", headerStyle);
            createHeaderCell(headerRow, 5, "Actual Result", headerStyle);
            createHeaderCell(headerRow, 6, "Tester", headerStyle);

            // Create data rows for each scenario
            for (int scenarioIndex = 0; scenarioIndex < scenarios.size(); scenarioIndex++) {
                Map<String, Object> scenario = scenarios.get(scenarioIndex);
                Row dataRow = sheet.createRow(rowNum++);

                // Alternate row colors
                XSSFCellStyle currentDataStyle = (scenarioIndex % 2 == 0) ? dataStyle : alternateDataStyle;

                // Path ID
                createDataCell(dataRow, 0, (String) scenario.get("pathId"), currentDataStyle);

                // Summary Step
                createDataCell(dataRow, 1, (String) scenario.get("description"), currentDataStyle);

                // Action Steps - vertical format
                @SuppressWarnings("unchecked")
                List<String> actionSteps = (List<String>) scenario.get("actionSteps");
                String combinedSteps = formatActionStepsVertically(actionSteps);
                createDataCell(dataRow, 2, combinedSteps, currentDataStyle);

                // Data Uji
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> testData = (List<Map<String, Object>>) scenario.get("testData");
                String formattedTestData = formatTestDataForTable(testData);
                createDataCell(dataRow, 3, formattedTestData, currentDataStyle);

                // Expected Result
                createDataCell(dataRow, 4, (String) scenario.get("expectedResult"), currentDataStyle);

                // Actual Result (empty)
                createDataCell(dataRow, 5, "", currentDataStyle);

                // Tester
                String finalTesterName = "";
                Boolean includeTester = (Boolean) allScenariosData.get("includeTester");
                String testerName = (String) allScenariosData.get("testerName");

                if (includeTester != null && includeTester && testerName != null && !testerName.trim().isEmpty()) {
                    finalTesterName = testerName.trim();
                }
                createDataCell(dataRow, 6, finalTesterName, currentDataStyle);

                // Set row height based on content
                int maxLines = Math.max(
                        countLines(scenario.get("description")),
                        actionSteps != null ? actionSteps.size() : 1
                );
                dataRow.setHeightInPoints(Math.max(40, maxLines * 20));
            }

            // Set column widths
            sheet.setColumnWidth(0, 2500);  // Path ID
            sheet.setColumnWidth(1, 8000);  // Summary Step
            sheet.setColumnWidth(2, 8000);  // Action Steps
            sheet.setColumnWidth(3, 5000);  // Data Uji
            sheet.setColumnWidth(4, 6000);  // Expected Result
            sheet.setColumnWidth(5, 4000);  // Actual Result
            sheet.setColumnWidth(6, 3000);  // Tester

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);

            return new ByteArrayResource(outputStream.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // Helper method to format action steps vertically
    private String formatActionStepsVertically(List<String> actionSteps) {
        if (actionSteps == null || actionSteps.isEmpty()) {
            return "-";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < actionSteps.size(); i++) {
            sb.append("Step ").append(i + 1).append(": ").append(actionSteps.get(i));
            if (i < actionSteps.size() - 1) {
                sb.append("\n\n"); // Double line break for better separation
            }
        }
        return sb.toString();
    }

    // Helper method to count lines in text
    private int countLines(Object text) {
        if (text == null) return 1;
        String str = text.toString();
        return (int) str.chars().filter(ch -> ch == '\n').count() + 1;
    }

    // Helper methods for creating cells
    private void createHeaderCell(Row row, int colIndex, String value, XSSFCellStyle style) {
        Cell cell = row.createCell(colIndex);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private void createDataCell(Row row, int colIndex, String value, XSSFCellStyle style) {
        Cell cell = row.createCell(colIndex);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    // Updated PDF method to handle tester name
    public Resource generatePdfFromScenario(Map<String, Object> scenarioData) {
        try {
            Document document = new Document(PageSize.A4, 36, 36, 54, 54);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            PdfWriter writer = PdfWriter.getInstance(document, outputStream);

            document.open();

            // Fonts
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, BaseColor.DARK_GRAY);
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, BaseColor.BLACK);
            Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 11, BaseColor.BLACK);
            Font dataFont = FontFactory.getFont(FontFactory.COURIER, 10, BaseColor.BLACK);

            // Title
            Paragraph title = new Paragraph("TEST SCENARIO REPORT", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(20);
            document.add(title);

            // File Info Table
            PdfPTable infoTable = new PdfPTable(2);
            infoTable.setWidthPercentage(100);
            infoTable.setWidths(new float[]{30, 70});

            addTableRow(infoTable, "File Name:", (String) scenarioData.get("fileName"), labelFont, valueFont);
            addTableRow(infoTable, "Path ID:", (String) scenarioData.get("pathId"), labelFont, valueFont);

            // Handle tester name in PDF
            Boolean includeTester = (Boolean) scenarioData.get("includeTester");
            String testerName = (String) scenarioData.get("testerName");
            if (includeTester != null && includeTester && testerName != null && !testerName.trim().isEmpty()) {
                addTableRow(infoTable, "Tester:", testerName, labelFont, valueFont);
            }

            addTableRow(infoTable, "Generated:", new Date().toString(), labelFont, valueFont);

            document.add(infoTable);
            document.add(new Paragraph(" ")); // Space

            // Description
            addLabelValuePdf(document, "Description:", (String) scenarioData.get("description"), labelFont, valueFont);
            document.add(new Paragraph(" "));

            // Action Steps
            @SuppressWarnings("unchecked")
            List<String> actionSteps = (List<String>) scenarioData.get("actionSteps");
            if (actionSteps != null && !actionSteps.isEmpty()) {
                Paragraph actionHeader = new Paragraph("Action Steps:", labelFont);
                actionHeader.setSpacingAfter(10);
                document.add(actionHeader);

                for (int i = 0; i < actionSteps.size(); i++) {
                    Paragraph step = new Paragraph((i + 1) + ". " + actionSteps.get(i), valueFont);
                    step.setIndentationLeft(20);
                    step.setSpacingAfter(5);
                    document.add(step);
                }
                document.add(new Paragraph(" "));
            }

            // Test Data
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> testData = (List<Map<String, Object>>) scenarioData.get("testData");
            if (testData != null && !testData.isEmpty()) {
                Paragraph testDataHeader = new Paragraph("Test Data:", labelFont);
                testDataHeader.setSpacingAfter(10);
                document.add(testDataHeader);

                for (int i = 0; i < testData.size(); i++) {
                    Map<String, Object> item = testData.get(i);
                    String dataText = (i + 1) + ". " + item.get("label") + ": ";

                    if ("array".equals(item.get("type"))) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> arrayValues = (List<Map<String, Object>>) item.get("value");
                        dataText += formatArrayValueForPdf(arrayValues);
                    } else if ("object".equals(item.get("type"))) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> objectValues = (List<Map<String, Object>>) item.get("value");
                        dataText += formatObjectValueForPdf(objectValues);
                    } else {
                        dataText += item.get("value");
                    }

                    Paragraph dataItem = new Paragraph(dataText, dataFont);
                    dataItem.setIndentationLeft(20);
                    dataItem.setSpacingAfter(8);
                    document.add(dataItem);
                }
                document.add(new Paragraph(" "));
            }

            // Expected Result
            addLabelValuePdf(document, "Expected Result:", (String) scenarioData.get("expectedResult"), labelFont, valueFont);

            // Add tester information to PDF if provided
            if (includeTester != null && includeTester && testerName != null && !testerName.trim().isEmpty()) {
                document.add(new Paragraph(" "));
                addLabelValuePdf(document, "Tested by:", testerName, labelFont, valueFont);
            }

            document.close();

            return new ByteArrayResource(outputStream.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // All your existing helper methods remain the same...
    private String formatTestDataForTable(List<Map<String, Object>> testData) {
        if (testData == null || testData.isEmpty()) {
            return "-";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < testData.size(); i++) {
            Map<String, Object> item = testData.get(i);
            sb.append(item.get("label")).append(": ");

            if ("array".equals(item.get("type"))) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> arrayValues = (List<Map<String, Object>>) item.get("value");
                sb.append(formatArrayValueForExcel(arrayValues));
            } else if ("object".equals(item.get("type"))) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> objectValues = (List<Map<String, Object>>) item.get("value");
                sb.append(formatObjectValueForExcel(objectValues));
            } else {
                sb.append(item.get("value"));
            }

            if (i < testData.size() - 1) {
                sb.append("\n\n");
            }
        }
        return sb.toString();
    }

    // Keep all your existing helper methods...
    private XSSFCellStyle createLabelStyle(XSSFWorkbook workbook) {
        XSSFCellStyle style = workbook.createCellStyle();
        XSSFFont font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        font.setFontName("Arial");
        style.setFont(font);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        setBorders(style);
        return style;
    }

    private void createLabelValueRow(Sheet sheet, int rowNum, String label, String value, CellStyle labelStyle, CellStyle valueStyle) {
        Row row = sheet.createRow(rowNum);

        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(labelStyle);

        Cell valueCell = row.createCell(1);
        valueCell.setCellValue(value != null ? value : "");
        valueCell.setCellStyle(valueStyle);

        row.setHeightInPoints(20);
    }

    private void createMultiLineRow(Sheet sheet, int rowNum, String label, String value, CellStyle labelStyle, CellStyle valueStyle) {
        Row row = sheet.createRow(rowNum);

        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(labelStyle);

        Cell valueCell = row.createCell(1);
        valueCell.setCellValue(value != null ? value : "");
        valueCell.setCellStyle(valueStyle);

        int lines = value != null ? (value.length() / 50) + 1 : 1;
        row.setHeightInPoints(Math.max(20, lines * 15));
    }

    private void addTableRow(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, labelFont));
        labelCell.setBorder(Rectangle.NO_BORDER);
        labelCell.setPadding(5);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value != null ? value : "", valueFont));
        valueCell.setBorder(Rectangle.NO_BORDER);
        valueCell.setPadding(5);
        table.addCell(valueCell);
    }

    private void addLabelValuePdf(Document document, String label, String value, Font labelFont, Font valueFont) throws DocumentException {
        Paragraph paragraph = new Paragraph();
        paragraph.add(new Chunk(label, labelFont));
        paragraph.add(new Chunk(value != null ? value : "", valueFont));
        paragraph.setSpacingAfter(10);
        document.add(paragraph);
    }

    private String formatArrayValueForExcel(List<Map<String, Object>> arrayValues) {
        if (arrayValues == null || arrayValues.isEmpty()) return "[]";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arrayValues.size(); i++) {
            Map<String, Object> item = arrayValues.get(i);
            sb.append("\n   - Item ").append(i + 1).append(": ");

            if (item.containsKey("properties")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> properties = (List<Map<String, Object>>) item.get("properties");
                sb.append(properties.stream()
                        .map(prop -> prop.get("key") + ": " + prop.get("value"))
                        .collect(java.util.stream.Collectors.joining(", ")));
            } else {
                sb.append(item.get("value"));
            }
        }
        return sb.toString();
    }

    private String formatObjectValueForExcel(List<Map<String, Object>> objectValues) {
        if (objectValues == null || objectValues.isEmpty()) return "{}";

        return objectValues.stream()
                .map(prop -> "\n   - " + prop.get("key") + ": " + prop.get("value"))
                .collect(java.util.stream.Collectors.joining());
    }

    private String formatArrayValueForPdf(List<Map<String, Object>> arrayValues) {
        return formatArrayValueForExcel(arrayValues);
    }

    private String formatObjectValueForPdf(List<Map<String, Object>> objectValues) {
        return formatObjectValueForExcel(objectValues);
    }

    private XSSFCellStyle createHeaderStyle(XSSFWorkbook workbook, XSSFColor bgColor) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(bgColor);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        XSSFFont font = workbook.createFont();
        font.setColor(IndexedColors.WHITE.getIndex());
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        font.setFontName("Arial");
        style.setFont(font);

        setBorders(style);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);

        return style;
    }

    private XSSFCellStyle createDataStyle(XSSFWorkbook workbook, XSSFColor bgColor) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.setWrapText(true);
        style.setFillForegroundColor(bgColor);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorders(style);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setAlignment(HorizontalAlignment.LEFT);

        XSSFFont font = workbook.createFont();
        font.setFontHeightInPoints((short) 9);
        font.setFontName("Consolas");
        style.setFont(font);

        return style;
    }

    private void setBorders(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    private byte[] hexToRgb(String hex) {
        int r = Integer.valueOf(hex.substring(1, 3), 16);
        int g = Integer.valueOf(hex.substring(3, 5), 16);
        int b = Integer.valueOf(hex.substring(5, 7), 16);
        return new byte[]{(byte) r, (byte) g, (byte) b};
    }
} 