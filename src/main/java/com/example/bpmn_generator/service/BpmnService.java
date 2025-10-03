package com.example.bpmn_generator.service;

import com.example.bpmn_generator.entity.BpmnFile;
import com.example.bpmn_generator.repository.BpmnRepository;
import com.example.bpmn_generator.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.*;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BpmnService {

    @Autowired
    private BpmnRepository bpmnRepository;

    @Autowired
    private ApiService apiService;

    @Autowired
    private DfsService dfsService;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.file.upload-dir}")
    private String uploadDir;

    @Autowired
    private UserRepository userRepository;

    public List<BpmnFile> getAllFiles() {
        return bpmnRepository.findAll();
    }
    // helper
    private com.example.bpmn_generator.entity.User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        String username = auth.getName();
        return userRepository.findByUsername(username).orElse(null);
    }
    public Optional<BpmnFile> getFileById(Long id) {
        return bpmnRepository.findById(id);
    }

    public Path getFilePath(String storedFileName) {
        return Paths.get(uploadDir).resolve(storedFileName);
    }

    public List<List<String>> findAllPaths(BpmnModelInstance modelInstance) {
        return dfsService.findAllPaths(modelInstance);
    }

    public List<List<String>> PathsWithLane(BpmnModelInstance modelInstance) {
        return dfsService.findAllPathsWithActor(modelInstance);
    }

    public List<Map<String, Object>> generateTestScenarios(
            BpmnModelInstance modelInstance,
            Map<String, String> taskToLane
    ) {
        List<List<String>> paths = findAllPaths(modelInstance);

        Map<String, FlowNode> nodeMap = new HashMap<>();
        for (FlowNode node : modelInstance.getModelElementsByType(FlowNode.class)) {
            nodeMap.put(node.getId(), node);
        }

        List<Map<String, Object>> scenarios = new ArrayList<>();
        boolean hasLanes = (taskToLane != null && !taskToLane.isEmpty());

        for (List<String> path : paths) {
            Map<String, Object> scenario = new LinkedHashMap<>();

            List<String> readablePath = path.stream()
                    .map(id -> {
                        FlowNode node = nodeMap.get(id);
                        String name = node.getName();
                        String type = node.getElementType().getTypeName();
                        return (name != null && !name.isBlank()) ? name : type;
                    })
                    .collect(Collectors.toList());

            scenario.put("path", readablePath);
            scenario.put("rawPath", path);

            StringBuilder steps = new StringBuilder();
            for (String nodeId : path) {
                FlowNode node = nodeMap.get(nodeId);
                String name = node.getName() != null ? node.getName() : node.getElementType().getTypeName();
                String lane = hasLanes ? taskToLane.get(nodeId) : null;

                if (lane != null && !lane.isBlank()) {
                    steps.append(String.format("-> [%s] %s%n", lane, name));
                } else {
                    steps.append(String.format("-> %s%n", name));
                }
            }
            scenario.put("scenario_step", steps.toString().trim());

            Map<String, String> testData = new LinkedHashMap<>();
            for (String nodeId : path) {
                FlowNode node = nodeMap.get(nodeId);
                if (node instanceof Task) {
                    String taskName = node.getName() != null ? node.getName().toLowerCase() : "task";
                    String key = taskName.replace(" ", "_");
                    testData.put(key, "sample_value");
                }
            }
            scenario.put("input_data", testData);
            scenario.put("expected_result", Map.of("status", "success", "message", "Proses selesai berhasil"));

            scenarios.add(scenario);
        }
        return scenarios;
    }

    public BpmnFile saveAndReturnEntity(
            MultipartFile file,
            List<Map<String, Object>> elementsJson,
            List<String> paths,
            List<Map<String, Object>> testScenarios,
            String bpmnXml,
            com.example.bpmn_generator.entity.User owner
    ) throws IOException {
        String originalFileName = file.getOriginalFilename();
        String storedFileName = java.util.UUID.randomUUID() + "_" + originalFileName;

        java.nio.file.Path uploadPath = java.nio.file.Paths.get(uploadDir);
        if (!java.nio.file.Files.exists(uploadPath)) {
            java.nio.file.Files.createDirectories(uploadPath);
        }

        java.nio.file.Path filePath = uploadPath.resolve(storedFileName);
        java.nio.file.Files.copy(file.getInputStream(), filePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        BpmnFile bpmnFile = new BpmnFile();
        bpmnFile.setOriginalFileName(originalFileName);
        bpmnFile.setStoredFileName(storedFileName);
        bpmnFile.setUploadedAt(java.time.LocalDateTime.now());
        bpmnFile.setElementsJson(elementsJson);
        bpmnFile.setPathsJson(paths);
        bpmnFile.setTestScenariosJson(testScenarios);
        bpmnFile.setBpmnXml(bpmnXml);
        bpmnFile.setOwner(owner); // <=== penting
        return bpmnRepository.save(bpmnFile);
    }

    public String toXml(BpmnModelInstance modelInstance) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Bpmn.writeModelToStream(outputStream, modelInstance);
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    public List<String> extractLanePov(BpmnModelInstance modelInstance) {
        Set<String> actors = new LinkedHashSet<>();

        for (Lane lane : modelInstance.getModelElementsByType(Lane.class)) {
            String laneName = lane.getName();
            if (laneName != null && !laneName.isBlank()) actors.add(laneName.trim());
        }

        for (Participant participant : modelInstance.getModelElementsByType(Participant.class)) {
            String participantName = participant.getName();
            if (participantName != null && !participantName.isBlank()) actors.add(participantName.trim());
        }

        if (actors.isEmpty()) {
            for (Process process : modelInstance.getModelElementsByType(Process.class)) {
                String processName = process.getName();
                if (processName != null && !processName.isBlank()) actors.add(processName.trim());
            }
        }

        return new ArrayList<>(actors);
    }

    private Map<String, String> buildNodeToLaneMapping(BpmnModelInstance modelInstance) {
        Map<String, String> nodeToLane = new HashMap<>();

        // PRIORITAS 1: Lane standar (getModelElementsByType - global)
        for (Lane lane : modelInstance.getModelElementsByType(Lane.class)) {
            String laneName = lane.getName();
            if (laneName == null || laneName.isBlank()) continue;
            String ln = laneName.trim();

            // Assign semua FlowNode yang direferensi oleh lane
            for (FlowNode n : lane.getFlowNodeRefs()) {
                nodeToLane.put(n.getId(), ln);
            }
        }

        // PRIORITAS 1.5: Untuk setiap lane, cari SubProcess dan assign semua isinya
        for (Lane lane : modelInstance.getModelElementsByType(Lane.class)) {
            String laneName = lane.getName();
            if (laneName == null || laneName.isBlank()) continue;
            String ln = laneName.trim();

            for (FlowNode n : lane.getFlowNodeRefs()) {
                if (n instanceof SubProcess) {
                    // Assign SEMUA children dalam subprocess (termasuk StartEvent, EndEvent, dll)
                    assignLaneToAllElements(((SubProcess) n).getFlowElements(), ln, nodeToLane);
                }
            }
        }

        // PRIORITAS 1.6: EventSubProcess - subprocess yang triggered by events
        for (Process proc : modelInstance.getModelElementsByType(Process.class)) {
            for (FlowElement element : proc.getFlowElements()) {
                if (element instanceof SubProcess) {
                    SubProcess sp = (SubProcess) element;
                    // Check if it's an EventSubProcess
                    if (sp.triggeredByEvent()) {
                        String parentLane = nodeToLane.get(sp.getId());
                        if (parentLane != null && !parentLane.isBlank()) {
                            assignLaneToAllElements(sp.getFlowElements(), parentLane, nodeToLane);
                        }
                    }
                }
            }
        }

        // PRIORITAS 2: Lane via Process → LaneSet → Lane (nested structure)
        for (Process proc : modelInstance.getModelElementsByType(Process.class)) {
            Collection<LaneSet> laneSets = proc.getLaneSets();
            if (laneSets != null && !laneSets.isEmpty()) {
                for (LaneSet ls : laneSets) {
                    for (Lane lane : ls.getLanes()) {
                        String laneName = lane.getName();
                        if (laneName == null || laneName.isBlank()) continue;
                        String ln = laneName.trim();

                        for (FlowNode n : lane.getFlowNodeRefs()) {
                            nodeToLane.put(n.getId(), ln);

                            // Jika ada SubProcess, assign isinya juga
                            if (n instanceof SubProcess) {
                                assignLaneToAllElements(((SubProcess) n).getFlowElements(), ln, nodeToLane);
                            }
                        }
                    }
                }
            }
        }

        // PRIORITAS 3: Participant (Pool) → Process → FlowElements
        for (Participant participant : modelInstance.getModelElementsByType(Participant.class)) {
            String participantName = participant.getName();
            if (participantName == null || participantName.isBlank()) continue;
            String poolName = participantName.trim();

            Process proc = participant.getProcess();
            if (proc == null) continue;

            // Cek dulu apakah process ini punya lanes
            boolean hasLanesInProcess = false;
            Collection<LaneSet> laneSets = proc.getLaneSets();
            if (laneSets != null && !laneSets.isEmpty()) {
                for (LaneSet ls : laneSets) {
                    if (!ls.getLanes().isEmpty()) {
                        hasLanesInProcess = true;
                        break;
                    }
                }
            }

            // HANYA assign participant name jika process TIDAK punya lanes
            if (!hasLanesInProcess) {
                // Gunakan helper untuk assign ke SEMUA elements (termasuk dalam SubProcess)
                assignLaneToAllElements(proc.getFlowElements(), poolName, nodeToLane);
            }
        }

        // PRIORITAS 4.5: Global scan untuk special subprocess types
        // Handle CallActivity, Transaction, EventSubProcess, AdHoc SubProcess
        for (SubProcess sp : modelInstance.getModelElementsByType(SubProcess.class)) {
            String spLane = nodeToLane.get(sp.getId());
            if (spLane == null || spLane.isBlank()) continue;

            // Assign ke semua children (akan handle semua tipe subprocess)
            assignLaneToAllElements(sp.getFlowElements(), spLane, nodeToLane);
        }

        // PRIORITAS 4.6: CallActivity - assign lane from parent context
        for (CallActivity ca : modelInstance.getModelElementsByType(CallActivity.class)) {
            if (nodeToLane.containsKey(ca.getId())) {
                // Already mapped, skip
                continue;
            }

            // Try to find lane from surrounding context (same level elements)
            String contextLane = findContextLane(ca, nodeToLane, modelInstance);
            if (contextLane != null && !contextLane.isBlank()) {
                nodeToLane.put(ca.getId(), contextLane);
            }
        }

        // PRIORITAS 4.7: Transaction - special type of subprocess
        for (Transaction tx : modelInstance.getModelElementsByType(Transaction.class)) {
            String txLane = nodeToLane.get(tx.getId());
            if (txLane != null && !txLane.isBlank()) {
                assignLaneToAllElements(tx.getFlowElements(), txLane, nodeToLane);
            }
        }
        for (Process proc : modelInstance.getModelElementsByType(Process.class)) {
            // Skip jika process ini sudah di-handle via participant
            boolean hasParticipant = false;
            for (Participant p : modelInstance.getModelElementsByType(Participant.class)) {
                if (proc.equals(p.getProcess())) {
                    hasParticipant = true;
                    break;
                }
            }

            if (!hasParticipant) {
                String processName = proc.getName();
                if (processName != null && !processName.isBlank()) {
                    assignLaneToAllElements(proc.getFlowElements(), processName.trim(), nodeToLane);
                }
            }
        }

        // Debug log
        System.out.println("=== COMPREHENSIVE LANE MAPPING DEBUG ===");
        System.out.println("Total Lanes (global): " + modelInstance.getModelElementsByType(Lane.class).size());
        System.out.println("Total Participants: " + modelInstance.getModelElementsByType(Participant.class).size());
        System.out.println("Total Processes: " + modelInstance.getModelElementsByType(Process.class).size());
        System.out.println("Total SubProcesses: " + modelInstance.getModelElementsByType(SubProcess.class).size());
        System.out.println("Total CallActivities: " + modelInstance.getModelElementsByType(CallActivity.class).size());
        System.out.println("Total Transactions: " + modelInstance.getModelElementsByType(Transaction.class).size());
        System.out.println("Total mapped nodes: " + nodeToLane.size());

        if (!nodeToLane.isEmpty()) {
            System.out.println("\nNode mappings:");
            nodeToLane.forEach((id, lane) -> System.out.println("  " + id + " -> [" + lane + "]"));
        } else {
            System.out.println("⚠️ WARNING: No lane/participant mappings found!");
        }

        // Analisis coverage
        int totalFlowNodes = modelInstance.getModelElementsByType(FlowNode.class).size();
        int mappedNodes = nodeToLane.size();
        double coverage = totalFlowNodes > 0 ? (mappedNodes * 100.0 / totalFlowNodes) : 0;
        System.out.println(String.format("\nCoverage: %d/%d nodes (%.1f%%)",
                mappedNodes, totalFlowNodes, coverage));

        // Detail unmapped nodes
        if (coverage < 100) {
            System.out.println("\n⚠️ UNMAPPED NODES:");
            for (FlowNode node : modelInstance.getModelElementsByType(FlowNode.class)) {
                if (!nodeToLane.containsKey(node.getId())) {
                    String name = node.getName() != null ? node.getName() : node.getId();
                    String type = node.getElementType().getTypeName();
                    System.out.println("  - " + type + ": " + name);
                }
            }
        }

        return nodeToLane;
    }

    /**
     * Helper: Recursive function untuk assign lane ke semua children dalam SubProcess
     */
    private void assignLaneToSubProcessChildren(SubProcess sp, String laneName, Map<String, String> nodeToLane) {
        for (FlowElement e : sp.getFlowElements()) {
            if (e instanceof FlowNode) {
                nodeToLane.putIfAbsent(e.getId(), laneName);

                // Recursive jika ada nested subprocess
                if (e instanceof SubProcess) {
                    assignLaneToSubProcessChildren((SubProcess) e, laneName, nodeToLane);
                }
            }
        }
    }

    /**
     * Helper: Find lane dari context sekitar (incoming/outgoing flows)
     */
    private String findContextLane(FlowNode node, Map<String, String> nodeToLane, BpmnModelInstance modelInstance) {
        // Cek incoming flows
        for (SequenceFlow incoming : node.getIncoming()) {
            FlowNode source = incoming.getSource();
            String sourceLane = nodeToLane.get(source.getId());
            if (sourceLane != null && !sourceLane.isBlank()) {
                return sourceLane;
            }
        }

        // Cek outgoing flows
        for (SequenceFlow outgoing : node.getOutgoing()) {
            FlowNode target = outgoing.getTarget();
            String targetLane = nodeToLane.get(target.getId());
            if (targetLane != null && !targetLane.isBlank()) {
                return targetLane;
            }
        }

        return null;
    }

    /**
     * Helper: Comprehensive lane assignment untuk semua flow elements dalam container
     */
    private void assignLaneToAllElements(Collection<FlowElement> elements, String laneName, Map<String, String> nodeToLane) {
        for (FlowElement e : elements) {
            if (e instanceof FlowNode) {
                nodeToLane.putIfAbsent(e.getId(), laneName);

                // Handle berbagai tipe subprocess/activity container
                if (e instanceof SubProcess) {
                    SubProcess sp = (SubProcess) e;
                    assignLaneToAllElements(sp.getFlowElements(), laneName, nodeToLane);
                } else if (e instanceof CallActivity) {
                    // CallActivity reference ke external process, tapi tetap assign lane
                    nodeToLane.putIfAbsent(e.getId(), laneName);
                } else if (e instanceof Transaction) {
                    // Transaction adalah special type of subprocess
                    Transaction tx = (Transaction) e;
                    assignLaneToAllElements(tx.getFlowElements(), laneName, nodeToLane);
                }
            }
        }
    }

    private String determineTaskType(FlowNode node, String laneName) {
        String nodeName = node.getName() != null ? node.getName().toLowerCase() : "";
        String elementType = node.getElementType().getTypeName().toLowerCase();

        if (elementType.contains("startevent") || elementType.contains("endevent")) return "SYSTEM";
        if (elementType.contains("gateway")) return "SYSTEM";

        if (laneName != null && !laneName.trim().isEmpty()) {
            String lane = laneName.toLowerCase();
            if (lane.contains("sistem") || lane.contains("system")) return "SYSTEM";
            return "HUMAN";
        }

        if (nodeName.contains("konfirmasi") || nodeName.contains("pilih") ||
                nodeName.contains("setuju") || nodeName.contains("tolak")) return "HUMAN";

        if (nodeName.contains("sistem") || nodeName.contains("otomatis") ||
                nodeName.contains("generate") || nodeName.contains("hitung")) return "SYSTEM";

        if (elementType.contains("usertask") || elementType.contains("manualtask")) return "HUMAN";
        if (elementType.contains("servicetask") || elementType.contains("scripttask")) return "SYSTEM";

        return (laneName != null && !laneName.trim().isEmpty()) ? "HUMAN" : "SYSTEM";
    }

    public BpmnFile parseAndSaveFile(MultipartFile file) throws IOException {
        BpmnModelInstance modelInstance = Bpmn.readModelFromStream(file.getInputStream());

        dfsService.printTaskTypeAnalysis(modelInstance);

        List<List<String>> pathsWithActors = dfsService.findAllPathsWithActor(modelInstance);
        List<String> pathStrings = pathsWithActors.stream()
                .map(p -> String.join(" -> ", p))
                .collect(Collectors.toList());

        Map<String, String> nodeToLane = buildNodeToLaneMapping(modelInstance);

        List<Map<String, Object>> elementsJson = new ArrayList<>();
        Map<String, String> taskTypes = new HashMap<>();
        for (FlowNode node : modelInstance.getModelElementsByType(FlowNode.class)) {
            Map<String, Object> el = new LinkedHashMap<>();
            el.put("id", node.getId());
            el.put("name", node.getName() != null ? node.getName() : "");
            el.put("type", node.getElementType().getTypeName());

            String laneName = nodeToLane.get(node.getId());
            if (laneName != null && !laneName.isBlank()) el.put("lane", laneName);

            String taskType = determineTaskType(node, laneName);
            el.put("task_type", taskType);

            taskTypes.put(node.getId(), taskType);
            elementsJson.add(el);
        }

        List<Map<String, Object>> scenarios = generateTestScenariosWithTaskTypes(
                modelInstance, nodeToLane, taskTypes);
        // 🔑 ambil current user
        com.example.bpmn_generator.entity.User owner = getCurrentUser();
        return saveAndReturnEntity(file, elementsJson, pathStrings, scenarios, toXml(modelInstance), owner);
    }

    public List<Map<String, Object>> generateTestScenariosWithTaskTypes(
            BpmnModelInstance modelInstance,
            Map<String, String> taskToLane,
            Map<String, String> taskTypes
    ) {
        List<List<String>> paths = findAllPaths(modelInstance);

        Map<String, FlowNode> nodeMap = new HashMap<>();
        for (FlowNode node : modelInstance.getModelElementsByType(FlowNode.class)) {
            nodeMap.put(node.getId(), node);
        }

        List<Map<String, Object>> scenarios = new ArrayList<>();
        boolean hasLanes = (taskToLane != null && !taskToLane.isEmpty());

        if (hasLanes) {
            System.out.println("=== LANE MAPPING IN SCENARIOS ===");
            taskToLane.entrySet().stream().limit(5).forEach(entry ->
                    System.out.println("  " + entry.getKey() + " -> [" + entry.getValue() + "]")
            );
        }

        for (List<String> path : paths) {
            Map<String, Object> scenario = new LinkedHashMap<>();

            // ✅ CEK: Berapa banyak unique lane dalam path ini?
            Set<String> lanesInPath = new HashSet<>();
            for (String nodeId : path) {
                String lane = taskToLane.get(nodeId);
                if (lane != null && !lane.isBlank()) {
                    lanesInPath.add(lane);
                }
            }

            // ✅ HANYA tampilkan [Lane] jika path melibatkan > 1 lane
            boolean showLanes = lanesInPath.size() > 1;

            System.out.println("Path has " + lanesInPath.size() + " unique lane(s): " + lanesInPath + " -> showLanes=" + showLanes);

            // Build readable path dengan conditional lane display
            List<String> readablePath = path.stream()
                    .map(id -> {
                        FlowNode node = nodeMap.get(id);
                        String name = node.getName();
                        String type = node.getElementType().getTypeName();
                        String laneName = taskToLane.get(id);

                        String displayName = (name != null && !name.isBlank()) ? name : type;

                        // Tampilkan [Lane] HANYA jika path cross-lane
                        if (showLanes && laneName != null && !laneName.isBlank()) {
                            return "[" + laneName + "] " + displayName;
                        } else {
                            return displayName;
                        }
                    })
                    .collect(Collectors.toList());

            scenario.put("path", readablePath);
            scenario.put("rawPath", path);
            scenario.put("task_types", taskTypes);

            // Build scenario_step dengan logic yang sama
            StringBuilder steps = new StringBuilder();
            for (String nodeId : path) {
                FlowNode node = nodeMap.get(nodeId);
                String name = node.getName() != null ? node.getName() : node.getElementType().getTypeName();
                String lane = taskToLane.get(nodeId);

                // Tampilkan [Lane] HANYA jika path cross-lane
                if (showLanes && lane != null && !lane.isBlank()) {
                    steps.append(String.format("-> [%s] %s%n", lane, name));
                } else {
                    steps.append(String.format("-> %s%n", name));
                }
            }
            scenario.put("scenario_step", steps.toString().trim());

            Map<String, String> testData = new LinkedHashMap<>();
            for (String nodeId : path) {
                FlowNode node = nodeMap.get(nodeId);
                if (node instanceof Task) {
                    String taskName = node.getName() != null ? node.getName().toLowerCase() : "task";
                    String key = taskName.replace(" ", "_");
                    testData.put(key, "sample_value");
                }
            }
            scenario.put("input_data", testData);
            scenario.put("expected_result", Map.of("status", "success", "message", "Proses selesai berhasil"));

            scenarios.add(scenario);
        }
        return scenarios;
    }

    public void assertLaneCoverage(BpmnModelInstance model, Map<String,String> nodeToLane, List<List<String>> paths) {
        Set<String> missing = new LinkedHashSet<>();
        Map<String, FlowNode> nodeMap = new HashMap<>();
        for (FlowNode n : model.getModelElementsByType(FlowNode.class)) nodeMap.put(n.getId(), n);

        for (List<String> path : paths) {
            for (String id : path) {
                FlowNode n = nodeMap.get(id);
                if (n == null) continue;
                if ((n instanceof Task || n instanceof SubProcess) &&
                        (nodeToLane.get(id) == null || nodeToLane.get(id).isBlank())) {
                    String label = (n.getName() != null && !n.getName().isBlank())
                            ? n.getName() : n.getElementType().getTypeName();
                    missing.add(id + " :: " + label);
                }
            }
        }

        System.out.println("=== UNMAPPED NODES (Task/SubProcess) ===");
        if (missing.isEmpty()) System.out.println("(none)");
        else missing.forEach(System.out::println);
    }

    /** Hapus file fisik di disk, non-fatal jika gagal */
    public void deletePhysicalFileIfExists(String storedFileName) {
        if (storedFileName == null || storedFileName.isBlank()) return;
        try {
            Path p = getFilePath(storedFileName);
            if (Files.exists(p)) {
                Files.delete(p);
                System.out.println("🗑Deleted physical file: " + p);
            }
        } catch (Exception ex) {
            System.err.println(" Gagal hapus file fisik: " + ex.getMessage());
        }
    }

    /** Hapus satu file milik user (fisik + DB) */
    @Transactional
    public void deleteOneOwned(BpmnFile file) {
        deletePhysicalFileIfExists(file.getStoredFileName());
        bpmnRepository.delete(file);
    }

    /** Hapus banyak file milik user berdasarkan list id */
    @Transactional
    public int deleteManyOwned(String username, List<Long> ids) {
        // Ambil file yang benar-benar owned
        List<BpmnFile> owned = bpmnRepository.findAllByOwnerUsername(username)
                .stream()
                .filter(f -> ids.contains(f.getId()))
                .toList();

        // Hapus physical files
        for (BpmnFile f : owned) {
            deletePhysicalFileIfExists(f.getStoredFileName());
        }

        // Delete dari database
        bpmnRepository.deleteAll(owned);  // ✅ Gunakan deleteAll() standard

        return owned.size();
    }

    @Transactional
    public int deleteAllOwned(String username) {
        List<BpmnFile> owned = bpmnRepository.findAllByOwnerUsername(username);

        // Hapus physical files
        for (BpmnFile f : owned) {
            deletePhysicalFileIfExists(f.getStoredFileName());
        }

        // Delete dari database
        bpmnRepository.deleteAll(owned);  // ✅ Gunakan deleteAll() standard

        return owned.size();
    }

}