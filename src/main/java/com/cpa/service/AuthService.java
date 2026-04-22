package com.cpa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private static class SessionInfo {
        private final String username;
        private final long expireAtEpochSec;

        private SessionInfo(String username, long expireAtEpochSec) {
            this.username = username;
            this.expireAtEpochSec = expireAtEpochSec;
        }
    }

    private final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    @Value("${tt.auth.username:ttadmin}")
    private String authUsername;

    @Value("${tt.auth.password:ttadmin123}")
    private String authPassword;

    @Value("${tt.auth.session-hours:12}")
    private int sessionHours;

    public Map<String, Object> login(String username, String password) {
        if (username == null || password == null) {
            return Map.of("success", false, "message", "用户名或密码不能为空");
        }
        if (!authUsername.equals(username.trim()) || !authPassword.equals(password)) {
            return Map.of("success", false, "message", "用户名或密码错误");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        long expireAt = Instant.now().getEpochSecond() + Math.max(1, sessionHours) * 3600L;
        sessions.put(token, new SessionInfo(username.trim(), expireAt));
        return Map.of(
                "success", true,
                "message", "登录成功",
                "data", Map.of("token", token, "username", username.trim(), "expireAt", expireAt)
        );
    }

    public boolean isTokenValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        SessionInfo info = sessions.get(token);
        if (info == null) {
            return false;
        }
        if (info.expireAtEpochSec < Instant.now().getEpochSecond()) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    public Map<String, Object> me(String token) {
        SessionInfo info = sessions.get(token);
        if (info == null || info.expireAtEpochSec < Instant.now().getEpochSecond()) {
            if (info != null) {
                sessions.remove(token);
            }
            return Map.of("success", false, "message", "登录已失效");
        }
        return Map.of(
                "success", true,
                "data", Map.of("username", info.username, "expireAt", info.expireAtEpochSec)
        );
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sessions.remove(token);
        }
    }

    public String getUsernameByToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        SessionInfo info = sessions.get(token);
        if (info == null) {
            return "";
        }
        if (info.expireAtEpochSec < Instant.now().getEpochSecond()) {
            sessions.remove(token);
            return "";
        }
        return info.username;
    }
}

