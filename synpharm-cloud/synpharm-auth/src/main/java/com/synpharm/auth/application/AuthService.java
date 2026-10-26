package com.synpharm.auth.application;

import com.synpharm.auth.api.dto.LoginRequest;
import com.synpharm.auth.api.dto.RegisterRequest;
import com.synpharm.auth.api.dto.LoginResponse;
import jakarta.servlet.http.HttpServletRequest;

public interface AuthService {

    LoginResponse login(LoginRequest request, HttpServletRequest httpRequest);

    LoginResponse register(RegisterRequest request, HttpServletRequest httpRequest);

    void resetPassword(String email, String captcha, String newPassword);

    void logout(String tokenId);
}
