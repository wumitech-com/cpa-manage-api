package com.cpa.service;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * CPA API服务
 * 用于调用CPA API接口（如ResetPhoneEnv等）
 */
@Slf4j
@Service
public class ApiService {

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    
    @Value("${cpa-api.base-url:http://cpa-api:8080}")
    private String baseUrl;
    
    // 初始化OkHttpClient和ObjectMapper
    public ApiService() {
        // 配置连接池以复用HTTP连接，减少TCP连接数
        // 最大空闲连接数：50，保持连接时间：5分钟
        ConnectionPool connectionPool = new ConnectionPool(50, 5, TimeUnit.MINUTES);
        
        this.okHttpClient = new OkHttpClient.Builder()
                .connectionPool(connectionPool)  // 使用连接池复用连接
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(600, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 重置手机环境（合并reset和换机功能）
     * 
     * @param phoneId 云手机ID
     * @param serverIp 服务器IP
     * @param country 国家代码（如US、BR等）
     * @param sdk SDK版本（如33）
     * @param imagePath 镜像路径（如uhub.service.ucloud.cn/phone/android13_cpu:20251120）
     * @param gaidTag GAID标签（如20250410）
     * @param dynamicIpChannel 动态IP渠道（可选值：ipidea、closeli、netnut，为空代表不用动态IP）
     * @param staticIpChannel 静态IP渠道（可选值：ipidea，为空则代表不用静态IP，纯动态）
     * @param biz 业务标识（可选）
     * @return 执行结果，包含real_ip
     */
    public Map<String, Object> resetPhoneEnv(String phoneId, String serverIp, String country, 
                                             String sdk, String imagePath, String gaidTag,
                                             String dynamicIpChannel, String staticIpChannel, String biz) {
        try {
            log.info("调用ResetPhoneEnv API: phoneId={}, serverIp={}, country={}, sdk={}, imagePath={}, gaidTag={}, dynamicIpChannel={}, staticIpChannel={}, biz={}", 
                    phoneId, serverIp, country, sdk, imagePath, gaidTag, dynamicIpChannel, staticIpChannel, biz);
            
            // 验证参数：dynamicIpChannel和staticIpChannel不能同时为空
            if ((dynamicIpChannel == null || dynamicIpChannel.isEmpty()) && 
                (staticIpChannel == null || staticIpChannel.isEmpty())) {
                throw new IllegalArgumentException("dynamicIpChannel和staticIpChannel不能同时为空");
            }
            
            // 构建URL，使用查询参数
            HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + "/ResetPhoneEnv").newBuilder();
            urlBuilder.addQueryParameter("phone_id", phoneId);
            urlBuilder.addQueryParameter("phone_server_ip", serverIp);
            urlBuilder.addQueryParameter("country", country);
            urlBuilder.addQueryParameter("sdk", sdk);
            urlBuilder.addQueryParameter("image_path", imagePath);
            
            // gaid_tag 参数不再传递
            // if (gaidTag != null && !gaidTag.isEmpty()) {
            //     urlBuilder.addQueryParameter("gaid_tag", gaidTag);
            // }
            
            if (dynamicIpChannel != null && !dynamicIpChannel.isEmpty()) {
                urlBuilder.addQueryParameter("dynamic_ip_channel", dynamicIpChannel);
            }
            if (staticIpChannel != null && !staticIpChannel.isEmpty()) {
                urlBuilder.addQueryParameter("static_ip_channel", staticIpChannel);
            }
            if (biz != null && !biz.isEmpty()) {
                urlBuilder.addQueryParameter("biz", biz);
            }
            
            String url = urlBuilder.build().toString();
            log.debug("ResetPhoneEnv请求URL: {}", url);
            
            // 创建请求体（空body，text/plain类型）
            MediaType mediaType = MediaType.parse("text/plain");
            RequestBody body = RequestBody.create("", mediaType);
            
            // 构建请求
            Request request = new Request.Builder()
                    .url(url)
                    .method("POST", body)
                    .build();
            
            // 发送请求
            try (Response response = okHttpClient.newCall(request).execute()) {
                int statusCode = response.code();
                String responseBody = response.body() != null ? response.body().string() : null;
            
                if (statusCode >= 200 && statusCode < 300) {
                    // 解析响应JSON
                    if (responseBody != null && !responseBody.isEmpty()) {
                @SuppressWarnings("unchecked")
                        Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
                log.info("ResetPhoneEnv API调用成功: {}", result);
                return result;
            } else {
                        log.error("ResetPhoneEnv API调用成功但响应体为空: status={}", statusCode);
                        throw new RuntimeException("ResetPhoneEnv API调用成功但响应体为空");
            }
                } else {
                    // 处理错误状态码
                    if (statusCode == 502 || statusCode == 503 || statusCode == 504) {
                        log.error("调用ResetPhoneEnv API网关错误 ({}): phoneId={}, 响应: {}", 
                                statusCode, phoneId, responseBody);
                        throw new RuntimeException("调用ResetPhoneEnv API网关错误 (" + statusCode + "): " + responseBody);
            } else {
                        log.error("调用ResetPhoneEnv API服务器错误 ({}): phoneId={}, 响应: {}", 
                                statusCode, phoneId, responseBody);
                        throw new RuntimeException("调用ResetPhoneEnv API服务器错误 (" + statusCode + "): " + responseBody);
                    }
                }
            }
            
        } catch (IOException e) {
            log.error("调用ResetPhoneEnv API IO异常: phoneId={}", phoneId, e);
            throw new RuntimeException("调用ResetPhoneEnv API失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("调用ResetPhoneEnv API异常: phoneId={}", phoneId, e);
            throw new RuntimeException("调用ResetPhoneEnv API失败: " + e.getMessage(), e);
        }
    }

    /**
     * 重置手机（TTFarmResetPhone，主要用于 10.13 网段服务器）
     *
     * 实际调用方式与后端一致：所有参数通过 query 传递，body 为空 text/plain。
     */
    public Map<String, Object> ttFarmResetPhone(String phoneId,
                                                String phoneServerIp,
                                                String xrayServerIp,
                                                String country,
                                                String sdk,
                                                String imagePath,
                                                String dynamicIpChannel,
                                                boolean fastSwitch) {
        try {
            log.info("调用TTFarmResetPhone API: phoneId={}, phoneServerIp={}, xrayServerIp={}, country={}, sdk={}, imagePath={}, dynamicIpChannel={}, fastSwitch={}",
                    phoneId, phoneServerIp, xrayServerIp, country, sdk, imagePath, dynamicIpChannel, fastSwitch);

            HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + "/TTFarmResetPhone").newBuilder();
            urlBuilder.addQueryParameter("phone_id", phoneId);
            urlBuilder.addQueryParameter("phone_server_ip", phoneServerIp);
            if (xrayServerIp != null && !xrayServerIp.isEmpty()) {
                urlBuilder.addQueryParameter("xray_server_ip", xrayServerIp);
            }
            urlBuilder.addQueryParameter("country", country);
            urlBuilder.addQueryParameter("sdk", sdk);
            urlBuilder.addQueryParameter("image_path", imagePath);
            urlBuilder.addQueryParameter("dynamic_ip_channel", dynamicIpChannel);
            urlBuilder.addQueryParameter("fast_switch", String.valueOf(fastSwitch));

            String url = urlBuilder.build().toString();
            log.debug("TTFarmResetPhone请求URL: {}", url);

            // 按对方接口实际用法，body 为空 text/plain
            MediaType mediaType = MediaType.parse("text/plain");
            RequestBody body = RequestBody.create("", mediaType);

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                int statusCode = response.code();
                String responseBody = response.body() != null ? response.body().string() : null;

                if (statusCode >= 200 && statusCode < 300) {
                    if (responseBody != null && !responseBody.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
                        log.info("TTFarmResetPhone API调用成功: {}", result);
                        return result;
                    } else {
                        log.error("TTFarmResetPhone API调用成功但响应体为空: status={}", statusCode);
                        throw new RuntimeException("TTFarmResetPhone API调用成功但响应体为空");
                    }
                } else {
                    log.error("调用TTFarmResetPhone API服务器错误 ({}): phoneId={}, 响应: {}",
                            statusCode, phoneId, responseBody);
                    throw new RuntimeException("调用TTFarmResetPhone API失败 (" + statusCode + "): " + responseBody);
                }
            }
        } catch (IOException e) {
            log.error("调用TTFarmResetPhone API IO异常: phoneId={}", phoneId, e);
            throw new RuntimeException("调用TTFarmResetPhone API失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("调用TTFarmResetPhone API异常: phoneId={}", phoneId, e);
            throw new RuntimeException("调用TTFarmResetPhone API失败: " + e.getMessage(), e);
        }
    }

    /**
     * 恢复应用（RestoreApp，用于留存任务）
     *
     * @param phoneId 云手机ID
     * @param serverIp 服务器IP
     * @param packageName 包名（如 com.zhiliaoapp.musically）
     * @param imagePath 镜像路径
     * @param gaid 账号 GAID
     * @return 执行结果
     */
    public Map<String, Object> restoreApp(String phoneId, String serverIp, String packageName,
                                          String imagePath, String gaid) {
        try {
            log.info("调用RestoreApp API: phoneId={}, serverIp={}, packageName={}, gaid={}",
                    phoneId, serverIp, packageName, gaid);
            HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + "/RestoreApp").newBuilder();
            urlBuilder.addQueryParameter("phone_id", phoneId);
            urlBuilder.addQueryParameter("phone_server_ip", serverIp);
            urlBuilder.addQueryParameter("package_name", packageName);
            urlBuilder.addQueryParameter("image_path", imagePath != null ? imagePath : "");
            urlBuilder.addQueryParameter("gaid", gaid != null ? gaid : "");
            urlBuilder.addQueryParameter("restore_phone", "true");
            String url = urlBuilder.build().toString();
            MediaType mediaType = MediaType.parse("text/plain");
            RequestBody body = RequestBody.create("", mediaType);
            Request request = new Request.Builder().url(url).method("POST", body).build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                int statusCode = response.code();
                String responseBody = response.body() != null ? response.body().string() : null;
                if (statusCode >= 200 && statusCode < 300) {
                    if (responseBody != null && !responseBody.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
                        log.info("RestoreApp API调用成功: phoneId={}", phoneId);
                        return result;
                    }
                    throw new RuntimeException("RestoreApp API响应体为空");
                }
                log.error("调用RestoreApp API错误 ({}): phoneId={}, 响应: {}", statusCode, phoneId, responseBody);
                throw new RuntimeException("RestoreApp API失败 (" + statusCode + "): " + responseBody);
            }
        } catch (IOException e) {
            log.error("调用RestoreApp API IO异常: phoneId={}", phoneId, e);
            throw new RuntimeException("RestoreApp API失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("调用RestoreApp API异常: phoneId={}", phoneId, e);
            throw new RuntimeException("RestoreApp API失败: " + e.getMessage(), e);
        }
    }

