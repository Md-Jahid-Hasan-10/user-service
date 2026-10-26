package com.banking.user.service;

import com.banking.user.dto.AuthResponse;
import com.banking.user.dto.LoginRequest;
import com.banking.user.dto.RegisterRequest;
import com.banking.user.dto.UserResponse;

public interface AuthService {

    UserResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);
}