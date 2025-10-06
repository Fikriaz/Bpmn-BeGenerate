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
import java.util.ArrayList;
import java.util.HashMap;

@Service
public class ExportService {

    // ============= HELPER METHOD - Convert input_data to List format =============
    private List<Map<String, Object>> processInputDataToList(Object inputDataObj) {
        if (inputDataObj == null) {
            return List.of();
        }

        if (inputDataObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) inputDataObj;
            return list;
        }

        if (inputDataObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> inputDataMap = (Map<String, Object>) inputDataObj;

            List<Map<String, Object>> result = new ArrayList<>();

            inputDataMap.forEach((key, value) -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", key);

                String label = key
                        .replace("_", " ")
                        .replaceAll("([A-Z])", " $1")
                        .trim()
                        .toLowerCase();
                label = label.substring(0, 1).toUpperCase() + label.substring(1);
                item.put("label", label);

                if (value instanceof List) {
                    item.put("type", "array");
                    @SuppressWarnings("unchecked")
                    List<Object> arrayValue = (List<Object>) value;
                    List<Map<String, Object>> processedArray = new ArrayList<>();

                    for (int i = 0; i < arrayValue.size(); i++) {
                        Object arrItem = arrayValue.get(i);
                        Map<String, Object> arrayItemMap = new HashMap<>();
                        arrayItemMap.put("id", key + "_" + i);
                        arrayItemMap.put("index", i);

                        if (arrItem instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> objItem = (Map<String, Object>) arrItem;
                            List<Map<String, Object>> properties = new ArrayList<>();
                            objItem.forEach((k, v) -> {
                                Map<String, Object> prop = new HashMap<>();
                                prop.put("key", k);
                                prop.put("value", String.valueOf(v));
                                properties.add(prop);
                            });
                            arrayItemMap.put("properties", properties);
                        } else {
                            arrayItemMap.put("value", String.valueOf(arrItem));
                        }
                        processedArray.add(arrayItemMap);
                    }
                    item.put("value", processedArray);

                } else if (value instanceof Map) {
                    item.put("type", "object");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> objectValue = (Map<String, Object>) value;
                    List<Map<String, Object>> properties = new ArrayList<>();
                    objectValue.forEach((k, v) -> {
                        Map<String, Object> prop = new HashMap<>();
                        prop.put("key", k);
                        prop.put("value", String.valueOf(v));
                        properties.add(prop);
                    });
                    item.put("value", properties);

                } else {
                    item.put("type", "primitive");
                    item.put("value", String.valueOf(value));
                }

                result.add(item);
            });

