package com.example.bpmn_generator.security;

import com.example.bpmn_generator.entity.User;
import com.example.bpmn_generator.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.NoSuchElementException;

public class CurrentUser {

    public static User require(UserRepository userRepository) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("User belum terautentikasi");
        }
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new NoSuchElementException("User tidak ditemukan: " + auth.getName()));
    }
}
