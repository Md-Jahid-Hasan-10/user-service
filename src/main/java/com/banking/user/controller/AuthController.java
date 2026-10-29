package com.banking.user.controller;

import com.banking.user.dto.AuthResponse;
import com.banking.user.dto.LoginRequest;
import com.banking.user.dto.MessageResponse;
import com.banking.user.dto.RegisterRequest;
import com.banking.user.dto.VerifyEmailRequest;
import com.banking.user.service.AuthService;
import com.banking.user.util.ResponseTimeFloor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final ResponseTimeFloor responseTimeFloor;

    public AuthController(AuthService authService, ResponseTimeFloor responseTimeFloor) {
        this.authService = authService;
        this.responseTimeFloor = responseTimeFloor;
    }

    @PostMapping("/register")
    public ResponseEntity<MessageResponse> register(@Valid @RequestBody RegisterRequest request) {
        long start = System.nanoTime();
        MessageResponse response = authService.register(request);
        responseTimeFloor.await(start);
        return ResponseEntity.accepted().body(response);
    }

    @PostMapping("/verify")
    public ResponseEntity<MessageResponse> verify(@Valid @RequestBody VerifyEmailRequest request) {
        return ResponseEntity.ok(authService.verifyEmail(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}