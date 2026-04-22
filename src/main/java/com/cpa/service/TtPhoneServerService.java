package com.cpa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cpa.entity.TtPhoneServer;
import com.cpa.entity.TtRegisterTask;
import com.cpa.repository.TtRegisterTaskRepository;
import com.cpa.repository.TtPhoneServerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TtPhoneServerService {

    private final TtPhoneServerRepository repository;
    private final TtRegisterTaskRepository registerTaskRepository;
    private static final String DEFAULT_XRAY_SERVER_IP = "192.168.40.249";
    private static final String DEFAULT_APPIUM_SERVER = "10.13.58.129";
    private static final int DEFAULT_MAX_CONCURRENCY = 8;
    private static final List<String> ALLOWED_USAGE_SCOPE = List.of("NONE", "REGISTER", "RETENTION", "MIXED");

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\."
                    + "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\."
                    + "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\."
                    + "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    );

    public Map<String, Object> list(String serverIp, Integer status, String usageScope, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = (safePage - 1) * safeSize;
        String keyword = serverIp == null ? null : serverIp.trim();
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        String safeUsageScope = normalizeUsageScope(usageScope, false);

        LambdaQueryWrapper<TtPhoneServer> qw = new LambdaQueryWrapper<TtPhoneServer>()
                .like(hasKeyword, TtPhoneServer::getServerIp, keyword)
                .eq(status != null, TtPhoneServer::getStatus, status)
                .eq(safeUsageScope != null, TtPhoneServer::getUsageScope, safeUsageScope)
                .orderByDesc(TtPhoneServer::getCreatedAt)
                .last("LIMIT " + offset + "," + safeSize);

        List<TtPhoneServer> list = repository.selectList(qw);
        long total = repository.selectCount(
                new LambdaQueryWrapper<TtPhoneServer>()
                        .like(hasKeyword, TtPhoneServer::getServerIp, keyword)
                        .eq(status != null, TtPhoneServer::getStatus, status)
                        .eq(safeUsageScope != null, TtPhoneServer::getUsageScope, safeUsageScope)
        );

        return Map.of(
                "list", list,
                "total", total,
                "page", safePage,
                "size", safeSize
        );
    }

    public List<TtPhoneServer> enabledList() {
        return repository.selectList(
                new LambdaQueryWrapper<TtPhoneServer>()
                        .eq(TtPhoneServer::getStatus, 0)
                        .in(TtPhoneServer::getUsageScope, List.of("REGISTER", "MIXED"))
                        .orderByDesc(TtPhoneServer::getCreatedAt)
        );
    }

    public List<TtPhoneServer> registerServerList() {
        return repository.selectList(
                new LambdaQueryWrapper<TtPhoneServer>()
                        .in(TtPhoneServer::getUsageScope, List.of("REGISTER", "MIXED"))
                        .orderByDesc(TtPhoneServer::getCreatedAt)
        );
    }

    @Transactional
    public TtPhoneServer create(TtPhoneServer input) {
        validateRequired(input.getServerIp());
        TtPhoneServer entity = normalize(input);
        repository.insert(entity);
        return entity;
    }

    @Transactional
    public Map<String, Object> batchCreate(List<TtPhoneServer> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("批量数据不能为空");
        }
        int insertCount = 0;
        int skipCount = 0;
        List<String> skippedServerIps = new ArrayList<>();

        for (TtPhoneServer input : inputs) {
            if (input == null || input.getServerIp() == null || input.getServerIp().isBlank()) {
                skipCount++;
                skippedServerIps.add("(empty)");
                continue;
            }
            String ip = input.getServerIp().trim();
            if (!isValidIpv4(ip)) {
                skipCount++;
                skippedServerIps.add(ip);
                continue;
            }

            Optional<TtPhoneServer> existing = repository.findByServerIp(ip);
            if (existing.isPresent()) {
                skipCount++;
                skippedServerIps.add(ip);
                continue;
            }
            TtPhoneServer entity = normalize(input);
            entity.setServerIp(ip);
            try {
                repository.insert(entity);
                insertCount++;
            } catch (DuplicateKeyException ex) {
                skipCount++;
                skippedServerIps.add(ip);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("insertCount", insertCount);
        result.put("skipCount", skipCount);
        result.put("skippedServerIps", skippedServerIps);
        return result;
    }

    @Transactional
    public TtPhoneServer update(Long id, TtPhoneServer patch) {
        TtPhoneServer existing = repository.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("服务器不存在");
        }
        if (patch.getXrayServerIp() != null) {
            existing.setXrayServerIp(trimToNull(patch.getXrayServerIp()));
        }
        if (patch.getAppiumServer() != null) {
            existing.setAppiumServer(trimToNull(patch.getAppiumServer()));
        }
        if (patch.getMaxConcurrency() != null) {
            if (patch.getMaxConcurrency() <= 0) {
                throw new IllegalArgumentException("最大并发数必须大于0");
            }
            existing.setMaxConcurrency(patch.getMaxConcurrency());
        }
        if (patch.getNote() != null) {
            existing.setNote(trimToNull(patch.getNote()));
        }
        if (patch.getUsageScope() != null) {
            existing.setUsageScope(normalizeUsageScope(patch.getUsageScope(), true));
        }
        existing.setUpdatedAt(LocalDateTime.now());
        repository.updateById(existing);
        return existing;
    }

    @Transactional
    public void enable(Long id) {
        updateStatus(id, 0);
    }

    @Transactional
    public Map<String, Object> disable(Long id) {
        return updateStatus(id, 1);
    }

    private Map<String, Object> updateStatus(Long id, int status) {
        TtPhoneServer existing = repository.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("服务器不存在");
        }
        int stoppedCount = 0;
        if (status == 1) {
            LambdaUpdateWrapper<TtRegisterTask> stopWrapper = new LambdaUpdateWrapper<TtRegisterTask>()
                    .eq(TtRegisterTask::getServerIp, existing.getServerIp())
                    .in(TtRegisterTask::getStatus, List.of("PENDING", "RUNNING"))
                    .set(TtRegisterTask::getStatus, "STOPPED")
                    .set(TtRegisterTask::getUpdatedAt, LocalDateTime.now());
            stoppedCount = registerTaskRepository.update(null, stopWrapper);
        }
        existing.setStatus(status);
        existing.setUpdatedAt(LocalDateTime.now());
        repository.updateById(existing);
        Map<String, Object> result = new HashMap<>();
        result.put("serverIp", existing.getServerIp());
        result.put("status", status);
        result.put("stoppedTaskCount", stoppedCount);
        return result;
    }

    private TtPhoneServer normalize(TtPhoneServer input) {
        TtPhoneServer entity = new TtPhoneServer();
        String ip = input.getServerIp() == null ? "" : input.getServerIp().trim();
        validateRequired(ip);
        if (!isValidIpv4(ip)) {
            throw new IllegalArgumentException("server_ip 不是有效 IPv4 地址: " + ip);
        }
        entity.setServerIp(ip);
        entity.setXrayServerIp(trimToNull(input.getXrayServerIp()) != null ? trimToNull(input.getXrayServerIp()) : DEFAULT_XRAY_SERVER_IP);
        entity.setAppiumServer(trimToNull(input.getAppiumServer()) != null ? trimToNull(input.getAppiumServer()) : DEFAULT_APPIUM_SERVER);
        entity.setMaxConcurrency(input.getMaxConcurrency() == null || input.getMaxConcurrency() <= 0 ? DEFAULT_MAX_CONCURRENCY : input.getMaxConcurrency());
        entity.setStatus(input.getStatus() == null ? 0 : (input.getStatus() == 0 ? 0 : 1));
        entity.setUsageScope(normalizeUsageScope(input.getUsageScope(), false) == null ? "NONE" : normalizeUsageScope(input.getUsageScope(), false));
        entity.setNote(trimToNull(input.getNote()));
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private void validateRequired(String serverIp) {
        if (serverIp == null || serverIp.isBlank()) {
            throw new IllegalArgumentException("server_ip 不能为空");
        }
    }

    private boolean isValidIpv4(String value) {
        return value != null && IPV4_PATTERN.matcher(value).matches();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private String normalizeUsageScope(String usageScope, boolean required) {
        String value = trimToNull(usageScope);
        if (value == null) {
            if (required) throw new IllegalArgumentException("usage_scope 不能为空");
            return null;
        }
        String upper = value.toUpperCase();
        if (!ALLOWED_USAGE_SCOPE.contains(upper)) {
            throw new IllegalArgumentException("usage_scope 仅支持: " + ALLOWED_USAGE_SCOPE);
        }
        return upper;
    }
}
