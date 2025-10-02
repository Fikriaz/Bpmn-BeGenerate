package com.example.bpmn_generator.repository;

import com.example.bpmn_generator.entity.BpmnFile;
import com.example.bpmn_generator.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BpmnRepository extends JpaRepository<BpmnFile, Long> {
    List<BpmnFile> findByOwner(User owner);
}
