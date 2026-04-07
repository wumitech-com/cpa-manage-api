package com.cpa.controller;

import com.cpa.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> request) {
        String username = request == null ? null : String.valueOf(request.getOrDefault("username", ""));
        String password = request == null ? null : String.valueOf(request.getOrDefault("password", ""));
        return authService.login(username, password);
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                  @RequestHeader(value = "Authorization", required = false) String authorization) {
        String resolved = resolveToken(token, authorization);
        return authService.me(resolved);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                      @RequestHeader(value = "Authorization", required = false) String authorization) {
        String resolved = resolveToken(token, authorization);
        authService.logout(resolved);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "已退出登录");
        return result;
    }

    private String resolveToken(String token, String authorization) {
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        if (authorization != null && authorization.toLowerCase().startsWith("bearer ")) {
            return authorization.substring(7).trim();
        }
        return null;
    }
}

