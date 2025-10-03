package com.example.bpmn_generator.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "bpmn_files")
public class BpmnFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_file_name")
    private String originalFileName;

    @Column(name = "stored_file_name")
    private String storedFileName;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @Type(JsonBinaryType.class)
    @Column(name = "elements_json", columnDefinition = "jsonb")
    private List<Map<String, Object>> elementsJson;

    @Type(JsonBinaryType.class)
    @Column(name = "paths_json", columnDefinition = "jsonb")
    private List<String> pathsJson;

    @Type(JsonBinaryType.class)
    @Column(name = "test_scenarios_json", columnDefinition = "jsonb")
    private List<Map<String, Object>> testScenariosJson;

    @Column(name = "bpmn_xml", columnDefinition = "TEXT")
    private String bpmnXml;

    @Column(name = "scenario_ready")
    private boolean scenarioReady = false;

    @Column(name = "generating_scenario")
    private boolean generatingScenario = false;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "password"})
    private User owner;

    @OneToMany(mappedBy = "bpmnFile", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BpmnResult> results = new ArrayList<>();

    // getters & setters
    public Long getId() { return id; }
    public String getOriginalFileName() { return originalFileName; }
    public void setOriginalFileName(String originalFileName) { this.originalFileName = originalFileName; }
    public String getStoredFileName() { return storedFileName; }
    public void setStoredFileName(String storedFileName) { this.storedFileName = storedFileName; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
    public List<Map<String, Object>> getElementsJson() { return elementsJson; }
    public void setElementsJson(List<Map<String, Object>> elementsJson) { this.elementsJson = elementsJson; }
    public List<String> getPathsJson() { return pathsJson; }
    public void setPathsJson(List<String> pathsJson) { this.pathsJson = pathsJson; }
    public List<Map<String, Object>> getTestScenariosJson() { return testScenariosJson; }
    public void setTestScenariosJson(List<Map<String, Object>> testScenariosJson) { this.testScenariosJson = testScenariosJson; }
    public String getBpmnXml() { return bpmnXml; }
    public void setBpmnXml(String bpmnXml) { this.bpmnXml = bpmnXml; }
    public boolean isGeneratingScenario() { return generatingScenario; }
    public void setGeneratingScenario(boolean generatingScenario) { this.generatingScenario = generatingScenario; }
    public boolean isScenarioReady() { return scenarioReady; }
    public void setScenarioReady(boolean scenarioReady) { this.scenarioReady = scenarioReady; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }





    // getter/setter
    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
}
