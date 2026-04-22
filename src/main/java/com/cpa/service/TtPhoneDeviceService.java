package com.cpa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cpa.entity.TtPhoneDevice;
import com.cpa.entity.TtPhoneServer;
import com.cpa.repository.TtPhoneDeviceRepository;
import com.cpa.repository.TtPhoneServerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TtPhoneDeviceService {

    private final TtPhoneDeviceRepository repository;
    private final TtPhoneServerRepository phoneServerRepository;
    private static final List<String> ALLOWED_STATUS = List.of("IDLE", "BUSY", "OFFLINE", "DISABLED");
    private static final Pattern SUFFIX_PATTERN = Pattern.compile("_(\\d{4})$");

    public Map<String, Object> list(String serverIp, String phoneId, String deviceStatus, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = (safePage - 1) * safeSize;
        String serverIpLike = trimToNull(serverIp);
        String phoneIdLike = trimToNull(phoneId);
        String status = trimToNull(deviceStatus);

        LambdaQueryWrapper<TtPhoneDevice> query = new LambdaQueryWrapper<TtPhoneDevice>()
                .like(serverIpLike != null, TtPhoneDevice::getServerIp, serverIpLike)
                .like(phoneIdLike != null, TtPhoneDevice::getPhoneId, phoneIdLike)
                .eq(status != null, TtPhoneDevice::getDeviceStatus, status)
                .orderByDesc(TtPhoneDevice::getCreatedAt)
                .last("LIMIT " + offset + "," + safeSize);

        List<TtPhoneDevice> list = repository.selectList(query);
        long total = repository.selectCount(
                new LambdaQueryWrapper<TtPhoneDevice>()
                        .like(serverIpLike != null, TtPhoneDevice::getServerIp, serverIpLike)
                        .like(phoneIdLike != null, TtPhoneDevice::getPhoneId, phoneIdLike)
                        .eq(status != null, TtPhoneDevice::getDeviceStatus, status)
        );

        return Map.of("list", list, "total", total, "page", safePage, "size", safeSize);
    }

    public Map<String, Object> registerList(String serverIp, String phoneId, String deviceStatus, int page, int size) {
        List<TtPhoneServer> registerServers = phoneServerRepository.selectList(
                new LambdaQueryWrapper<TtPhoneServer>()
                        .in(TtPhoneServer::getUsageScope, List.of("REGISTER", "MIXED"))
        );
        List<String> registerServerIps = registerServers.stream()
                .map(TtPhoneServer::getServerIp)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .toList();
        if (registerServerIps.isEmpty()) {
            return Map.of("list", List.of(), "total", 0, "page", Math.max(page, 1), "size", Math.min(Math.max(size, 1), 200));
        }

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = (safePage - 1) * safeSize;
        String serverIpLike = trimToNull(serverIp);
        String phoneIdLike = trimToNull(phoneId);
        String status = trimToNull(deviceStatus);

        List<TtPhoneDevice> list = repository.selectList(
                new LambdaQueryWrapper<TtPhoneDevice>()
                        .in(TtPhoneDevice::getServerIp, registerServerIps)
                        .like(serverIpLike != null, TtPhoneDevice::getServerIp, serverIpLike)
                        .like(phoneIdLike != null, TtPhoneDevice::getPhoneId, phoneIdLike)
                        .eq(status != null, TtPhoneDevice::getDeviceStatus, status)
                        .orderByDesc(TtPhoneDevice::getCreatedAt)
                        .last("LIMIT " + offset + "," + safeSize)
        );
        long total = repository.selectCount(
                new LambdaQueryWrapper<TtPhoneDevice>()
                        .in(TtPhoneDevice::getServerIp, registerServerIps)
                        .like(serverIpLike != null, TtPhoneDevice::getServerIp, serverIpLike)
                        .like(phoneIdLike != null, TtPhoneDevice::getPhoneId, phoneIdLike)
                        .eq(status != null, TtPhoneDevice::getDeviceStatus, status)
        );
        return Map.of("list", list, "total", total, "page", safePage, "size", safeSize);
    }

    @Transactional
    public TtPhoneDevice create(TtPhoneDevice input) {
        TtPhoneDevice row = normalizeForCreate(input);
        try {
            repository.insert(row);
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("phone_id 已存在: " + row.getPhoneId());
        }
        return row;
    }

    @Transactional
    public Map<String, Object> batchCreate(List<TtPhoneDevice> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("批量数据不能为空");
        }
        int insertCount = 0;
        int skipCount = 0;
        List<String> skippedPhoneIds = new ArrayList<>();
        for (TtPhoneDevice input : inputs) {
            if (input == null) {
                skipCount++;
                skippedPhoneIds.add("(null)");
                continue;
            }
            try {
                TtPhoneDevice row = normalizeForCreate(input);
                repository.insert(row);
                insertCount++;
            } catch (Exception ex) {
                skipCount++;
                String phoneId = input.getPhoneId() == null ? "(empty)" : input.getPhoneId();
                skippedPhoneIds.add(phoneId);
            }
        }
        return Map.of(
                "insertCount", insertCount,
                "skipCount", skipCount,
                "skippedPhoneIds", skippedPhoneIds
        );
    }

    @Transactional
    public Map<String, Object> batchCreateByRule(String phonePrefix, String serverIp, Integer count, String note) {
        String prefix = requireValue(phonePrefix, "phonePrefix");
        String ip = requireValue(serverIp, "serverIp");
        int createCount = count == null ? 0 : count;
        if (createCount <= 0 || createCount > 500) {
            throw new IllegalArgumentException("count 需在 1~500");
        }
        String ipToken = ip.replace('.', '_');
        String basePrefix = prefix + "_" + ipToken + "_";
        List<String> existing = repository.listPhoneIdsByServerAndPrefix(ip, basePrefix);
        int maxSuffix = 0;
        for (String phoneId : existing) {
            Matcher matcher = SUFFIX_PATTERN.matcher(phoneId == null ? "" : phoneId);
            if (matcher.find()) {
                try {
                    maxSuffix = Math.max(maxSuffix, Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        List<String> insertedPhoneIds = new ArrayList<>();
        int skipCount = 0;
        for (int i = 1; i <= createCount; i++) {
            int suffix = maxSuffix + i;
            String phoneId = basePrefix + String.format("%04d", suffix);
            TtPhoneDevice row = new TtPhoneDevice();
            row.setServerIp(ip);
            row.setPhoneId(phoneId);
            row.setDeviceStatus("IDLE");
            row.setNote(trimToNull(note));
            row.setCreatedAt(LocalDateTime.now());
            row.setUpdatedAt(LocalDateTime.now());
            try {
                repository.insert(row);
                insertedPhoneIds.add(phoneId);
            } catch (DuplicateKeyException ex) {
                skipCount++;
            }
        }

        return Map.of(
                "insertCount", insertedPhoneIds.size(),
                "skipCount", skipCount,
                "insertedPhoneIds", insertedPhoneIds
        );
    }

    public Map<String, Long> countByServerIps(List<String> serverIps) {
        if (serverIps == null || serverIps.isEmpty()) {
            return Map.of();
        }
        List<String> validIps = serverIps.stream().map(this::trimToNull).filter(v -> v != null).distinct().toList();
        if (validIps.isEmpty()) {
            return Map.of();
        }
        List<TtPhoneDevice> rows = repository.selectList(
                new LambdaQueryWrapper<TtPhoneDevice>()
                        .in(TtPhoneDevice::getServerIp, validIps)
        );
        Map<String, Long> countMap = new LinkedHashMap<>();
        for (String ip : validIps) {
            countMap.put(ip, 0L);
        }
        for (TtPhoneDevice row : rows) {
            String ip = row.getServerIp();
            if (ip == null) {
                continue;
            }
            countMap.put(ip, countMap.getOrDefault(ip, 0L) + 1L);
        }
        return countMap;
    }

    @Transactional
    public TtPhoneDevice update(Long id, TtPhoneDevice patch) {
        TtPhoneDevice existing = repository.selectById(id);
        if (existing == null) throw new IllegalArgumentException("云手机不存在");
        if (patch.getServerIp() != null) existing.setServerIp(requireValue(patch.getServerIp(), "server_ip"));
        if (patch.getPhoneId() != null) existing.setPhoneId(requireValue(patch.getPhoneId(), "phone_id"));
        if (patch.getDeviceStatus() != null) existing.setDeviceStatus(normalizeStatus(patch.getDeviceStatus()));
        if (patch.getNote() != null) existing.setNote(trimToNull(patch.getNote()));
        existing.setUpdatedAt(LocalDateTime.now());
        repository.updateById(existing);
        return existing;
    }

    @Transactional
    public void delete(Long id) {
        TtPhoneDevice existing = repository.selectById(id);
        if (existing == null) throw new IllegalArgumentException("云手机不存在");
        repository.deleteById(id);
    }

    private TtPhoneDevice normalizeForCreate(TtPhoneDevice input) {
        TtPhoneDevice row = new TtPhoneDevice();
        row.setServerIp(requireValue(input.getServerIp(), "server_ip"));
        row.setPhoneId(requireValue(input.getPhoneId(), "phone_id"));
        row.setDeviceStatus(input.getDeviceStatus() == null ? "IDLE" : normalizeStatus(input.getDeviceStatus()));
        row.setNote(trimToNull(input.getNote()));
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private String normalizeStatus(String value) {
        String status = requireValue(value, "device_status").toUpperCase();
        if (!ALLOWED_STATUS.contains(status)) {
            throw new IllegalArgumentException("device_status 仅支持: " + ALLOWED_STATUS);
        }
        return status;
    }

    private String requireValue(String value, String field) {
        String text = trimToNull(value);
        if (text == null) throw new IllegalArgumentException(field + " 不能为空");
        return text;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