    /**
     * 设置云手机网络（TTFarmSetupNetwork，用于恢复/留存后配置 IP）
     *
     * @param phoneId     云手机ID
     * @param serverIp    云手机所在服务器IP
     * @param countryCode 国家代码（如 US、MX）
     * @param hasStaticIp 是否有静态IP
     * @return 是否调用成功（responseStatus.code == 0）
     */
    public boolean ttFarmSetupNetwork(String phoneId, String serverIp, String countryCode, boolean hasStaticIp) {
        try {
            log.info("调用TTFarmSetupNetwork API: phoneId={}, serverIp={}, countryCode={}, hasStaticIp={}",
                    phoneId, serverIp, countryCode, hasStaticIp);

            HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + "/TTFarmSetupNetwork").newBuilder();
            urlBuilder.addQueryParameter("phone_id", phoneId);
            urlBuilder.addQueryParameter("phone_server_ip", serverIp);
            urlBuilder.addQueryParameter("country_code", countryCode);
            urlBuilder.addQueryParameter("has_static_ip", String.valueOf(hasStaticIp));

            String url = urlBuilder.build().toString();
            log.debug("TTFarmSetupNetwork 请求URL: {}", url);

            MediaType mediaType = MediaType.parse("text/plain");
            RequestBody body = RequestBody.create("", mediaType);

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                int statusCode = response.code();
                String responseBody = response.body() != null ? response.body().string() : null;

                if (statusCode >= 200 && statusCode < 300) {
                    if (responseBody != null && !responseBody.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> responseStatus = (Map<String, Object>) result.get("responseStatus");
                        if (responseStatus != null) {
                            Integer code = (Integer) responseStatus.get("code");
                            String message = (String) responseStatus.get("message");
                            String dynamicIp = (String) result.get("dynamicIp");
                            String staticIp = (String) result.get("staticIp");
                            String errMsg = (String) result.get("errMsg");
                            log.info("TTFarmSetupNetwork 响应: code={}, message={}, dynamicIp={}, staticIp={}, errMsg={}",
                                    code, message, dynamicIp, staticIp, errMsg);
                            return code != null && code == 0;
                        } else {
                            log.error("TTFarmSetupNetwork 响应缺少responseStatus: phoneId={}, body={}", phoneId, responseBody);
                            return false;
                        }
                    } else {
                        log.error("TTFarmSetupNetwork API调用成功但响应体为空: phoneId={}, status={}", phoneId, statusCode);
                        return false;
                    }
                } else {
                    log.error("调用TTFarmSetupNetwork API服务器错误 ({}): phoneId={}, 响应: {}",
                            statusCode, phoneId, responseBody);
                    return false;
                }
            }
        } catch (IOException e) {
            log.error("调用TTFarmSetupNetwork API IO异常: phoneId={}", phoneId, e);
            return false;
        } catch (Exception e) {
            log.error("调用TTFarmSetupNetwork API异常: phoneId={}", phoneId, e);
            return false;
        }
    }

    /**
     * 获取流量数据
     * 
     * @param phoneId 云手机ID
     * @return 流量数据字符串，如果获取失败返回null
     */
    public String getTrafficData(String phoneId) {
        try {
            log.info("调用GetTrafficData API: phoneId={}", phoneId);
            
            // 构建URL，使用查询参数
            HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + "/GetTrafficData").newBuilder();
            urlBuilder.addQueryParameter("phone_id", phoneId);
            
            String url = urlBuilder.build().toString();
            log.debug("GetTrafficData请求URL: {}", url);
            
            // 创建请求体（空body，text/plain类型）
            MediaType mediaType = MediaType.parse("text/plain");
            RequestBody body = RequestBody.create("", mediaType);
            
            // 构建请求
            Request request = new Request.Builder()
                    .url(url)
                    .method("POST", body)
                    .build();
            
            // 发送请求
            try (Response response = okHttpClient.newCall(request).execute()) {
                int statusCode = response.code();
                String responseBody = response.body() != null ? response.body().string() : null;
                
                if (statusCode >= 200 && statusCode < 300) {
                    // 解析响应JSON
                    if (responseBody != null && !responseBody.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
                        
                        // 检查响应状态
                        @SuppressWarnings("unchecked")
                        Map<String, Object> responseStatus = (Map<String, Object>) result.get("responseStatus");
                        if (responseStatus != null) {
                            Integer code = (Integer) responseStatus.get("code");
                            if (code != null && code == 0) {
                                // 成功，提取data字段
                                String trafficData = (String) result.get("data");
                                log.info("GetTrafficData API调用成功: phoneId={}, trafficData={}", phoneId, trafficData);
                                return trafficData;
                            } else {
                                String message = (String) responseStatus.get("message");
                                log.error("GetTrafficData API返回非0状态码: phoneId={}, code={}, message={}", 
                                        phoneId, code, message);
                                return null;
                            }
                        } else {
                            log.error("GetTrafficData API响应格式错误，缺少responseStatus: phoneId={}, 响应: {}", 
                                    phoneId, responseBody);
                            return null;
                        }
                    } else {
                        log.error("GetTrafficData API调用成功但响应体为空: phoneId={}, status={}", phoneId, statusCode);
                        return null;
                    }
                } else {
                    log.error("调用GetTrafficData API服务器错误 ({}): phoneId={}, 响应: {}", 
                            statusCode, phoneId, responseBody);
                    return null;
                }
            }
            
        } catch (IOException e) {
            log.error("调用GetTrafficData API IO异常: phoneId={}", phoneId, e);
            return null;
        } catch (Exception e) {
            log.error("调用GetTrafficData API异常: phoneId={}", phoneId, e);
            return null;
        }
    }

    /**
     * 备份应用
     * 
     * @param phoneId 云手机ID
     * @param serverIp 服务器IP
     * @param packageName 包名（如com.zhiliaoapp.musically）
     * @return 执行结果，如果成功返回true，失败返回false
     */
    public boolean backupApp(String phoneId, String serverIp, String packageName) {
        try {
            log.info("调用BackupApp API: phoneId={}, serverIp={}, packageName={}", phoneId, serverIp, packageName);
            
            // 构建URL，使用查询参数
            HttpUrl.Builder urlBuilder = HttpUrl.parse(baseUrl + "/BackupApp").newBuilder();
            urlBuilder.addQueryParameter("phone_id", phoneId);
            urlBuilder.addQueryParameter("phone_server_ip", serverIp);
            urlBuilder.addQueryParameter("package_name", packageName);
            
            String url = urlBuilder.build().toString();
            log.debug("BackupApp请求URL: {}", url);
            
            // 创建请求体（空body，text/plain类型）
            MediaType mediaType = MediaType.parse("text/plain");
            RequestBody body = RequestBody.create("", mediaType);
            
            // 构建请求
            Request request = new Request.Builder()
                    .url(url)
                    .method("POST", body)
                    .build();
            
            // 发送请求
            try (Response response = okHttpClient.newCall(request).execute()) {
                int statusCode = response.code();
                String responseBody = response.body() != null ? response.body().string() : null;
                
                if (statusCode >= 200 && statusCode < 300) {
                    // 解析响应JSON，检查responseStatus.code
                    if (responseBody != null && !responseBody.isEmpty()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
                            
                            // 检查响应状态
                            @SuppressWarnings("unchecked")
                            Map<String, Object> responseStatus = (Map<String, Object>) result.get("responseStatus");
                            if (responseStatus != null) {
                                Integer code = (Integer) responseStatus.get("code");
                                String message = (String) responseStatus.get("message");
                                if (code != null && code == 0) {
                                    log.info("BackupApp API调用成功: phoneId={}, code={}, message={}", phoneId, code, message);
                                    return true;
                                } else {
                                    log.error("BackupApp API返回非0状态码: phoneId={}, code={}, message={}", phoneId, code, message);
                                    return false;
                                }
                            } else {
                                log.error("BackupApp API响应格式错误，缺少responseStatus: phoneId={}, 响应: {}", phoneId, responseBody);
                                return false;
                            }
                        } catch (Exception e) {
                            log.error("BackupApp API响应解析失败: phoneId={}, 响应: {}", phoneId, responseBody, e);
                            return false;
                        }
                    } else {
                        log.error("BackupApp API调用成功但响应体为空: phoneId={}, status={}", phoneId, statusCode);
                        return false;
                    }
                } else {
                    log.error("调用BackupApp API服务器错误 ({}): phoneId={}, 响应: {}", 
                            statusCode, phoneId, responseBody);
                    return false;
                }
            }
            
        } catch (IOException e) {
            log.error("调用BackupApp API IO异常: phoneId={}", phoneId, e);
            return false;
        } catch (Exception e) {
            log.error("调用BackupApp API异常: phoneId={}", phoneId, e);
            return false;
        }
    }

    /**
     * 主板机换机任务（调用主板机API）
     * 
     * @param phoneId 主板机ID
     * @param countryCode 国家代码（如US）
     * @param tiktokVersion TikTok版本（如43.2.1）
     * @return 任务ID，如果失败返回null
     */
    public String switchPhoneTask(String phoneId, String countryCode, String tiktokVersion) {
        try {
            log.info("调用主板机换机API: phoneId={}, countryCode={}, tiktokVersion={}", phoneId, countryCode, tiktokVersion);
            
            // 主板机API地址（内网Docker部署）
            String mainboardApiUrl = "http://127.0.0.1:50054/openapi/v1/phone/switch/add_task";
            
            // 构建请求体JSON（使用ObjectMapper确保JSON格式正确）
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("phone_id", phoneId);
            requestBody.put("country_code", countryCode);
            
            List<Map<String, String>> apps = new ArrayList<>();
            Map<String, String> app = new HashMap<>();
            app.put("pkg_name", "com.zhiliaoapp.musically");
            app.put("version", tiktokVersion);
            apps.add(app);
            requestBody.put("apps", apps);
            
            String requestBodyJson = objectMapper.writeValueAsString(requestBody);
            
            log.debug("主板机换机请求URL: {}", mainboardApiUrl);
            log.debug("主板机换机请求体: {}", requestBodyJson);
            
            // 创建请求体（JSON格式）
            MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
            RequestBody body = RequestBody.create(requestBodyJson, mediaType);
            
            // 构建请求
            Request request = new Request.Builder()
                    .url(mainboardApiUrl)
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build();
            
            // 发送请求
            try (Response response = okHttpClient.newCall(request).execute()) {
                int statusCode = response.code();
                String responseBody = response.body() != null ? response.body().string() : null;
                
                if (statusCode >= 200 && statusCode < 300) {
                    if (responseBody != null && !responseBody.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
                        
                        // 检查响应状态
                        @SuppressWarnings("unchecked")
                        Map<String, Object> responseObj = (Map<String, Object>) result.get("response");
                        if (responseObj != null) {
                            Integer code = (Integer) responseObj.get("code");
                            if (code != null && code == 0) {
                                // 成功，提取task_id
                                String taskId = (String) result.get("task_id");
                                log.info("主板机换机API调用成功: phoneId={}, taskId={}", phoneId, taskId);
                                return taskId;
                            } else {
                                String errMsg = (String) responseObj.get("err_msg");
                                log.error("主板机换机API返回非0状态码: phoneId={}, code={}, err_msg={}", phoneId, code, errMsg);
                                return null;
                            }
                        } else {
                            log.error("主板机换机API响应格式错误，缺少response: phoneId={}, 响应: {}", phoneId, responseBody);
                            return null;
                        }
                    } else {
                        log.error("主板机换机API调用成功但响应体为空: phoneId={}, status={}", phoneId, statusCode);
                        return null;
                    }
                } else {
                    log.error("调用主板机换机API服务器错误 ({}): phoneId={}, 响应: {}", 
                            statusCode, phoneId, responseBody);
                    return null;
                }
            }
            
        } catch (IOException e) {
            log.error("调用主板机换机API IO异常: phoneId={}", phoneId, e);
            return null;
        } catch (Exception e) {
            log.error("调用主板机换机API异常: phoneId={}", phoneId, e);
            return null;
        }
    }

    /**
     * 查询主板机任务状态
     * 
     * @param taskId 任务ID
     * @return 任务状态信息，如果失败返回null
     */
    public Map<String, Object> getMainboardTaskStatus(String taskId) {
        try {
            log.info("查询主板机任务状态: taskId={}", taskId);
            
            // 主板机API地址（内网Docker部署）
            String mainboardApiUrl = "http://127.0.0.1:50054/openapi/v1/task/status";
            
            // 构建请求体JSON（使用ObjectMapper确保JSON格式正确）
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("task_id", taskId);
            String requestBodyJson = objectMapper.writeValueAsString(requestBody);
            
            log.debug("主板机任务状态查询URL: {}", mainboardApiUrl);
            log.debug("主板机任务状态查询请求体: {}", requestBodyJson);
            
            // 创建请求体（JSON格式）
            MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
            RequestBody body = RequestBody.create(requestBodyJson, mediaType);
            
            // 构建请求
            Request request = new Request.Builder()
                    .url(mainboardApiUrl)
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build();
            
            // 发送请求
            try (Response response = okHttpClient.newCall(request).execute()) {
                int statusCode = response.code();
                String responseBody = response.body() != null ? response.body().string() : null;
                
                if (statusCode >= 200 && statusCode < 300) {
                    if (responseBody != null && !responseBody.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
                        log.info("主板机任务状态查询成功: taskId={}, 响应: {}", taskId, result);
                        return result;
                    } else {
                        log.error("主板机任务状态查询成功但响应体为空: taskId={}, status={}", taskId, statusCode);
                        return null;
                    }
                } else {
                    log.error("查询主板机任务状态服务器错误 ({}): taskId={}, 响应: {}", 
                            statusCode, taskId, responseBody);
                    return null;
                }
            }
            
        } catch (IOException e) {
            log.error("查询主板机任务状态IO异常: taskId={}", taskId, e);
            return null;
        } catch (Exception e) {
            log.error("查询主板机任务状态异常: taskId={}", taskId, e);
            return null;
        }
    }

    /**
     * 获取连接池统计信息
     * 用于监控连接池状态，便于调优
     * 
     * @return 连接池统计信息字符串
     */
    public String getConnectionPoolStats() {
        ConnectionPool pool = okHttpClient.connectionPool();
        return String.format("连接池统计: 空闲连接=%d, 总连接=%d", 
                pool.idleConnectionCount(), pool.connectionCount());
    }
}

