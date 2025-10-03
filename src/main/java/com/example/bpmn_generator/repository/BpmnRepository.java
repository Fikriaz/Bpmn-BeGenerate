package com.example.bpmn_generator.repository;

import com.example.bpmn_generator.entity.BpmnFile;
import com.example.bpmn_generator.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BpmnRepository extends JpaRepository<BpmnFile, Long> {
    List<BpmnFile> findByOwner(User owner);

    // Method yang sudah ada (keep these)
    List<BpmnFile> findAllByOwnerUsername(String username);
    Optional<BpmnFile> findByIdAndOwnerUsername(Long id, String username);

    // Alternatif dengan custom query (lebih eksplisit)
    @Query("SELECT b FROM BpmnFile b WHERE b.owner.username = :username")
    List<BpmnFile> findByOwnerUsernameCustom(@Param("username") String username);

    @Query("SELECT b FROM BpmnFile b WHERE b.id = :id AND b.owner.username = :username")
    Optional<BpmnFile> findByIdAndOwnerUsernameCustom(@Param("id") Long id, @Param("username") String username);
}
