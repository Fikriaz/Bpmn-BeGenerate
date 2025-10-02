package com.example.bpmn_generator.controller;

import com.example.bpmn_generator.entity.User;
import com.example.bpmn_generator.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> req) {
        User u = auth.register(req.get("username"), req.get("password"));
        return ResponseEntity.ok(Map.of("status", "ok", "username", u.getUsername()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> req) {
        String username = req.get("username");
        String token = auth.login(username, req.get("password"));

        User user = auth.currentUser(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return ResponseEntity.ok(Map.of(
                "token", token,
                "tokenType", "Bearer",
                "user", Map.of(
                        "id", user.getId().toString(),
                        "username", user.getUsername(),
                        "loginTime", System.currentTimeMillis()
                )
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring(7) : null;
        if (token != null) auth.logout(token);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
