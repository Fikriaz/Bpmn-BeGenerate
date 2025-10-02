package com.example.bpmn_generator.service;

import com.example.bpmn_generator.entity.BpmnFile;
import com.example.bpmn_generator.entity.BpmnResult;
import com.example.bpmn_generator.repository.BpmnRepository;
import com.example.bpmn_generator.repository.BpmnResultRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class BpmnResultService {

    @Autowired
    private BpmnResultRepository bpmnResultRepository;

    @Autowired
    private BpmnRepository bpmnRepository;

    @Autowired
    private ApiService apiService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Update method untuk generateScenario dengan penyimpanan ke BpmnResult
     */
    public void generateScenario(Long fileId) {
        Optional<BpmnFile> fileOpt = bpmnRepository.findById(fileId);
        if (fileOpt.isEmpty()) {
            System.err.println("❌ File dengan ID " + fileId + " tidak ditemukan");
            return;
        }

        BpmnFile file = fileOpt.get();
        file.setGeneratingScenario(true);
        file.setScenarioReady(false);
        bpmnRepository.save(file);

        try {
            // Clear existing BPMN results for this file
            List<BpmnResult> existingResults = bpmnResultRepository.findByBpmnFileId(fileId);
            if (!existingResults.isEmpty()) {
                bpmnResultRepository.deleteAll(existingResults);
                System.out.println("🗑️ Menghapus " + existingResults.size() + " BPMN results lama");
            }

            String context = getAutoProcessContext(file);
            System.out.println("📋 Context: " + context);

            // Build mappings (same as original code)
            Map<String, String> idToLabel = new HashMap<>();
            Map<String, String> idToTaskType = new HashMap<>();
            Map<String, Map<String, Object>> taskDetails = new HashMap<>();

            for (Map<String, Object> element : file.getElementsJson()) {
                String id = (String) element.get("id");
                String name = (String) element.getOrDefault("name", "");
                String type = (String) element.getOrDefault("type", "");

                if (id != null && !id.isBlank()) {
                    String taskType = determineTaskType(type, element);
                    idToTaskType.put(id, taskType);
                    taskDetails.put(id, new HashMap<>(element));
                    String baseLabel = (name != null && !name.isBlank()) ? name :
                            (type != null && !type.isBlank()) ? type : id;
                    String labelWithType = enhanceLabelWithTaskType(baseLabel, taskType);
                    idToLabel.put(id, labelWithType);
                    System.out.println("🏷️ Task: " + id + " -> " + labelWithType + " [" + taskType + "]");
                }
            }

            // Build lane mapping
            Map<String, String> taskToLane = new HashMap<>();
            Set<String> uniqueLanes = new HashSet<>();

            for (Map<String, Object> element : file.getElementsJson()) {
                if (element.containsKey("lane")) {
                    String nodeId = (String) element.get("id");
                    String laneName = (String) element.get("lane");
                    if (nodeId != null && laneName != null && !laneName.isBlank()) {
                        taskToLane.put(nodeId, laneName);
                        uniqueLanes.add(laneName);
                    }
                }
            }

            List<String> lanes = new ArrayList<>(uniqueLanes);
            boolean hasMultipleLanes = lanes.size() > 1;
            System.out.println("🏊 Lanes detected: " + lanes.size() + " lanes -> " + lanes);

            // Print task type summary
            printTaskTypeSummary(idToTaskType);

            List<String> allPaths = file.getPathsJson();
            System.out.println("🔄 Memproses " + allPaths.size() + " paths...");

            List<Map<String, Object>> scenarios = new ArrayList<>();
            List<BpmnResult> bpmnResults = new ArrayList<>();

            for (int i = 0; i < allPaths.size(); i++) {
                String pathStr = allPaths.get(i);
                System.out.println("🔄 Processing path " + (i + 1) + "/" + allPaths.size() + ": " + pathStr);

                try {
                    List<String> rawPath = Arrays.stream(pathStr.split("->"))
                            .map(String::trim)
                            .collect(Collectors.toList());

                    List<String> stepsForGPT = buildStepsForGPTWithTaskTypes(rawPath, idToLabel,
                            idToTaskType, taskToLane, hasMultipleLanes);

                    System.out.println("📝 Steps for GPT: " + stepsForGPT);

                    Map<String, String> gptResult = generateWithRetry(stepsForGPT, context, 3);
                    String scenarioStep = gptResult.get("scenario_step");
                    String validatedScenarioStep = validateAndFixScenarioStep(scenarioStep, stepsForGPT, hasMultipleLanes);
                    gptResult.put("scenario_step", validatedScenarioStep);

                    Map<String, Object> inputData = parseJsonSafely(gptResult.get("input_data"));
                    Map<String, Object> expectedResult = parseJsonSafely(gptResult.get("expected_result"));

                    String pathId = "P" + (i + 1);

                    // Simpan ke BpmnResult
                    BpmnResult bpmnResult = new BpmnResult();
                    bpmnResult.setBpmnFile(file);
                    bpmnResult.setPathId(pathId);
                    bpmnResult.setSummary(gptResult.getOrDefault("summary", "Pengujian alur proses end-to-end"));
                    bpmnResult.setDescription(gptResult.get("description")); // langsung dari "description"
                    bpmnResult.setScenarioStep(validatedScenarioStep);
                    bpmnResult.setTestData(objectMapper.writeValueAsString(inputData)); // inputData -> testData
                    bpmnResult.setExpectedResult(objectMapper.writeValueAsString(expectedResult));

                    bpmnResults.add(bpmnResult);

                    // Build scenario object untuk backward compatibility
                    Map<String, Object> scenario = new LinkedHashMap<>();
                    scenario.put("path_id", pathId);
                    scenario.put("scenario_path", pathStr);
                    scenario.put("rawPath", rawPath);
                    scenario.put("task_types", getTaskTypesForPath(rawPath, idToTaskType));
                    scenario.put("scenario_step", validatedScenarioStep);
                    scenario.put("readable_description", gptResult.get("description")); // readable_description untuk backward compatibility
                    scenario.put("input_data", inputData); // input_data untuk backward compatibility
                    scenario.put("expected_result", expectedResult);
                    scenario.put("summary", gptResult.getOrDefault("summary", "Pengujian alur proses end-to-end"));

                    scenarios.add(scenario);
                    System.out.println("✅ Path " + (i + 1) + " berhasil diproses");

                } catch (Exception e) {
                    System.err.println("❌ Error processing path " + (i + 1) + ": " + e.getMessage());

                    // Create fallback for both scenarios and BPMN results
                    Map<String, Object> fallbackScenario = createFallbackScenarioWithTaskTypes(pathStr, i + 1,
                            idToLabel, idToTaskType, taskToLane, hasMultipleLanes);
                    scenarios.add(fallbackScenario);

                    // Create fallback BPMN result
                    BpmnResult fallbackBpmnResult = new BpmnResult();
                    fallbackBpmnResult.setBpmnFile(file);
                    fallbackBpmnResult.setPathId("P" + (i + 1));
                    fallbackBpmnResult.setSummary("Fallback scenario");
                    fallbackBpmnResult.setDescription("Scenario dibuat otomatis karena error dalam generate");
                    fallbackBpmnResult.setScenarioStep("1. Jalankan path: " + pathStr);
                    fallbackBpmnResult.setTestData("{}");
                    fallbackBpmnResult.setExpectedResult("{\"status\":\"success\"}");

                    bpmnResults.add(fallbackBpmnResult);
                }
            }

            // Batch save BPMN results
            bpmnResultRepository.saveAll(bpmnResults);
            System.out.println("💾 Menyimpan " + bpmnResults.size() + " BPMN results ke database");

            String summary = scenarios.stream()
                    .map(s -> (String) s.get("summary"))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse("ringkasan tidak tersedia");

            // Update file dengan scenarios (backward compatibility)
            file.setSummary(summary);
            file.setTestScenariosJson(scenarios);
            file.setGeneratingScenario(false);
            file.setScenarioReady(true);
            bpmnRepository.save(file);

            System.out.println("✅ Generate scenario selesai! Total: " + scenarios.size() + " scenarios, " + bpmnResults.size() + " BPMN results disimpan");

        } catch (Exception e) {
            System.err.println("❌ Fatal error dalam generate scenario: " + e.getMessage());
            e.printStackTrace();

            file.setGeneratingScenario(false);
            file.setScenarioReady(false);
            bpmnRepository.save(file);
        }
    }

    /**
     * Menyimpan hasil BPMN baru
     */
    public BpmnResult saveBpmnResult(Long bpmnFileId, String pathId, String summary,
                                     String description, String scenarioStep,
                                     Object testData, Object expectedResult) {

        Optional<BpmnFile> fileOpt = bpmnRepository.findById(bpmnFileId);
        if (fileOpt.isEmpty()) {
            throw new IllegalArgumentException("BPMN File tidak ditemukan dengan ID: " + bpmnFileId);
        }

        try {
            BpmnResult bpmnResult = new BpmnResult();
            bpmnResult.setBpmnFile(fileOpt.get());
            bpmnResult.setPathId(pathId);
            bpmnResult.setSummary(summary);
            bpmnResult.setDescription(description);
            bpmnResult.setScenarioStep(scenarioStep);

            if (testData != null) {
                bpmnResult.setTestData(objectMapper.writeValueAsString(testData));
            }

            if (expectedResult != null) {
                bpmnResult.setExpectedResult(objectMapper.writeValueAsString(expectedResult));
            }

            return bpmnResultRepository.save(bpmnResult);

        } catch (Exception e) {
            throw new RuntimeException("Error saat menyimpan BPMN result: " + e.getMessage(), e);
        }
    }

    /**
     * Menyimpan hasil LLM dari testScenariosJson yang sudah ada
     */
    public void migrateBpmnToResults(Long bpmnFileId) {
        Optional<BpmnFile> fileOpt = bpmnRepository.findById(bpmnFileId);
        if (fileOpt.isEmpty()) {
            throw new IllegalArgumentException("BPMN File tidak ditemukan dengan ID: " + bpmnFileId);
        }

        BpmnFile bpmnFile = fileOpt.get();
        List<Map<String, Object>> testScenarios = bpmnFile.getTestScenariosJson();

        if (testScenarios == null || testScenarios.isEmpty()) {
            System.out.println("⚠️ Tidak ada test scenarios untuk dimigrasi");
            return;
        }

        List<BpmnResult> bpmnResults = new ArrayList<>();

        for (Map<String, Object> scenario : testScenarios) {
            try {
                BpmnResult bpmnResult = new BpmnResult();
                bpmnResult.setBpmnFile(bpmnFile);
                bpmnResult.setPathId((String) scenario.get("path_id"));
                bpmnResult.setSummary((String) scenario.get("summary"));

                // Transform readable_description -> description
                bpmnResult.setDescription((String) scenario.get("readable_description"));
                bpmnResult.setScenarioStep((String) scenario.get("scenario_step"));

                // Transform input_data -> testData (JSON)
                Object inputData = scenario.get("input_data");
                if (inputData != null) {
                    bpmnResult.setTestData(objectMapper.writeValueAsString(inputData));
                }

                // Expected result (JSON)
                Object expectedResult = scenario.get("expected_result");
                if (expectedResult != null) {
                    bpmnResult.setExpectedResult(objectMapper.writeValueAsString(expectedResult));
                }

                bpmnResults.add(bpmnResult);

            } catch (Exception e) {
                System.err.println("❌ Error saat migrasi scenario: " + e.getMessage());
            }
        }

        // Batch save
        bpmnResultRepository.saveAll(bpmnResults);
        System.out.println("✅ Berhasil migrasi " + bpmnResults.size() + " BPMN results");
    }

    /**
     * Mengambil BPMN results berdasarkan BPMN file ID
     */
    public List<BpmnResult> getBpmnResultsByFileId(Long fileId) {
        return bpmnResultRepository.findByBpmnFileIdOrderByPathId(fileId);
    }

    /**
     * Mengambil BPMN result berdasarkan path ID
     */
    public Optional<BpmnResult> getBpmnResultByPathId(Long fileId, String pathId) {
        return bpmnResultRepository.findByBpmnFileIdAndPathId(fileId, pathId);
    }

    /**
     * Update BPMN result
     */
    public BpmnResult updateBpmnResult(Long id, String summary, String description,
                                       String scenarioStep, Object testData, Object expectedResult) {
        Optional<BpmnResult> existingOpt = bpmnResultRepository.findById(id);
        if (existingOpt.isEmpty()) {
            throw new IllegalArgumentException("BPMN Result tidak ditemukan dengan ID: " + id);
        }

        try {
            BpmnResult existing = existingOpt.get();
            existing.setSummary(summary);
            existing.setDescription(description);
            existing.setScenarioStep(scenarioStep);

            if (testData != null) {
                existing.setTestData(objectMapper.writeValueAsString(testData));
            }

            if (expectedResult != null) {
                existing.setExpectedResult(objectMapper.writeValueAsString(expectedResult));
            }

            return bpmnResultRepository.save(existing);

        } catch (Exception e) {
            throw new RuntimeException("Error saat update BPMN result: " + e.getMessage(), e);
        }
    }

    /**
     * Helper method untuk parsing JSON dengan aman
     */
    public Map<String, Object> parseTestData(String testDataJson) {
        try {
            if (testDataJson == null || testDataJson.trim().isEmpty()) {
                return new HashMap<>();
            }
            return objectMapper.readValue(testDataJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            System.err.println("❌ Error parsing test data JSON: " + e.getMessage());
            return new HashMap<>();
        }
    }

    public Map<String, Object> parseExpectedResult(String expectedResultJson) {
        try {
            if (expectedResultJson == null || expectedResultJson.trim().isEmpty()) {
                return new HashMap<>();
            }
            return objectMapper.readValue(expectedResultJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            System.err.println("❌ Error parsing expected result JSON: " + e.getMessage());
            return new HashMap<>();
        }
    }

    // Helper methods yang sudah diimplementasikan
    private String getAutoProcessContext(BpmnFile file) {
        StringBuilder contextBuilder = new StringBuilder();

        // Extract filename as process name
        String fileName = file.getOriginalFileName() != null ? file.getOriginalFileName() : "Unknown Process";
        contextBuilder.append("Proses bisnis: ").append(fileName.replace(".bpmn", "")).append("\n");

        // Add basic context
        contextBuilder.append("Ini adalah proses bisnis yang perlu diuji secara menyeluruh untuk memastikan setiap langkah berjalan sesuai dengan yang diharapkan. ");
        contextBuilder.append("Setiap path dalam proses ini harus divalidasi dengan data input yang sesuai dan menghasilkan output yang benar.");

        return contextBuilder.toString();
    }

    private String determineTaskType(String type, Map<String, Object> element) {
        if (type == null || type.isEmpty()) {
            return "TASK";
        }

        switch (type.toLowerCase()) {
            case "startevent":
                return "START";
            case "endevent":
                return "END";
            case "usertask":
                return "USER_TASK";
            case "servicetask":
                return "SERVICE_TASK";
            case "gateway":
            case "exclusivegateway":
            case "parallelgateway":
                return "GATEWAY";
            case "intermediatecatchevent":
            case "intermediatethrowevent":
                return "INTERMEDIATE_EVENT";
            case "subprocess":
                return "SUBPROCESS";
            default:
                return "TASK";
        }
    }

    private String enhanceLabelWithTaskType(String baseLabel, String taskType) {
        if (baseLabel == null || baseLabel.trim().isEmpty()) {
            return taskType;
        }

        // Don't add task type if it's already descriptive
        if (baseLabel.toLowerCase().contains("start") ||
                baseLabel.toLowerCase().contains("end") ||
                baseLabel.toLowerCase().contains("gateway") ||
                baseLabel.toLowerCase().contains("event")) {
            return baseLabel;
        }

        return baseLabel;
    }

    private void printTaskTypeSummary(Map<String, String> idToTaskType) {
        System.out.println("📊 Task Type Summary:");
        Map<String, Long> typeCounts = idToTaskType.values().stream()
                .collect(Collectors.groupingBy(type -> type, Collectors.counting()));

        typeCounts.forEach((type, count) ->
                System.out.println("   " + type + ": " + count + " tasks"));
    }

    private List<String> buildStepsForGPTWithTaskTypes(List<String> rawPath, Map<String, String> idToLabel,
                                                       Map<String, String> idToTaskType, Map<String, String> taskToLane,
                                                       boolean hasMultipleLanes) {
        List<String> steps = new ArrayList<>();

        for (String nodeId : rawPath) {
            String label = idToLabel.getOrDefault(nodeId, nodeId);
            String taskType = idToTaskType.getOrDefault(nodeId, "UNKNOWN");
            String lane = taskToLane.get(nodeId);

            // Build readable step description
            StringBuilder stepBuilder = new StringBuilder();
            stepBuilder.append(label);

            // Add lane information if multiple lanes exist
            if (hasMultipleLanes && lane != null && !lane.trim().isEmpty()) {
                stepBuilder.append(" [").append(lane).append("]");
            }

            steps.add(stepBuilder.toString());
        }

        return steps;
    }

    /**
     * FIXED: Method untuk memanggil AI dengan retry logic
     */
    private Map<String, String> generateWithRetry(List<String> stepsForGPT, String context, int retries) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                System.out.println("🤖 Calling AI API (attempt " + attempt + "/" + retries + ")");

                // Panggil ApiService yang sudah ada
                Map<String, String> result = apiService.generate_bpmn(stepsForGPT, context);

                // Validasi hasil AI
                if (result != null && !result.isEmpty() &&
                        result.containsKey("description") &&
                        !result.get("description").startsWith("❌")) {

                    System.out.println("✅ AI API call successful on attempt " + attempt);
                    return result;
                } else {
                    throw new RuntimeException("AI returned invalid or error response");
                }

            } catch (Exception e) {
                lastException = e;
                System.err.println("❌ AI API call failed (attempt " + attempt + "/" + retries + "): " + e.getMessage());

                if (attempt < retries) {
                    try {
                        Thread.sleep(1000 * attempt); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // Jika semua attempt gagal, return fallback response
        System.err.println("❌ All AI API attempts failed, using fallback response");
        Map<String, String> fallback = new HashMap<>();
        fallback.put("summary", "Pengujian alur proses (fallback)");
        fallback.put("description", "Skenario dibuat otomatis karena AI tidak tersedia: " +
                (lastException != null ? lastException.getMessage() : "Unknown error"));
        fallback.put("scenario_step", String.join("\n", stepsForGPT));
        fallback.put("input_data", "{\"test\": \"data\"}");
        fallback.put("expected_result", "{\"status\": \"success\"}");
        return fallback;
    }

    private String validateAndFixScenarioStep(String scenarioStep, List<String> stepsForGPT, boolean hasMultipleLanes) {
        if (scenarioStep == null || scenarioStep.trim().isEmpty()) {
            // Create numbered steps from original steps
            List<String> numberedSteps = new ArrayList<>();
            for (int i = 0; i < stepsForGPT.size(); i++) {
                numberedSteps.add((i + 1) + ". " + stepsForGPT.get(i));
            }
            return String.join("\n", numberedSteps);
        }

        // Ensure proper numbering
        String[] lines = scenarioStep.split("\n");
        List<String> fixedLines = new ArrayList<>();
        int stepNumber = 1;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Remove existing numbering and add consistent numbering
            String cleanLine = line.replaceAll("^\\d+\\.\\s*", "").trim();
            if (!cleanLine.isEmpty()) {
                fixedLines.add(stepNumber + ". " + cleanLine);
                stepNumber++;
            }
        }

        return String.join("\n", fixedLines);
    }

    private Map<String, Object> parseJsonSafely(String jsonString) {
        try {
            if (jsonString == null || jsonString.trim().isEmpty()) {
                return new HashMap<>();
            }
            return objectMapper.readValue(jsonString, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            System.err.println("❌ Error parsing JSON: " + e.getMessage());
            return new HashMap<>();
        }
    }

    private Map<String, String> getTaskTypesForPath(List<String> rawPath, Map<String, String> idToTaskType) {
        Map<String, String> pathTaskTypes = new LinkedHashMap<>();
        for (String nodeId : rawPath) {
            String taskType = idToTaskType.getOrDefault(nodeId, "UNKNOWN");
            pathTaskTypes.put(nodeId, taskType);

        }
        return pathTaskTypes;
    }

    private Map<String, Object> createFallbackScenarioWithTaskTypes(String pathStr, int pathIndex,
                                                                    Map<String, String> idToLabel,
                                                                    Map<String, String> idToTaskType,
                                                                    Map<String, String> taskToLane,
                                                                    boolean hasMultipleLanes) {
        List<String> rawPath = Arrays.stream(pathStr.split("->"))
                .map(String::trim)
                .collect(Collectors.toList());

        // Create meaningful fallback steps
        List<String> fallbackSteps = new ArrayList<>();
        for (int i = 0; i < rawPath.size(); i++) {
            String nodeId = rawPath.get(i);
            String label = idToLabel.getOrDefault(nodeId, nodeId);
            String lane = taskToLane.get(nodeId);

            StringBuilder stepBuilder = new StringBuilder();
            stepBuilder.append(i + 1).append(". Jalankan ").append(label);

            if (hasMultipleLanes && lane != null) {
                stepBuilder.append(" pada lane ").append(lane);
            }

            fallbackSteps.add(stepBuilder.toString());
        }

        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("path_id", "P" + pathIndex);
        scenario.put("scenario_path", pathStr);
        scenario.put("rawPath", rawPath);
        scenario.put("task_types", getTaskTypesForPath(rawPath, idToTaskType));
        scenario.put("scenario_step", String.join("\n", fallbackSteps));
        scenario.put("readable_description", "Skenario fallback untuk path " + pathIndex + " - menjalankan serangkaian langkah dalam proses bisnis sesuai dengan alur yang telah ditentukan");
        scenario.put("input_data", Map.of("status", "ready", "data", "fallback"));
        scenario.put("expected_result", Map.of("status", "success", "message", "Path completed successfully"));
        scenario.put("summary", "Pengujian alur proses (fallback) - memastikan setiap langkah dapat dijalankan dengan benar");

        return scenario;
    }


}