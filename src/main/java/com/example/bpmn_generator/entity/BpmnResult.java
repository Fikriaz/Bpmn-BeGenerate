package com.example.bpmn_generator.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Entity untuk menyimpan hasil LLM dari BPMN processing
 * Berisi hasil yang sudah diolah dan siap untuk testing
 */
@Entity
@Table(name = "bpmn_results")
public class BpmnResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "path_id", nullable = false)
    private String pathId;

    @Column(length = 2000)
    private String summary;

    @Column(length = 5000)
    private String description;

    @Column(length = 10000)
    private String scenarioStep;

    @Lob
    private String testData;  // simpan input_data dalam bentuk string JSON

    @Lob
    private String expectedResult;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Foreign key ke BpmnFile
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bpmn_file_id")
    private BpmnFile bpmnFile;

    // Constructors
    public BpmnResult() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public BpmnResult(String pathId, String summary, String description,
                      String scenarioStep, String testData, String expectedResult) {
        this();
        this.pathId = pathId;
        this.summary = summary;
        this.description = description;
        this.scenarioStep = scenarioStep;
        this.testData = testData;
        this.expectedResult = expectedResult;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPathId() {
        return pathId;
    }

    public void setPathId(String pathId) {
        this.pathId = pathId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getScenarioStep() {
        return scenarioStep;
    }

    public void setScenarioStep(String scenarioStep) {
        this.scenarioStep = scenarioStep;
    }

    public String getTestData() {
        return testData;
    }

    public void setTestData(String testData) {
        this.testData = testData;
    }

    public String getExpectedResult() {
        return expectedResult;
    }

    public void setExpectedResult(String expectedResult) {
        this.expectedResult = expectedResult;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public BpmnFile getBpmnFile() {
        return bpmnFile;
    }

    public void setBpmnFile(BpmnFile bpmnFile) {
        this.bpmnFile = bpmnFile;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "BpmnResult{" +
                "id=" + id +
                ", pathId='" + pathId + '\'' +
                ", summary='" + summary + '\'' +
                ", description='" + description + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}