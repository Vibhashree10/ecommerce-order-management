package com.ecommerce.auth_service.service;

import com.ecommerce.auth_service.dto.response.UserResponse;
import java.util.List;

public interface UserService {
    UserResponse getCurrentUser(String username);

    UserResponse getUserById(Long id);

    List<UserResponse> getAllUsers();
}

