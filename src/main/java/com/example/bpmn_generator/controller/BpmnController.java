package com.example.bpmn_generator.controller;

import com.example.bpmn_generator.entity.BpmnFile;
import com.example.bpmn_generator.entity.User;
import com.example.bpmn_generator.repository.BpmnRepository;
import com.example.bpmn_generator.repository.UserRepository;
import com.example.bpmn_generator.service.ApiService;
import com.example.bpmn_generator.service.BpmnResultService;
import com.example.bpmn_generator.service.BpmnService;
import com.example.bpmn_generator.service.ExportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.FlowElement;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.Lane;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/bpmn")
public class BpmnController {
    @Autowired
    private ApiService apiService;

    @Autowired
    private ExportService exportService;

    @Autowired
    private BpmnRepository bpmnRepository;

    @Autowired
    private BpmnService bpmnService;

    @Autowired
    private BpmnResultService bpmnResultService;
    @Autowired
    private UserRepository userRepository;

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null) ? auth.getName() : null;
    }


    private boolean isOwner(BpmnFile file, String username) {
        return file != null && file.getOwner() != null &&
                file.getOwner().getUsername().equals(username);
    }
    /* ================= List File (hanya milik user) ================= */
    @GetMapping("/files")
    public ResponseEntity<List<BpmnFile>> getAllFiles() {
        String username = currentUsername();
        if (username == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        // ambil semua file milik user ini
        User ownerRef = new User(); // proxy minimal (cukup username utk query? lebih aman pakai join fetch)
        ownerRef.setUsername(username);
        // karena findByOwner(User) butuh entity ter-managed, kita bisa filter manual:
        List<BpmnFile> all = bpmnRepository.findAll();
        List<BpmnFile> mine = all.stream()
                .filter(f -> f.getOwner() != null && username.equals(f.getOwner().getUsername()))
                .toList();

        return ResponseEntity.ok(mine);
    }


    @PostMapping("/files/{id}/generateScenario")
    public ResponseEntity<Map<String, String>> generateScenarios(@PathVariable Long id) {
        Map<String, String> response = new HashMap<>();

        try {
            System.out.println("Controller: Starting generateScenario for fileId: " + id);

            // Call instance method, not static
            bpmnResultService.generateScenario(id);

            response.put("status", "success");
            response.put("message", "Deskripsi & data pengujian berhasil digenerate.");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            System.err.println("Controller Error: " + e.getMessage());
            e.printStackTrace();

            response.put("status", "error");
            response.put("message", "Error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/files/{id}")
    public ResponseEntity<BpmnFile> getFileById(@PathVariable Long id) {
        String username = currentUsername();
        System.out.println("=== GET FILE DEBUG ===");
        System.out.println("Requested file ID: " + id);
        System.out.println("Current username: " + username);

        if (username == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        Optional<BpmnFile> fileOpt = bpmnRepository.findById(id);
        if (fileOpt.isEmpty()) {
            System.out.println("File not found!");
            return ResponseEntity.notFound().build();
        }

        BpmnFile file = fileOpt.get();
        System.out.println("File owner: " + (file.getOwner() != null ? file.getOwner().getUsername() : "NULL"));
        System.out.println("Is owner: " + isOwner(file, username));
        System.out.println("=====================\n");

        if (!isOwner(file, username)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        return ResponseEntity.ok(file);
    }


    /* ================= Upload (owner = current user) ================= */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadBpmn(@RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new LinkedHashMap<>();
        String username = currentUsername();
        if (username == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        try (InputStream inputStream = file.getInputStream()) {
            BpmnModelInstance modelInstance = Bpmn.readModelFromStream(inputStream);

            // lanes → mapping node → lane
            Map<String, String> taskToLane = new HashMap<>();
            Collection<Lane> lanes = modelInstance.getModelElementsByType(Lane.class);
            for (Lane lane : lanes) {
                String laneName = lane.getName();
                if (laneName == null || laneName.isBlank()) continue;
                for (FlowNode node : lane.getFlowNodeRefs()) {
                    taskToLane.put(node.getId(), laneName.trim());
                }
            }

            // elements meta + id→name
            Collection<FlowElement> flowElements = modelInstance.getModelElementsByType(FlowElement.class);
            List<Map<String, Object>> elementMetadata = new ArrayList<>();
            Map<String, String> idToName = new HashMap<>();
            for (FlowElement el : flowElements) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("id", el.getId());
                data.put("type", el.getElementType().getTypeName());

                String name = (el instanceof FlowNode) ? ((FlowNode) el).getName()
                        : (el instanceof SequenceFlow) ? ((SequenceFlow) el).getName()
                        : el.getAttributeValue("name");
                if (name == null || name.isBlank()) name = el.getId();

                data.put("name", name);
                String laneName = taskToLane.get(el.getId());
                if (laneName != null && !laneName.isBlank()) data.put("lane", laneName);
                elementMetadata.add(data);
                idToName.put(el.getId(), name);
            }

            // paths
            List<List<String>> pathsToUse = (lanes.isEmpty())
                    ? bpmnService.findAllPaths(modelInstance)
                    : bpmnService.PathsWithLane(modelInstance);

            List<String> flatPaths = pathsToUse.stream()
                    .map(path -> String.join(" -> ", path))
                    .collect(Collectors.toList());

            // scenarios
            List<Map<String, Object>> testScenarios = bpmnService.generateTestScenarios(modelInstance, taskToLane);
            for (Map<String, Object> scenario : testScenarios) {
                @SuppressWarnings("unchecked")
                List<String> rawPath = (List<String>) scenario.get("rawPath");
                String readablePath = rawPath.stream()
                        .map(idToName::get)
                        .collect(Collectors.joining(" -> "));
                scenario.put("scenario_path", readablePath);

                List<String> scenarioSteps = new ArrayList<>();
                for (String nodeId : rawPath) {
                    String lane = taskToLane.get(nodeId);
                    String readableNode = idToName.get(nodeId);
                    scenarioSteps.add((lane != null) ? String.format("-> [%s] %s.", lane, readableNode)
                            : String.format("-> %s.", readableNode));
                }
                scenario.put("scenario_step", String.join("\n", scenarioSteps));
            }

            String bpmnXml = bpmnService.toXml(modelInstance);

            // === PERBAIKAN: Load User dari database ===
            User owner = userRepository.findByUsername(username)
                    .orElseThrow(() -> new RuntimeException("User not found: " + username));

            BpmnFile savedFile = bpmnService.saveAndReturnEntity(
                    file, elementMetadata, flatPaths, testScenarios, bpmnXml, owner
            );

            response.put("id", savedFile.getId());
            response.put("fileName", savedFile.getOriginalFileName());
            response.put("elements", elementMetadata);
            response.put("paths", flatPaths);
            response.put("testScenarios", testScenarios);
            response.put("bpmnXml", bpmnXml);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("error", "Gagal membaca file BPMN: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }




    @PostMapping("/files/download/{id}")
    public ResponseEntity<Resource> downloadScenario(
            @PathVariable Long id,
            @RequestBody Map<String, Object> downloadRequest
    ) {
        Optional<BpmnFile> optionalFile = bpmnService.getFileById(id);
        if (optionalFile.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        BpmnFile file = optionalFile.get();
        try {
            // Extract download data from request
            String format = (String) downloadRequest.get("format");
            String pathId = (String) downloadRequest.get("pathId");
            String description = (String) downloadRequest.get("description");
            String expectedResult = (String) downloadRequest.get("expectedResult");
            String fileName = (String) downloadRequest.get("fileName");

            // Handle tester name logic - only include if checkbox was checked
            Boolean includeTester = (Boolean) downloadRequest.get("includeTester");
            String testerName = null;
            if (includeTester != null && includeTester) {
                testerName = (String) downloadRequest.get("testerName");
                // Ensure testerName is not empty
                if (testerName == null || testerName.trim().isEmpty()) {
                    testerName = null; // Will result in empty tester column
                }
            }

            @SuppressWarnings("unchecked")
            List<String> actionSteps = (List<String>) downloadRequest.get("actionSteps");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> testData = (List<Map<String, Object>>) downloadRequest.get("testData");

            // Create download data structure
            Map<String, Object> scenarioData = new HashMap<>();
            scenarioData.put("pathId", pathId);
            scenarioData.put("description", description);
            scenarioData.put("actionSteps", actionSteps);
            scenarioData.put("testData", testData);
            scenarioData.put("expectedResult", expectedResult);
            scenarioData.put("fileName", fileName);

            // *** KEY FIX: Add both includeTester flag and testerName ***
            scenarioData.put("includeTester", includeTester); // Add this line!
            scenarioData.put("testerName", testerName); // You already have this

            // Optional: Add some logging for debugging
            System.out.println("=== Download Request Debug ===");
            System.out.println("Include Tester: " + includeTester);
            System.out.println("Tester Name: " + testerName);
            System.out.println("Path ID: " + pathId);
            System.out.println("Format: " + format);

            Resource resource;
            String contentType;
            String fileExtension;

            if ("pdf".equalsIgnoreCase(format)) {
                // Generate PDF using existing method
                resource = exportService.generatePdfFromScenario(scenarioData);
                contentType = "application/pdf";
                fileExtension = ".pdf";
            } else {
                // Use the new custom Excel format
                resource = exportService.generateExcelFromScenario(scenarioData);
                contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                fileExtension = ".xlsx";
            }

            if (resource == null) {
                return ResponseEntity.internalServerError().build();
            }

            String downloadFileName = "test-scenario-" + pathId + fileExtension;

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadFileName + "\"")
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
    /* === helper: current User (username only, entity loaded lewat BpmnService/AuthService kalau perlu) === */



    @PatchMapping("/files/{fileId}/scenarios/{pathId}")
    public ResponseEntity<String> updateData(
            @PathVariable Long fileId,
            @PathVariable String pathId,
            @RequestBody Map<String, Object> updatedFields
    ) {
        String username = currentUsername();
        if (username == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        Optional<BpmnFile> fileOpt = bpmnRepository.findById(fileId);
        if (fileOpt.isEmpty()) return ResponseEntity.notFound().build();

        BpmnFile file = fileOpt.get();

        // ✅ Validasi ownership - pastikan hanya owner yang bisa edit
        if (!isOwner(file, username)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<Map<String, Object>> scenarios = file.getTestScenariosJson();

        boolean updated = false;
        for (Map<String, Object> scenario : scenarios) {
            if (scenario.get("path_id").equals(pathId)) {
                if (updatedFields.containsKey("input_data")) {
                    scenario.put("input_data", updatedFields.get("input_data"));
                }
                if (updatedFields.containsKey("scenario_step")) {
                    scenario.put("scenario_step", updatedFields.get("scenario_step"));
                }
                if (updatedFields.containsKey("readable_description")) {
                    scenario.put("readable_description", updatedFields.get("readable_description"));
                }
                if (updatedFields.containsKey("expected_result")) {
                    scenario.put("expected_result", updatedFields.get("expected_result"));
                }
                updated = true;
                break;
            }
        }

        if (!updated) return ResponseEntity.notFound().build();

        file.setTestScenariosJson(scenarios);
        bpmnRepository.save(file); // ✅ Ganti dari bpmnService.updateFile(file)

        return ResponseEntity.ok("Skenario berhasil diperbarui!");
    }

    @GetMapping("/files/{id}/flow-sequences")
    public ResponseEntity<Map<String, Object>> getFlowSequences(@PathVariable Long id) {
        Optional<BpmnFile> optionalFile = bpmnService.getFileById(id);
        if (optionalFile.isEmpty()) return ResponseEntity.notFound().build();

        BpmnFile file = optionalFile.get();
        try {
            BpmnModelInstance modelInstance = Bpmn.readModelFromStream(
                    new java.io.ByteArrayInputStream(file.getBpmnXml().getBytes())
            );

            List<List<String>> rawPaths = bpmnService.findAllPaths(modelInstance);
            Map<String, FlowNode> nodeMap = new HashMap<>();
            for (FlowNode node : modelInstance.getModelElementsByType(FlowNode.class)) {
                nodeMap.put(node.getId(), node);
            }

            List<List<String>> readablePaths = rawPaths.stream()
                    .map(path -> path.stream()
                            .map(nodeId -> {
                                FlowNode node = nodeMap.get(nodeId);
                                String name = node.getName();
                                return (name != null && !name.isBlank()) ? name : node.getElementType().getTypeName();
                            }).toList())
                    .toList();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("fileId", file.getId());
            response.put("fileName", file.getOriginalFileName());
            response.put("totalSequences", readablePaths.size());
            response.put("flowSequences", readablePaths);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "Gagal memproses BPMN XML"));
        }
    }

    @GetMapping("/files/{id}/scenarios")
    public ResponseEntity<List<Map<String, Object>>> getScenarios(@PathVariable Long id) {
        Optional<BpmnFile> fileOpt = bpmnRepository.findById(id);
        return fileOpt.map(file -> ResponseEntity.ok(file.getTestScenariosJson()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(null));
    }
}
