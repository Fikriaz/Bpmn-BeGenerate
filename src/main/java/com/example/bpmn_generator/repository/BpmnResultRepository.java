package com.example.bpmn_generator.repository;

import com.example.bpmn_generator.entity.BpmnResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository untuk mengelola data BpmnResult
 * BpmnResult berisi hasil yang sudah diproses oleh LLM dan siap untuk testing
 */
@Repository
public interface BpmnResultRepository extends JpaRepository<BpmnResult, Long> {

    /**
     * Mencari semua BpmnResult berdasarkan BPMN file ID
     */
    List<BpmnResult> findByBpmnFileId(Long bpmnFileId);

    /**
     * Mencari BpmnResult berdasarkan path ID
     */
    List<BpmnResult> findByPathId(String pathId);

    /**
     * Mencari BpmnResult berdasarkan BPMN file ID dan path ID
     */
    Optional<BpmnResult> findByBpmnFileIdAndPathId(Long bpmnFileId, String pathId);

    /**
     * Mencari semua BpmnResult berdasarkan BPMN file ID dan diurutkan berdasarkan path ID
     */
    @Query("SELECT br FROM BpmnResult br WHERE br.bpmnFile.id = :fileId ORDER BY br.pathId")
    List<BpmnResult> findByBpmnFileIdOrderByPathId(@Param("fileId") Long fileId);

    /**
     * Menghitung jumlah BpmnResult berdasarkan BPMN file ID
     */
    long countByBpmnFileId(Long bpmnFileId);

    /**
     * Menghapus semua BpmnResult berdasarkan BPMN file ID
     */
    void deleteByBpmnFileId(Long bpmnFileId);

    /**
     * Mencari BpmnResult berdasarkan summary yang mengandung keyword tertentu
     */
    @Query("SELECT br FROM BpmnResult br WHERE br.summary LIKE %:keyword%")
    List<BpmnResult> findBySummaryContaining(@Param("keyword") String keyword);

    /**
     * Mencari BpmnResult terbaru berdasarkan BPMN file ID
     */
    @Query("SELECT br FROM BpmnResult br WHERE br.bpmnFile.id = :fileId ORDER BY br.createdAt DESC")
    List<BpmnResult> findByBpmnFileIdOrderByCreatedAtDesc(@Param("fileId") Long fileId);

    /**
     * Mencari BpmnResult berdasarkan status (jika ada field status di masa depan)
     */
    @Query("SELECT br FROM BpmnResult br WHERE br.bpmnFile.id = :fileId AND br.description IS NOT NULL")
    List<BpmnResult> findCompletedResultsByFileId(@Param("fileId") Long fileId);
}