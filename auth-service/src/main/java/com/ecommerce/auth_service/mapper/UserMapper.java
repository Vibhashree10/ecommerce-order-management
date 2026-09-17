package com.ecommerce.auth_service.mapper;

import com.ecommerce.auth_service.dto.response.UserResponse;
import com.ecommerce.auth_service.entity.Role;
import com.ecommerce.auth_service.entity.User;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        List<String> roles = user.getRoles() == null ? List.of() : user.getRoles().stream()
                .map(Role::getName)
                .sorted()
                .toList();

        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), roles);
    }
}

