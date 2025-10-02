package com.example.bpmn_generator.service;

import com.example.bpmn_generator.entity.User;
import com.example.bpmn_generator.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService implements UserDetailsService {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    // simple in-memory token store: token -> username
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    public AuthService(UserRepository userRepo, PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
    }

    /** Register new user */
    public User register(String username, String rawPassword) {
        userRepo.findByUsername(username).ifPresent(u -> {
            throw new IllegalArgumentException("Username already exists");
        });
        User u = new User(username, passwordEncoder.encode(rawPassword));
        return userRepo.save(u);
    }

    /** Login -> returns Bearer token */
    public String login(String username, String rawPassword) {
        User u = userRepo.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        if (!passwordEncoder.matches(rawPassword, u.getPassword())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        String token = UUID.randomUUID().toString();
        tokens.put(token, u.getUsername());
        return token;
    }

    public void logout(String token) {
        tokens.remove(token);
    }

    public boolean isValidToken(String token, String username) {
        return username != null && username.equals(tokens.get(token));
    }

    public String usernameFromToken(String token) {
        return tokens.get(token);
    }

    public Optional<User> currentUser(String username) {
        return userRepo.findByUsername(username);
    }

    /* ===== UserDetailsService ===== */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = userRepo.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return new org.springframework.security.core.userdetails.User(
                u.getUsername(),
                u.getPassword(),
                Collections.singleton(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }
}
