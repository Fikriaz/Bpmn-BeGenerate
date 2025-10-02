package com.example.bpmn_generator.service;

import com.itextpdf.text.Font;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.*;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Date;

@Service
public class ExportService {

    // SINGLE SCENARIO EXCEL - KEEP AS IS
    public Resource generateExcelFromScenario(Map<String, Object> scenarioData) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Test Scenario");

            byte[] headerRgb = hexToRgb("#2c5aa0");
            byte[] cellRgb = hexToRgb("#e8f4fd");

            XSSFColor headerBgColor = new XSSFColor(headerRgb, null);
            XSSFColor cellBgColor = new XSSFColor(cellRgb, null);

            XSSFCellStyle headerStyle = createHeaderStyle(workbook, headerBgColor);
            XSSFCellStyle dataStyle = createDataStyle(workbook, cellBgColor);

            int rowNum = 0;

            Row titleRow = sheet.createRow(rowNum++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("TEST SCENARIO REPORT");
            titleCell.setCellStyle(headerStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowNum-1, rowNum-1, 0, 6));
            rowNum++;

            Row infoRow = sheet.createRow(rowNum++);
            Cell infoCell = infoRow.createCell(0);
            infoCell.setCellValue("File: " + scenarioData.get("fileName") + " | Generated: " + new Date().toString());
            infoCell.setCellStyle(dataStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowNum-1, rowNum-1, 0, 6));
            rowNum++;

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

            if (actionSteps != null && !actionSteps.isEmpty()) {
                for (int i = 0; i < actionSteps.size(); i++) {
                    Row dataRow = sheet.createRow(rowNum++);

                    if (i == 0) {
                        createDataCell(dataRow, 0, pathId, dataStyle);
                    } else {
                        createDataCell(dataRow, 0, "", dataStyle);
                    }

                    if (i == 0) {
                        createDataCell(dataRow, 1, description, dataStyle);
                    } else {
                        createDataCell(dataRow, 1, "", dataStyle);
                    }

                    createDataCell(dataRow, 2, "Step " + (i + 1) + ": " + actionSteps.get(i), dataStyle);

                    if (i == 0) {
                        createDataCell(dataRow, 3, formattedTestData, dataStyle);
                    } else {
                        createDataCell(dataRow, 3, "", dataStyle);
                    }

                    if (i == 0) {
                        createDataCell(dataRow, 4, expectedResult, dataStyle);
                    } else {
                        createDataCell(dataRow, 4, "", dataStyle);
                    }

                    createDataCell(dataRow, 5, "", dataStyle);

                    if (i == 0) {
                        createDataCell(dataRow, 6, finalTesterName, dataStyle);
                    } else {
                        createDataCell(dataRow, 6, "", dataStyle);
                    }
                }

                int firstDataRow = rowNum - actionSteps.size();
                int lastDataRow = rowNum - 1;

                if (actionSteps.size() > 1) {
                    sheet.addMergedRegion(new CellRangeAddress(firstDataRow, lastDataRow, 0, 0));
                    sheet.addMergedRegion(new CellRangeAddress(firstDataRow, lastDataRow, 1, 1));
                    sheet.addMergedRegion(new CellRangeAddress(firstDataRow, lastDataRow, 3, 3));
                    sheet.addMergedRegion(new CellRangeAddress(firstDataRow, lastDataRow, 4, 4));
                    sheet.addMergedRegion(new CellRangeAddress(firstDataRow, lastDataRow, 6, 6));
                }

            } else {
                Row dataRow = sheet.createRow(rowNum++);
                createDataCell(dataRow, 0, pathId, dataStyle);
                createDataCell(dataRow, 1, description, dataStyle);
                createDataCell(dataRow, 2, "-", dataStyle);
                createDataCell(dataRow, 3, formattedTestData, dataStyle);
                createDataCell(dataRow, 4, expectedResult, dataStyle);
                createDataCell(dataRow, 5, "", dataStyle);
                createDataCell(dataRow, 6, finalTesterName, dataStyle);
            }

            sheet.setColumnWidth(0, 2500);
            sheet.setColumnWidth(1, 8000);
            sheet.setColumnWidth(2, 8000);
            sheet.setColumnWidth(3, 5000);
            sheet.setColumnWidth(4, 6000);
            sheet.setColumnWidth(5, 4000);
            sheet.setColumnWidth(6, 3000);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);

            return new ByteArrayResource(outputStream.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // UPDATED: ALL SCENARIOS EXCEL - SAME FORMAT AS SINGLE SCENARIO
    public Resource generateExcelFromAllScenarios(Map<String, Object> allScenariosData) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("All Test Scenarios");

            byte[] headerRgb = hexToRgb("#2c5aa0");
            byte[] cellRgb = hexToRgb("#e8f4fd");

            XSSFColor headerBgColor = new XSSFColor(headerRgb, null);
            XSSFColor cellBgColor = new XSSFColor(cellRgb, null);

            XSSFCellStyle headerStyle = createHeaderStyle(workbook, headerBgColor);
            XSSFCellStyle dataStyle = createDataStyle(workbook, cellBgColor);

            int rowNum = 0;

            Row titleRow = sheet.createRow(rowNum++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("ALL TEST SCENARIOS REPORT");
            titleCell.setCellStyle(headerStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowNum-1, rowNum-1, 0, 6));
            rowNum++;

            Row infoRow = sheet.createRow(rowNum++);
            Cell infoCell = infoRow.createCell(0);
            infoCell.setCellValue("File: " + allScenariosData.get("fileName") + " | Generated: " + new Date().toString());
            infoCell.setCellStyle(dataStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowNum-1, rowNum-1, 0, 6));
            rowNum++;

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

            Row headerRow = sheet.createRow(rowNum++);
            createHeaderCell(headerRow, 0, "Path ID", headerStyle);
            createHeaderCell(headerRow, 1, "Summary Step", headerStyle);
            createHeaderCell(headerRow, 2, "Action Step", headerStyle);
            createHeaderCell(headerRow, 3, "Data Uji", headerStyle);
            createHeaderCell(headerRow, 4, "Expected Result", headerStyle);
            createHeaderCell(headerRow, 5, "Actual Result", headerStyle);
            createHeaderCell(headerRow, 6, "Tester", headerStyle);

            Boolean includeTester = (Boolean) allScenariosData.get("includeTester");
            String testerName = (String) allScenariosData.get("testerName");
            String finalTesterName = "";
            if (includeTester != null && includeTester && testerName != null && !testerName.trim().isEmpty()) {
                finalTesterName = testerName.trim();
            }

            // Process each scenario with action steps in separate rows
            for (Map<String, Object> scenario : scenarios) {
                String pathId = (String) scenario.get("path_id");
                String description = scenario.get("readable_description") != null
                        ? (String) scenario.get("readable_description")
                        : (String) scenario.get("summary");

                @SuppressWarnings("unchecked")
                List<String> rawPath = (List<String>) scenario.get("rawPath");

                // Extract action steps from rawPath
                List<String> actionSteps = extractActionSteps(rawPath);

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> testData = (List<Map<String, Object>>) scenario.get("input_data");
                String formattedTestData = formatTestDataForTable(testData);

                String expectedResult = (String) scenario.get("expected_result");

                if (actionSteps != null && !actionSteps.isEmpty()) {
                    int firstRowOfScenario = rowNum;

                    for (int i = 0; i < actionSteps.size(); i++) {
                        Row dataRow = sheet.createRow(rowNum++);

                        // Path ID (only first row)
                        if (i == 0) {
                            createDataCell(dataRow, 0, pathId, dataStyle);
                        } else {
                            createDataCell(dataRow, 0, "", dataStyle);
                        }

                        // Summary Step (only first row)
                        if (i == 0) {
                            createDataCell(dataRow, 1, description, dataStyle);
                        } else {
                            createDataCell(dataRow, 1, "", dataStyle);
                        }

                        // Action Step (each step gets its own row)
                        createDataCell(dataRow, 2, "Step " + (i + 1) + ": " + actionSteps.get(i), dataStyle);

                        // Data Uji (only first row)
                        if (i == 0) {
                            createDataCell(dataRow, 3, formattedTestData, dataStyle);
                        } else {
                            createDataCell(dataRow, 3, "", dataStyle);
                        }

                        // Expected Result (only first row)
                        if (i == 0) {
                            createDataCell(dataRow, 4, expectedResult, dataStyle);
                        } else {
                            createDataCell(dataRow, 4, "", dataStyle);
                        }

                        // Actual Result (empty)
                        createDataCell(dataRow, 5, "", dataStyle);

                        // Tester (only first row)
                        if (i == 0) {
                            createDataCell(dataRow, 6, finalTesterName, dataStyle);
                        } else {
                            createDataCell(dataRow, 6, "", dataStyle);
                        }
                    }

                    // Merge cells for columns that should span multiple rows
                    int lastRowOfScenario = rowNum - 1;
                    if (actionSteps.size() > 1) {
                        sheet.addMergedRegion(new CellRangeAddress(firstRowOfScenario, lastRowOfScenario, 0, 0)); // Path ID
                        sheet.addMergedRegion(new CellRangeAddress(firstRowOfScenario, lastRowOfScenario, 1, 1)); // Summary
                        sheet.addMergedRegion(new CellRangeAddress(firstRowOfScenario, lastRowOfScenario, 3, 3)); // Data Uji
                        sheet.addMergedRegion(new CellRangeAddress(firstRowOfScenario, lastRowOfScenario, 4, 4)); // Expected Result
                        sheet.addMergedRegion(new CellRangeAddress(firstRowOfScenario, lastRowOfScenario, 6, 6)); // Tester
                    }
                } else {
                    // If no action steps, create single row
                    Row dataRow = sheet.createRow(rowNum++);
                    createDataCell(dataRow, 0, pathId, dataStyle);
                    createDataCell(dataRow, 1, description, dataStyle);
                    createDataCell(dataRow, 2, "-", dataStyle);
                    createDataCell(dataRow, 3, formattedTestData, dataStyle);
                    createDataCell(dataRow, 4, expectedResult, dataStyle);
                    createDataCell(dataRow, 5, "", dataStyle);
                    createDataCell(dataRow, 6, finalTesterName, dataStyle);
                }
            }

            sheet.setColumnWidth(0, 2500);
            sheet.setColumnWidth(1, 8000);
            sheet.setColumnWidth(2, 8000);
            sheet.setColumnWidth(3, 5000);
            sheet.setColumnWidth(4, 6000);
            sheet.setColumnWidth(5, 4000);
            sheet.setColumnWidth(6, 3000);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);

            return new ByteArrayResource(outputStream.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // NEW: Generate PDF for All Scenarios
    public Resource generatePdfFromAllScenarios(Map<String, Object> allScenariosData) {
        try {
            Document document = new Document(PageSize.A4.rotate(), 36, 36, 54, 54); // Landscape for table
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            PdfWriter writer = PdfWriter.getInstance(document, outputStream);

            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, BaseColor.DARK_GRAY);
            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, BaseColor.WHITE);
            Font dataFont = FontFactory.getFont(FontFactory.HELVETICA, 8, BaseColor.BLACK);
            Font stepFont = FontFactory.getFont(FontFactory.HELVETICA, 7, BaseColor.BLACK);

            Paragraph title = new Paragraph("ALL TEST SCENARIOS REPORT", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(15);
            document.add(title);

            Paragraph fileInfo = new Paragraph("File: " + allScenariosData.get("fileName") + " | Generated: " + new Date().toString(), dataFont);
            fileInfo.setAlignment(Element.ALIGN_CENTER);
            fileInfo.setSpacingAfter(20);
            document.add(fileInfo);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scenarios = (List<Map<String, Object>>) allScenariosData.get("scenarios");

            if (scenarios == null || scenarios.isEmpty()) {
                document.add(new Paragraph("No scenarios available", dataFont));
                document.close();
                return new ByteArrayResource(outputStream.toByteArray());
            }

            Boolean includeTester = (Boolean) allScenariosData.get("includeTester");
            String testerName = (String) allScenariosData.get("testerName");
            String finalTesterName = "";
            if (includeTester != null && includeTester && testerName != null && !testerName.trim().isEmpty()) {
                finalTesterName = testerName.trim();
            }

            // Create table with 7 columns
            PdfPTable table = new PdfPTable(7);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{10, 20, 25, 15, 15, 10, 10});

            // Table header
            BaseColor headerBg = new BaseColor(44, 90, 160);
            String[] headers = {"Path ID", "Summary Step", "Action Step", "Data Uji", "Expected Result", "Actual Result", "Tester"};
            for (String header : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(header, headerFont));
                cell.setBackgroundColor(headerBg);
                cell.setPadding(8);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                table.addCell(cell);
            }

            // Process each scenario
            for (Map<String, Object> scenario : scenarios) {
                String pathId = (String) scenario.get("path_id");
                String description = scenario.get("readable_description") != null
                        ? (String) scenario.get("readable_description")
                        : (String) scenario.get("summary");

                @SuppressWarnings("unchecked")
                List<String> rawPath = (List<String>) scenario.get("rawPath");
                List<String> actionSteps = extractActionSteps(rawPath);

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> testData = (List<Map<String, Object>>) scenario.get("input_data");
                String formattedTestData = formatTestDataForTable(testData);

                String expectedResult = (String) scenario.get("expected_result");

                if (actionSteps != null && !actionSteps.isEmpty()) {
                    int rowspan = actionSteps.size();

                    for (int i = 0; i < actionSteps.size(); i++) {
                        // Path ID (rowspan)
                        if (i == 0) {
                            PdfPCell cell = new PdfPCell(new Phrase(pathId, dataFont));
                            cell.setRowspan(rowspan);
                            cell.setPadding(5);
                            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                            table.addCell(cell);
                        }

                        // Summary Step (rowspan)
                        if (i == 0) {
                            PdfPCell cell = new PdfPCell(new Phrase(description, dataFont));
                            cell.setRowspan(rowspan);
                            cell.setPadding(5);
                            cell.setVerticalAlignment(Element.ALIGN_TOP);
                            table.addCell(cell);
                        }

                        // Action Step (each step)
                        PdfPCell stepCell = new PdfPCell(new Phrase("Step " + (i + 1) + ": " + actionSteps.get(i), stepFont));
                        stepCell.setPadding(5);
                        stepCell.setVerticalAlignment(Element.ALIGN_TOP);
                        table.addCell(stepCell);

                        // Data Uji (rowspan)
                        if (i == 0) {
                            PdfPCell cell = new PdfPCell(new Phrase(formattedTestData, stepFont));
                            cell.setRowspan(rowspan);
                            cell.setPadding(5);
                            cell.setVerticalAlignment(Element.ALIGN_TOP);
                            table.addCell(cell);
                        }

                        // Expected Result (rowspan)
                        if (i == 0) {
                            PdfPCell cell = new PdfPCell(new Phrase(expectedResult, dataFont));
                            cell.setRowspan(rowspan);
                            cell.setPadding(5);
                            cell.setVerticalAlignment(Element.ALIGN_TOP);
                            table.addCell(cell);
                        }

                        // Actual Result (empty)
                        PdfPCell actualCell = new PdfPCell(new Phrase("", dataFont));
                        actualCell.setPadding(5);
                        table.addCell(actualCell);

                        // Tester (rowspan)
                        if (i == 0) {
                            PdfPCell cell = new PdfPCell(new Phrase(finalTesterName, dataFont));
                            cell.setRowspan(rowspan);
                            cell.setPadding(5);
                            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                            table.addCell(cell);
                        }
                    }
                } else {
                    // Single row if no action steps
                    table.addCell(new PdfPCell(new Phrase(pathId, dataFont)));
                    table.addCell(new PdfPCell(new Phrase(description, dataFont)));
                    table.addCell(new PdfPCell(new Phrase("-", dataFont)));
                    table.addCell(new PdfPCell(new Phrase(formattedTestData, stepFont)));
                    table.addCell(new PdfPCell(new Phrase(expectedResult, dataFont)));
                    table.addCell(new PdfPCell(new Phrase("", dataFont)));
                    table.addCell(new PdfPCell(new Phrase(finalTesterName, dataFont)));
                }
            }

            document.add(table);
            document.close();

            return new ByteArrayResource(outputStream.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // Helper: Extract action steps from rawPath
    private List<String> extractActionSteps(List<String> rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return List.of();
        }

        // Remove [Lane] prefix from each step
        return rawPath.stream()
                .map(step -> step.replaceAll("^\\[.*?\\]\\s*", ""))
                .filter(step -> !step.trim().isEmpty())
                .toList();
    }

    // SINGLE SCENARIO PDF - KEEP AS IS
    public Resource generatePdfFromScenario(Map<String, Object> scenarioData) {
        try {
            Document document = new Document(PageSize.A4, 36, 36, 54, 54);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            PdfWriter writer = PdfWriter.getInstance(document, outputStream);

            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, BaseColor.DARK_GRAY);
            Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, BaseColor.BLACK);
            Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 11, BaseColor.BLACK);
            Font dataFont = FontFactory.getFont(FontFactory.COURIER, 10, BaseColor.BLACK);

            Paragraph title = new Paragraph("TEST SCENARIO REPORT", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(20);
            document.add(title);

            PdfPTable infoTable = new PdfPTable(2);
            infoTable.setWidthPercentage(100);
            infoTable.setWidths(new float[]{30, 70});

            addTableRow(infoTable, "File Name:", (String) scenarioData.get("fileName"), labelFont, valueFont);
            addTableRow(infoTable, "Path ID:", (String) scenarioData.get("pathId"), labelFont, valueFont);

            Boolean includeTester = (Boolean) scenarioData.get("includeTester");
            String testerName = (String) scenarioData.get("testerName");
            if (includeTester != null && includeTester && testerName != null && !testerName.trim().isEmpty()) {
                addTableRow(infoTable, "Tester:", testerName, labelFont, valueFont);
            }

            addTableRow(infoTable, "Generated:", new Date().toString(), labelFont, valueFont);

            document.add(infoTable);
            document.add(new Paragraph(" "));

            addLabelValuePdf(document, "Description:", (String) scenarioData.get("description"), labelFont, valueFont);
            document.add(new Paragraph(" "));

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

            addLabelValuePdf(document, "Expected Result:", (String) scenarioData.get("expectedResult"), labelFont, valueFont);

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

    // Helper methods
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
        paragraph.add(new Chunk(" " + (value != null ? value : ""), valueFont));
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