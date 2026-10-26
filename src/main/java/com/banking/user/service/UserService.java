package com.banking.user.service;

import com.banking.user.dto.UserResponse;

public interface UserService {

    UserResponse getById(Long id);
}