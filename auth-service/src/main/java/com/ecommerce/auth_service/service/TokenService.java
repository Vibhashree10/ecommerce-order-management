package com.ecommerce.auth_service.service;

import com.ecommerce.auth_service.entity.User;

public interface TokenService {
    String generateAccessToken(User user);

    String generateRefreshToken(User user);

    String extractUsername(String token);

    boolean validateToken(String token);
}