            return result;
        }

        return List.of();
    }

    private String extractExpectedResult(Object expectedResultObj) {
        if (expectedResultObj == null) {
            return "-";
        }

        if (expectedResultObj instanceof String) {
            return (String) expectedResultObj;
        }

        if (expectedResultObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resultMap = (Map<String, Object>) expectedResultObj;
            Object message = resultMap.get("message");
            return message != null ? String.valueOf(message) : "-";
        }

        return String.valueOf(expectedResultObj);
    }

    // ============= ✅ FIXED: Extract action steps WITH [Lane] prefix =============
    private List<String> extractActionStepsFromScenario(String scenarioStep) {
        if (scenarioStep == null || scenarioStep.trim().isEmpty()) {
            return List.of();
        }

        List<String> steps = new ArrayList<>();
        String[] lines = scenarioStep.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // Remove numbering (1. 2. etc)
            String cleaned = trimmed.replaceFirst("^\\d+\\.\\s*", "");
            // Remove arrow prefix (-> )
            cleaned = cleaned.replaceFirst("^->\\s*", "");
            // ✅ KEEP [Lane] prefix - DON'T remove it
            // cleaned = cleaned.replaceFirst("^\\[.*?\\]\\s*", ""); // COMMENTED OUT
            // Remove trailing dot
            cleaned = cleaned.replaceFirst("\\.$", "");

            if (!cleaned.trim().isEmpty()) {
                steps.add(cleaned.trim());
            }
        }

        return steps.isEmpty() ? List.of("-") : steps;
    }

    // SINGLE SCENARIO EXCEL
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
            createHeaderCell(headerRow, 3, "Test Data", headerStyle);
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
                    sheet.addMergedRegion(new CellRangeAddress(firstDataRow, lastDataRow, 5, 5));
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
            sheet.setColumnWidth(4, 6000);
            sheet.setColumnWidth(5, 3000);
            sheet.setColumnWidth(6, 3000);

            sheet.autoSizeColumn(3);
            int currentWidth = sheet.getColumnWidth(3);
            int newWidth = Math.max(10000, (int) (currentWidth * 1.15));
            sheet.setColumnWidth(3, newWidth);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);

            return new ByteArrayResource(outputStream.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ALL SCENARIOS EXCEL
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
            createHeaderCell(headerRow, 3, "Test Data", headerStyle);
            createHeaderCell(headerRow, 4, "Expected Result", headerStyle);
            createHeaderCell(headerRow, 5, "Actual Result", headerStyle);
            createHeaderCell(headerRow, 6, "Tester", headerStyle);

            Boolean includeTester = (Boolean) allScenariosData.get("includeTester");
            String testerName = (String) allScenariosData.get("testerName");
            String finalTesterName = "";
            if (includeTester != null && includeTester && testerName != null && !testerName.trim().isEmpty()) {
                finalTesterName = testerName.trim();
            }

            for (Map<String, Object> scenario : scenarios) {
                String pathId = (String) scenario.get("path_id");
                String description = scenario.get("readable_description") != null
                        ? (String) scenario.get("readable_description")
                        : (String) scenario.get("summary");

                // ✅ NOW includes [Lane] prefix
                List<String> actionSteps = extractActionStepsFromScenario((String) scenario.get("scenario_step"));

                List<Map<String, Object>> testData = processInputDataToList(scenario.get("input_data"));
                String formattedTestData = formatTestDataForTable(testData);

                String expectedResult = extractExpectedResult(scenario.get("expected_result"));

                if (actionSteps != null && !actionSteps.isEmpty()) {
                    int firstRowOfScenario = rowNum;

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

                    int lastRowOfScenario = rowNum - 1;
                    if (actionSteps.size() > 1) {
                        sheet.addMergedRegion(new CellRangeAddress(firstRowOfScenario, lastRowOfScenario, 0, 0));
                        sheet.addMergedRegion(new CellRangeAddress(firstRowOfScenario, lastRowOfScenario, 1, 1));
                        sheet.addMergedRegion(new CellRangeAddress(firstRowOfScenario, lastRowOfScenario, 3, 3));
                        sheet.addMergedRegion(new CellRangeAddress(firstRowOfScenario, lastRowOfScenario, 4, 4));
                        sheet.addMergedRegion(new CellRangeAddress(firstRowOfScenario, lastRowOfScenario, 5, 5));
                        sheet.addMergedRegion(new CellRangeAddress(firstRowOfScenario, lastRowOfScenario, 6, 6));
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
            }

            sheet.setColumnWidth(0, 2500);
            sheet.setColumnWidth(1, 8000);
            sheet.setColumnWidth(2, 8000);
            sheet.setColumnWidth(4, 6000);
            sheet.setColumnWidth(5, 3000);
            sheet.setColumnWidth(6, 3000);

            sheet.autoSizeColumn(3);
            int currentWidth = sheet.getColumnWidth(3);
            int newWidth = Math.max(10000, (int) (currentWidth * 1.15));
            sheet.setColumnWidth(3, newWidth);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);

            return new ByteArrayResource(outputStream.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ALL SCENARIOS PDF
    public Resource generatePdfFromAllScenarios(Map<String, Object> allScenariosData) {
        try {
            Document document = new Document(PageSize.A4.rotate(), 36, 36, 54, 54);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            PdfWriter.getInstance(document, outputStream);

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

            PdfPTable table = new PdfPTable(7);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{10, 20, 25, 18, 15, 7, 10});

            BaseColor headerBg = new BaseColor(44, 90, 160);
            String[] headers = {"Path ID", "Summary Step", "Action Step", "Test Data", "Expected Result", "Actual Result", "Tester"};
            for (String header : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(header, headerFont));
                cell.setBackgroundColor(headerBg);
                cell.setPadding(8);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                table.addCell(cell);
            }

            for (Map<String, Object> scenario : scenarios) {
                String pathId = (String) scenario.get("path_id");
                String description = scenario.get("readable_description") != null
                        ? (String) scenario.get("readable_description")
                        : (String) scenario.get("summary");

                // ✅ NOW includes [Lane] prefix
                List<String> actionSteps = extractActionStepsFromScenario((String) scenario.get("scenario_step"));

                List<Map<String, Object>> testData = processInputDataToList(scenario.get("input_data"));
                String formattedTestData = formatTestDataForTable(testData);

                String expectedResult = extractExpectedResult(scenario.get("expected_result"));

                if (actionSteps != null && !actionSteps.isEmpty()) {
                    int rowspan = actionSteps.size();

                    for (int i = 0; i < actionSteps.size(); i++) {
                        if (i == 0) {
                            PdfPCell cell = new PdfPCell(new Phrase(pathId, dataFont));
                            cell.setRowspan(rowspan);
                            cell.setPadding(5);
                            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                            table.addCell(cell);
                        }

                        if (i == 0) {
                            PdfPCell cell = new PdfPCell(new Phrase(description, dataFont));
                            cell.setRowspan(rowspan);
                            cell.setPadding(5);
                            cell.setVerticalAlignment(Element.ALIGN_TOP);
                            table.addCell(cell);
                        }

                        PdfPCell stepCell = new PdfPCell(new Phrase("Step " + (i + 1) + ": " + actionSteps.get(i), stepFont));
                        stepCell.setPadding(5);
                        stepCell.setVerticalAlignment(Element.ALIGN_TOP);
                        table.addCell(stepCell);

                        if (i == 0) {
                            PdfPCell cell = new PdfPCell(new Phrase(formattedTestData, stepFont));
                            cell.setRowspan(rowspan);
                            cell.setPadding(5);
                            cell.setVerticalAlignment(Element.ALIGN_TOP);
                            table.addCell(cell);
                        }

                        if (i == 0) {
                            PdfPCell cell = new PdfPCell(new Phrase(expectedResult, dataFont));
                            cell.setRowspan(rowspan);
                            cell.setPadding(5);
                            cell.setVerticalAlignment(Element.ALIGN_TOP);
                            table.addCell(cell);
                        }

                        if (i == 0) {
                            PdfPCell actualCell = new PdfPCell(new Phrase("", dataFont));
                            actualCell.setRowspan(rowspan);
                            actualCell.setPadding(5);
                            table.addCell(actualCell);
                        }

                        if (i == 0) {
                            PdfPCell cell = new PdfPCell(new Phrase(finalTesterName, dataFont));
                            cell.setRowspan(rowspan);
                            cell.setPadding(5);
                            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                            table.addCell(cell);
                        }
                    }
                } else {
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

    // SINGLE SCENARIO PDF
    public Resource generatePdfFromScenario(Map<String, Object> scenarioData) {
        try {
            Document document = new Document(PageSize.A4.rotate(), 36, 36, 54, 54);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            PdfWriter.getInstance(document, outputStream);

            document.open();

            Font titleFont  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, BaseColor.DARK_GRAY);
            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, BaseColor.WHITE);
            Font dataFont   = FontFactory.getFont(FontFactory.HELVETICA, 8, BaseColor.BLACK);
            Font stepFont   = FontFactory.getFont(FontFactory.HELVETICA, 7, BaseColor.BLACK);

            Paragraph title = new Paragraph("TEST SCENARIO REPORT", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(15);
            document.add(title);

            Paragraph fileInfo = new Paragraph(
                    "File: " + scenarioData.get("fileName") + " | Generated: " + new Date().toString(),
                    dataFont
            );
            fileInfo.setAlignment(Element.ALIGN_CENTER);
            fileInfo.setSpacingAfter(20);
            document.add(fileInfo);

            String pathId      = (String) scenarioData.get("pathId");
            String description = (String) scenarioData.get("description");

            @SuppressWarnings("unchecked")
            List<String> actionStepsRaw = (List<String>) scenarioData.get("actionSteps");
            List<String> actionSteps =
                    (actionStepsRaw != null && !actionStepsRaw.isEmpty())
                            ? actionStepsRaw
                            : extractActionStepsFromScenario((String) scenarioData.get("scenario_step"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> testDataList = (List<Map<String, Object>>) scenarioData.get("testData");
            String formattedTestData = formatTestDataForTable(testDataList);

            String expectedResult = (String) scenarioData.get("expectedResult");

            String finalTesterName = "";
            Boolean includeTester = (Boolean) scenarioData.get("includeTester");
            String testerName = (String) scenarioData.get("testerName");
            if (includeTester != null && includeTester && testerName != null && !testerName.trim().isEmpty()) {
                finalTesterName = testerName.trim();
            }

            PdfPTable table = new PdfPTable(7);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{10, 20, 25, 18, 15, 7, 10});

            BaseColor headerBg = new BaseColor(44, 90, 160);
            String[] headers = {"Path ID", "Summary Step", "Action Step", "Test Data", "Expected Result", "Actual Result", "Tester"};
            for (String h : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
                cell.setBackgroundColor(headerBg);
                cell.setPadding(8);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
                table.addCell(cell);
            }

            if (actionSteps != null && !actionSteps.isEmpty()) {
                int rowspan = actionSteps.size();

                for (int i = 0; i < actionSteps.size(); i++) {
                    if (i == 0) {
                        PdfPCell c = new PdfPCell(new Phrase(pathId != null ? pathId : "", dataFont));
                        c.setRowspan(rowspan);
                        c.setPadding(5);
                        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
                        table.addCell(c);
                    }

                    if (i == 0) {
                        PdfPCell c = new PdfPCell(new Phrase(description != null ? description : "", dataFont));
                        c.setRowspan(rowspan);
                        c.setPadding(5);
                        c.setVerticalAlignment(Element.ALIGN_TOP);
                        table.addCell(c);
                    }

                    String stepText = "Step " + (i + 1) + ": " + actionSteps.get(i);
                    PdfPCell stepCell = new PdfPCell(new Phrase(stepText, stepFont));
                    stepCell.setPadding(5);
                    stepCell.setVerticalAlignment(Element.ALIGN_TOP);
                    table.addCell(stepCell);

                    if (i == 0) {
                        PdfPCell c = new PdfPCell(new Phrase(formattedTestData != null ? formattedTestData : "-", stepFont));
                        c.setRowspan(rowspan);
                        c.setPadding(5);
                        c.setVerticalAlignment(Element.ALIGN_TOP);
                        table.addCell(c);
                    }

                    if (i == 0) {
                        PdfPCell c = new PdfPCell(new Phrase(expectedResult != null ? expectedResult : "-", dataFont));
                        c.setRowspan(rowspan);
                        c.setPadding(5);
                        c.setVerticalAlignment(Element.ALIGN_TOP);
                        table.addCell(c);
                    }

                    if (i == 0) {
                        PdfPCell c = new PdfPCell(new Phrase("", dataFont));
                        c.setRowspan(rowspan);
                        c.setPadding(5);
                        table.addCell(c);
                    }

                    if (i == 0) {
                        PdfPCell c = new PdfPCell(new Phrase(finalTesterName, dataFont));
                        c.setRowspan(rowspan);
                        c.setPadding(5);
                        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
                        table.addCell(c);
                    }
                }
            } else {
                table.addCell(new PdfPCell(new Phrase(pathId != null ? pathId : "", dataFont)));
                table.addCell(new PdfPCell(new Phrase(description != null ? description : "", dataFont)));
                table.addCell(new PdfPCell(new Phrase("-", stepFont)));
                table.addCell(new PdfPCell(new Phrase(formattedTestData != null ? formattedTestData : "-", stepFont)));
                table.addCell(new PdfPCell(new Phrase(expectedResult != null ? expectedResult : "-", dataFont)));
                table.addCell(new PdfPCell(new Phrase("", dataFont)));
                table.addCell(new PdfPCell(new Phrase(finalTesterName, dataFont)));
            }

            document.add(table);
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