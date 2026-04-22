package com.cpa.service;

import com.cpa.repository.TtEmailPoolRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailPoolAlertService {
    private static final int FORECAST_HOURS = 12;


    private final TtEmailPoolRepository ttEmailPoolRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    @Value("${email-pool.alert.enabled:false}")
    private boolean alertEnabled;

    @Value("${email-pool.alert.threshold:5000}")
    private long threshold;

    @Value("${email-pool.alert.cron:0 0 */2 * * ?}")
    private String alertCron;

    @Value("${email-pool.alert.wecom-webhook:}")
    private String wecomWebhook;
    private static final DateTimeFormatter ALERT_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 邮箱池低库存告警（默认每2小时执行一次）。
     * 仅在低于阈值时发送企业微信机器人告警，正常不发。
     */
    @Scheduled(cron = "${email-pool.alert.cron:0 0 */2 * * ?}")
    public void alertWhenLow() {
        checkAndSend(false);
    }

    /**
     * 手动触发一次邮箱池告警检查（用于联调）。
     */
    public Map<String, Object> manualCheckNow() {
        return checkAndSend(true);
    }

    private Map<String, Object> checkAndSend(boolean manual) {
        Map<String, Object> result = new HashMap<>();
        result.put("manual", manual);
        result.put("enabled", alertEnabled);
        result.put("threshold", threshold);
        result.put("cron", alertCron);
        if (!alertEnabled) {
            result.put("sent", false);
            result.put("message", "告警未启用");
            return result;
        }
        if (wecomWebhook == null || wecomWebhook.isBlank()) {
            log.warn("邮箱池告警已启用，但未配置企业微信机器人 webhook");
            result.put("sent", false);
            result.put("message", "未配置企业微信机器人 webhook");
            return result;
        }
        try {
            long count = ttEmailPoolRepository.countUnusedEmails();
            long consumed24h = ttEmailPoolRepository.countConsumedInLast24Hours();
            double hourlyConsume = consumed24h / 24.0d;
            boolean lowByThreshold = count < threshold;
            boolean lowByForecast = hourlyConsume > 0 && count < hourlyConsume * FORECAST_HOURS;

            String depletionHoursText;
            if (hourlyConsume <= 0) {
                depletionHoursText = "无法预计（近24小时无消耗）";
            } else {
                double depletionHours = count / hourlyConsume;
                depletionHoursText = String.format("%.1f 小时", depletionHours);
                result.put("estimatedDepletionHours", depletionHours);
            }

            result.put("count", count);
            result.put("consumed24h", consumed24h);
            result.put("forecastHours", FORECAST_HOURS);
            result.put("lowByThreshold", lowByThreshold);
            result.put("lowByForecast", lowByForecast);
            if (!lowByThreshold && !lowByForecast) {
                result.put("sent", false);
                result.put("message", "库存充足，未触发告警");
                return result;
            }

            List<String> reasons = new ArrayList<>();
            if (lowByThreshold) {
                reasons.add("当前可用邮箱数量低于阈值");
            }
            if (lowByForecast) {
                reasons.add(String.format("当前库存不足覆盖未来 %d 小时预计消耗", FORECAST_HOURS));
            }

            String content = String.format(
                    "<font color=\"warning\">警告: 邮箱池数量告警</font>\n" +
                            "时间: %s\n" +
                            "详情:\n" +
                            "当前可用邮箱数量：%d\n" +
                            "低于阈值：%d\n" +
                            "近24小时消耗邮箱：%d\n" +
                            "预计耗尽时间：%s\n" +
                            "触发原因：%s",
                    LocalDateTime.now().format(ALERT_TIME_FMT),
                    count,
                    threshold,
                    consumed24h,
                    depletionHoursText,
                    String.join("；", reasons)
            );
            sendWecomMarkdown(content);
            result.put("sent", true);
            result.put("message", "告警已发送");
            log.warn("邮箱池低库存告警已发送: count={}, threshold={}, consumed24h={}, hourlyConsume={}, lowByThreshold={}, lowByForecast={}, cron={}",
                    count, threshold, consumed24h, hourlyConsume, lowByThreshold, lowByForecast, alertCron);
        } catch (Exception e) {
            log.error("邮箱池低库存告警执行失败", e);
            result.put("sent", false);
            result.put("message", "告警执行失败: " + e.getMessage());
        }
        return result;
    }

    private void sendWecomMarkdown(String content) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("msgtype", "markdown");
        Map<String, Object> markdown = new HashMap<>();
        markdown.put("content", content);
        payload.put("markdown", markdown);

        String json = objectMapper.writeValueAsString(payload);
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(wecomWebhook)
                .post(body)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String resp = response.body() != null ? response.body().string() : "";
                throw new RuntimeException("企业微信机器人调用失败: " + response.code() + ", body=" + resp);
            }
        }
    }
}

