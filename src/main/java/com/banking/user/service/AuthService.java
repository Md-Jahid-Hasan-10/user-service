package com.banking.user.service;

import com.banking.user.dto.AuthResponse;
import com.banking.user.dto.LoginRequest;
import com.banking.user.dto.MessageResponse;
import com.banking.user.dto.RegisterRequest;
import com.banking.user.dto.VerifyEmailRequest;

public interface AuthService {

    /** Always answers the same way, whether or not the email is already registered. */
    MessageResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    MessageResponse verifyEmail(VerifyEmailRequest request);
}