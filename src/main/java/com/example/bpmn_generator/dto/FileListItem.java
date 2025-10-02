package com.example.bpmn_generator.dto;

import java.time.LocalDateTime;

public class FileListItem {
    private Long id;
    private String originalFileName;
    private LocalDateTime uploadedAt;
    private boolean scenarioReady;
    private String summary;

    public FileListItem(Long id, String originalFileName, LocalDateTime uploadedAt, boolean scenarioReady, String summary) {
        this.id = id;
        this.originalFileName = originalFileName;
        this.uploadedAt = uploadedAt;
        this.scenarioReady = scenarioReady;
        this.summary = summary;
    }

    public Long getId() { return id; }
    public String getOriginalFileName() { return originalFileName; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public boolean isScenarioReady() { return scenarioReady; }
    public String getSummary() { return summary; }
}
