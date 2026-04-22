package com.cpa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cpa.entity.TtEmailPool;
import com.cpa.repository.TtEmailPoolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TtEmailPoolService {

    private final TtEmailPoolRepository repository;
    private static final List<String> ALLOWED_USAGE_STATUS = List.of("UNUSED", "USED");
    private static final int IMPORT_BATCH_SIZE = 1000;

    public Map<String, Object> list(String email, String channel, String usageStatus, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = (safePage - 1) * safeSize;
        String emailLike = trimToNull(email);
        String channelLike = trimToNull(channel);
        String usage = normalizeUsageStatus(trimToNull(usageStatus));

        List<TtEmailPool> list = repository.selectList(
                new LambdaQueryWrapper<TtEmailPool>()
                        .like(emailLike != null, TtEmailPool::getEmail, emailLike)
                        .like(channelLike != null, TtEmailPool::getChannel, channelLike)
                        .eq(usage != null, TtEmailPool::getUsageStatus, usage)
                        .orderByDesc(TtEmailPool::getCreatedAt)
                        .last("LIMIT " + offset + "," + safeSize)
        );
        long total = repository.selectCount(
                new LambdaQueryWrapper<TtEmailPool>()
                        .like(emailLike != null, TtEmailPool::getEmail, emailLike)
                        .like(channelLike != null, TtEmailPool::getChannel, channelLike)
                        .eq(usage != null, TtEmailPool::getUsageStatus, usage)
        );
        return Map.of("list", list, "total", total, "page", safePage, "size", safeSize);
    }

    @Transactional
    public Map<String, Object> importTxt(MultipartFile file, String channel) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        String finalChannel = trimToNull(channel);
        int insertCount = 0;
        int updateCount = 0;
        int skipCount = 0;
        List<String> errors = new ArrayList<>();
        List<ParsedRow> buffer = new ArrayList<>(IMPORT_BATCH_SIZE);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String raw = line == null ? "" : line.trim();
                if (raw.isEmpty()) {
                    continue;
                }
                String[] parts = raw.split("----", -1);
                if (parts.length < 4) {
                    skipCount++;
                    errors.add("第" + lineNo + "行格式错误，需为 email----password----client_id----refresh_token");
                    continue;
                }
                String email = trimToNull(parts[0]);
                String password = trimToNull(parts[1]);
                String clientId = trimToNull(parts[2]);
                String refreshToken = trimToNull(parts[3]);
                if (email == null || password == null) {
                    skipCount++;
                    errors.add("第" + lineNo + "行缺少 email/password");
                    continue;
                }
                buffer.add(new ParsedRow(email, password, clientId, refreshToken));
                if (buffer.size() >= IMPORT_BATCH_SIZE) {
                    Map<String, Integer> r = flushBatch(buffer, finalChannel);
                    insertCount += r.getOrDefault("insert", 0);
                    updateCount += r.getOrDefault("update", 0);
                    buffer.clear();
                }
            }
            if (!buffer.isEmpty()) {
                Map<String, Integer> r = flushBatch(buffer, finalChannel);
                insertCount += r.getOrDefault("insert", 0);
                updateCount += r.getOrDefault("update", 0);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("解析文件失败: " + e.getMessage(), e);
        }

        return Map.of(
                "insertCount", insertCount,
                "updateCount", updateCount,
                "skipCount", skipCount,
                "errors", errors
        );
    }

    private Map<String, Integer> flushBatch(List<ParsedRow> batchRows, String finalChannel) {
        if (batchRows == null || batchRows.isEmpty()) {
            return Map.of("insert", 0, "update", 0);
        }
        List<String> emails = batchRows.stream().map(ParsedRow::getEmail).distinct().toList();
        Map<String, TtEmailPool> existingMap = new HashMap<>();
        for (TtEmailPool row : repository.listByEmails(emails)) {
            if (row.getEmail() != null) {
                existingMap.put(row.getEmail(), row);
            }
        }

        int updateCount = existingMap.size();
        int insertCount = Math.max(0, emails.size() - updateCount);
        LocalDateTime now = LocalDateTime.now();
        List<TtEmailPool> upsertRows = new ArrayList<>(batchRows.size());
        for (ParsedRow input : batchRows) {
            TtEmailPool row = new TtEmailPool();
            row.setEmail(input.getEmail());
            row.setPassword(input.getPassword());
            row.setClientId(input.getClientId());
            row.setRefreshToken(input.getRefreshToken());
            row.setChannel(finalChannel);
            row.setUsageStatus("UNUSED");
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            upsertRows.add(row);
        }
        repository.batchUpsert(upsertRows);
        return Map.of("insert", insertCount, "update", updateCount);
    }

    private static class ParsedRow {
        private final String email;
        private final String password;
        private final String clientId;
        private final String refreshToken;

        private ParsedRow(String email, String password, String clientId, String refreshToken) {
            this.email = email;
            this.password = password;
            this.clientId = clientId;
            this.refreshToken = refreshToken;
        }

        private String getEmail() {
            return email;
        }

        private String getPassword() {
            return password;
        }

        private String getClientId() {
            return clientId;
        }

        private String getRefreshToken() {
            return refreshToken;
        }
    }

    private String normalizeUsageStatus(String value) {
        if (value == null) {
            return null;
        }
        String status = value.toUpperCase();
        if (!ALLOWED_USAGE_STATUS.contains(status)) {
            throw new IllegalArgumentException("usageStatus 仅支持: " + ALLOWED_USAGE_STATUS);
        }
        return status;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
