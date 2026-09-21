package com.booking.auth.dto;

import com.booking.user.Role;
import com.booking.user.User;

public record UserResponse(Long id, String email, String fullName, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
