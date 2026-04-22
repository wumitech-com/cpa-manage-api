package com.cpa.controller;

import com.cpa.service.TtRegisterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TT账号批量注册控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/tt-register")
@RequiredArgsConstructor
public class TtRegisterController {

    private final TtRegisterService ttRegisterService;

    /**
     * 批量注册TT账号
     * 
     * 请求参数格式:
     * {
     *   "phoneIds": ["phone_id_1", "phone_id_2"],
     *   "serverIp": "10.7.107.224",
     *   "resetParams": {
     *     "country": "BR",
     *     "sdk": "33",
     *     "imagePath": "uhub.service.ucloud.cn/phone/android13_cpu:20251120",
     *     "gaidTag": "20250410",
     *     "dynamicIpChannel": "closeli",
     *     "staticIpChannel": "",
     *     "biz": ""
     *   }
     * }
     */
    @PostMapping("/batch")
    public Map<String, Object> batchRegisterTtAccounts(@RequestBody Map<String, Object> request) {
        log.info("接收到批量注册TT账号请求，参数: {}", request);
        
        @SuppressWarnings("unchecked")
        List<String> phoneIds = (List<String>) request.get("phoneIds");
        String serverIp = (String) request.get("serverIp");
        
        if (phoneIds == null || phoneIds.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "phoneIds参数不能为空");
            return errorResponse;
        }
        
        if (serverIp == null || serverIp.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "serverIp参数不能为空");
            return errorResponse;
        }
        
        // 获取ResetPhoneEnv参数（可选）
        @SuppressWarnings("unchecked")
        Map<String, String> resetParams = (Map<String, String>) request.get("resetParams");
        
        return ttRegisterService.batchRegisterTtAccounts(phoneIds, serverIp, resetParams);
    }

    /**
     * 新增留存任务（task_kind=RETENTION）
     * 请求体: { "phoneId": "tt_farm_xxx", "serverIp": "10.7.136.129", "targetCount": 50, "country": "US", "imagePath": "" }
     */
    @PostMapping("/retention")
    public Map<String, Object> createRetentionTask(@RequestBody Map<String, Object> request) {
        log.info("接收到创建留存任务请求，参数: {}", request);
        String phoneId = (String) request.get("phoneId");
        String serverIp = (String) request.get("serverIp");
        if (phoneId == null || phoneId.isEmpty() || serverIp == null || serverIp.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "phoneId 与 serverIp 不能为空");
            return err;
        }
        Object tc = request.get("targetCount");
        Integer targetCount = tc != null ? (tc instanceof Number ? ((Number) tc).intValue() : null) : null;
        String country = (String) request.get("country");
        String imagePath = (String) request.get("imagePath");
        com.cpa.entity.TtRegisterTask task = ttRegisterService.createRetentionTask(phoneId, serverIp, targetCount, country, imagePath);
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("message", "留存任务已创建");
        resp.put("taskId", task.getTaskId());
        resp.put("taskKind", task.getTaskKind());
        return resp;
    }

    /**
     * 多设备并行注册TT账号（多个设备同时注册，每个设备可注册多个账号）
     * 
     * 请求参数格式:
     * {
     *   "phoneIds": ["phone_id_1", "phone_id_2", "phone_id_3"],
     *   "serverIp": "10.7.107.224",
     *   "maxConcurrency": 10,
     *   "targetCountPerDevice": 1,
     *   "resetParams": {
     *     "country": "BR",
     *     "sdk": "33",
     *     "imagePath": "uhub.service.ucloud.cn/phone/android13_cpu:20251120",
     *     "gaidTag": "20250410",
     *     "dynamicIpChannel": "closeli",
     *     "staticIpChannel": "",
     *     "biz": ""
     *   }
     * }
     */
    @PostMapping("/parallel")
    public Map<String, Object> parallelRegisterMultipleDevices(@RequestBody Map<String, Object> request) {
        log.info("接收到多设备并行注册请求，参数: {}", request);
        
        @SuppressWarnings("unchecked")
        List<String> phoneIds = (List<String>) request.get("phoneIds");
        String serverIp = (String) request.get("serverIp");
        Object maxConcurrencyObj = request.get("maxConcurrency");
        Object targetCountPerDeviceObj = request.get("targetCountPerDevice");
        
        if (phoneIds == null || phoneIds.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "phoneIds参数不能为空");
            return errorResponse;
        }
        
        if (serverIp == null || serverIp.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "serverIp参数不能为空");
            return errorResponse;
        }
        
        Integer maxConcurrency = null;
        if (maxConcurrencyObj != null) {
            if (maxConcurrencyObj instanceof Integer) {
                maxConcurrency = (Integer) maxConcurrencyObj;
            } else if (maxConcurrencyObj instanceof String) {
                try {
                    maxConcurrency = Integer.parseInt((String) maxConcurrencyObj);
                } catch (NumberFormatException e) {
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("message", "maxConcurrency参数格式错误，必须是数字");
                    return errorResponse;
                }
            }
        }
        
        if (maxConcurrency != null && (maxConcurrency <= 0 || maxConcurrency > 100)) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "maxConcurrency参数必须在1-100之间");
            return errorResponse;
        }
        
        Integer targetCountPerDevice = null;
        if (targetCountPerDeviceObj != null) {
            if (targetCountPerDeviceObj instanceof Integer) {
                targetCountPerDevice = (Integer) targetCountPerDeviceObj;
            } else if (targetCountPerDeviceObj instanceof String) {
                try {
                    targetCountPerDevice = Integer.parseInt((String) targetCountPerDeviceObj);
                } catch (NumberFormatException e) {
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("message", "targetCountPerDevice参数格式错误，必须是数字");
                    return errorResponse;
                }
            }
        }
        
        // 允许0（无限循环）或1-1000之间的值
        if (targetCountPerDevice != null && targetCountPerDevice != 0 && (targetCountPerDevice < 1 || targetCountPerDevice > 1000)) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "targetCountPerDevice参数必须是0（无限循环）或1-1000之间的数字");
            return errorResponse;
        }
        
        // 获取TikTok版本目录（必填）
        String tiktokVersionDir = (String) request.get("tiktokVersionDir");
        if (tiktokVersionDir == null || tiktokVersionDir.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "tiktokVersionDir参数不能为空");
            return errorResponse;
        }
        
        // 获取ResetPhoneEnv参数（可选）
        @SuppressWarnings("unchecked")
        Map<String, String> resetParams = (Map<String, String>) request.get("resetParams");
        
        return ttRegisterService.parallelRegisterMultipleDevices(phoneIds, serverIp, maxConcurrency, targetCountPerDevice, tiktokVersionDir, resetParams);
    }
    
    /**
     * 查询批量注册任务状态
     * 
     * @param taskId 任务ID
     * @return 任务状态
     */
    @GetMapping("/status/{taskId}")
    public Map<String, Object> getTaskStatus(@PathVariable String taskId) {
        log.info("查询批量注册任务状态，taskId: {}", taskId);
        return ttRegisterService.getTaskStatus(taskId);
    }
    
    /**
     * 获取任务日志
     * 
     * @param taskId 任务ID
     * @param lines 读取行数（可选，默认500行）
     * @return 日志内容
     */
    @GetMapping("/log/{taskId}")
    public Map<String, Object> getTaskLog(@PathVariable String taskId,
                                          @RequestParam(required = false, defaultValue = "500") int lines) {
        log.info("查询批量注册任务日志，taskId: {}, lines: {}", taskId, lines);
        return ttRegisterService.getTaskLog(taskId, lines);
    }
    
    /**
     * 停止任务
     * 
     * @param taskId 任务ID
     * @return 停止结果
     */
    @PostMapping("/stop/{taskId}")
    public Map<String, Object> stopTask(@PathVariable String taskId) {
        log.info("停止任务请求，taskId: {}", taskId);
        return ttRegisterService.stopTask(taskId);
    }
    
    /**
     * 获取所有任务列表（分页）
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return 任务列表分页数据
     */
    @GetMapping("/tasks")
    public Map<String, Object> getAllTasks(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size,
                                           @RequestParam(required = false) String taskId,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String serverIp) {
        log.info("获取任务列表, page={}, size={}, taskId={}, status={}, serverIp={}",
                page, size, taskId, status, serverIp);
        if (page < 1) {
            page = 1;
        }
        if (size <= 0) {
            size = 20;
        }
        return ttRegisterService.getAllTasks(page, size, taskId, status, serverIp);
    }

    /**
     * 账号管理列表（分页+筛选）
     */
    @GetMapping("/account/list")
    public Map<String, Object> getAccountList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String registerStatus,
            @RequestParam(required = false) String keyStatus,
            @RequestParam(required = false) String matureStatus,
            @RequestParam(required = false) String emailBindStatus,
            @RequestParam(required = false) String blockStatus,
            @RequestParam(required = false) String sellStatus,
            @RequestParam(required = false) String shopStatus,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String accountType,
            @RequestParam(required = false) String note,
            @RequestParam(required = false, defaultValue = "desc") String sortOrder) {
        String usernameOrEmail = (username != null && !username.trim().isEmpty()) ? username : email;
        log.info("查询账号管理列表，page={}, size={}, startDate={}, endDate={}, username={}, country={}, region={}, registerStatus={}, keyStatus={}, matureStatus={}, emailBindStatus={}, blockStatus={}, sellStatus={}, shopStatus={}, status={}, accountType={}, sortOrder={}",
                page, size, startDate, endDate, usernameOrEmail, country, region,
                registerStatus, keyStatus, matureStatus, emailBindStatus, blockStatus, sellStatus, shopStatus,
                status, accountType, sortOrder);
        return ttRegisterService.getAccountManageList(
                page, size, startDate, endDate, usernameOrEmail, country, region,
                registerStatus, keyStatus, matureStatus, emailBindStatus, blockStatus, sellStatus, shopStatus,
                status, accountType, note, sortOrder);
    }

    /**
     * 账号管理：按列表日期范围统计注册成功数、2FA成功数与比例
     */
    @GetMapping("/account/date-summary")
    public Map<String, Object> getAccountDateSummary(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ttRegisterService.getAccountDateSummary(startDate, endDate);
    }

    /**
     * 账号管理：在当前筛选条件下统计各维度数量（用于占比，与列表筛选一致）
     */
    @GetMapping("/account/filter-stats")
    public Map<String, Object> getAccountFilterStats(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String registerStatus,
            @RequestParam(required = false) String keyStatus,
            @RequestParam(required = false) String matureStatus,
            @RequestParam(required = false) String emailBindStatus,
            @RequestParam(required = false) String blockStatus,
            @RequestParam(required = false) String sellStatus,
            @RequestParam(required = false) String shopStatus,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String accountType,
            @RequestParam(required = false) String note) {
        String usernameOrEmail = (username != null && !username.trim().isEmpty()) ? username : email;
        return ttRegisterService.getAccountFilterStats(
                startDate, endDate, usernameOrEmail, country, region,
                registerStatus, keyStatus, matureStatus, emailBindStatus, blockStatus, sellStatus, shopStatus,
                status, accountType, note);
    }

    /**
     * 开窗管理列表（分页+筛选）
     */
    @GetMapping("/window/list")
    public Map<String, Object> getWindowList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String fanStartDate,
            @RequestParam(required = false) String fanEndDate,
            @RequestParam(required = false) String nurtureStartDate,
            @RequestParam(required = false) String nurtureEndDate,
            @RequestParam(required = false) String nurtureStrategy,
            @RequestParam(required = false) String shopStatus,
            @RequestParam(required = false) String nurtureDevice,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String note) {
        log.info("查询开窗管理列表，page={}, size={}, shopStatus={}, country={}, account={}",
                page, size, shopStatus, country, account);
        return ttRegisterService.getWindowManageList(
                page, size, fanStartDate, fanEndDate, nurtureStartDate, nurtureEndDate,
                nurtureStrategy, shopStatus, nurtureDevice, country, account, note);
    }

    /**
     * 查看设备：按 phone_id + gaid 恢复环境并返回连接命令
     */
    @PostMapping("/device/inspect")
    public Map<String, Object> inspectDevice(@RequestBody Map<String, Object> request) {
        String phoneId = request == null ? null : (String) request.get("phoneId");
        String gaid = request == null ? null : (String) request.get("gaid");
        log.info("查看设备请求: phoneId={}, gaid={}", phoneId, gaid);
        return ttRegisterService.inspectDeviceByPhoneAndGaid(phoneId, gaid);
    }

    /**
     * 账号管理：全量导出（同筛选条件，不分页）
     */
    @GetMapping("/account/export")
    public Map<String, Object> exportAccountList(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String registerStatus,
            @RequestParam(required = false) String keyStatus,
            @RequestParam(required = false) String matureStatus,
            @RequestParam(required = false) String emailBindStatus,
            @RequestParam(required = false) String blockStatus,
            @RequestParam(required = false) String sellStatus,
            @RequestParam(required = false) String shopStatus,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String accountType,
            @RequestParam(required = false) String note,
            @RequestParam(required = false, defaultValue = "desc") String sortOrder) {
        String usernameOrEmail = (username != null && !username.trim().isEmpty()) ? username : email;
        log.info("导出账号列表，startDate={}, endDate={}, status={}, country={}, username={}, registerStatus={}, keyStatus={}, matureStatus={}, emailBindStatus={}, blockStatus={}, sellStatus={}, shopStatus={}, sortOrder={}",
                startDate, endDate, status, country, usernameOrEmail,
                registerStatus, keyStatus, matureStatus, emailBindStatus, blockStatus, sellStatus, shopStatus,
                sortOrder);
        return ttRegisterService.exportAccountList(
                startDate, endDate, usernameOrEmail, country, region,
                registerStatus, keyStatus, matureStatus, emailBindStatus, blockStatus, sellStatus, shopStatus,
                status, accountType, note, sortOrder);
    }

    /**
     * 账号管理：详情
     */
    @GetMapping("/account/{id}")
    public Map<String, Object> getAccountDetail(@PathVariable Long id) {
        return ttRegisterService.getAccountDetail(id);
    }

    /**
     * 账号管理：更新
     */
    @PostMapping("/account/update")
    public Map<String, Object> updateAccount(@RequestBody Map<String, Object> request) {
        return ttRegisterService.updateAccount(request);
    }

    /**
     * 账号管理：开始养号（勾选批量）
     */
    @PostMapping("/account/start-nurture")
    public Map<String, Object> startNurture(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Object> rawIds = request == null ? null : (List<Object>) request.get("accountIds");
        List<Long> accountIds = new ArrayList<>();
        if (rawIds != null) {
            for (Object item : rawIds) {
                if (item == null) continue;
                try {
                    if (item instanceof Number) {
                        accountIds.add(((Number) item).longValue());
                    } else {
                        accountIds.add(Long.parseLong(String.valueOf(item)));
                    }
                } catch (Exception ignore) {
                    // ignore invalid id
                }
            }
        }
        log.info("开始养号请求，accountIds.size={}", accountIds.size());
        return ttRegisterService.startNurtureAccounts(accountIds);
    }

    /**
     * 账号管理：批量导入CSV（按 username 匹配，有则更新，无则插入）
     * CSV 格式（第一行为标题行，可选列）：
     *   username, email, password, status, note, country
     * status 可选值：封号 / 已售 / 可售 / 养号 / 橱窗 / 换绑成功 / 换绑失败 / 2FA失败 / 2FA成功-封号 / 2FA成功-正常
     */
    @PostMapping("/account/import")
    public Map<String, Object> importAccountCsv(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        if (file == null || file.isEmpty()) {
            result.put("success", false);
            result.put("message", "请上传CSV文件");
            return result;
        }
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> lines = readCsvLinesWithAutoCharset(file);
            if (lines.isEmpty()) {
                result.put("success", false);
                result.put("message", "文件内容为空");
                return result;
            }
            int lineIdx = 0;
            String headerLine = lines.get(lineIdx++);
            if (headerLine == null) {
                result.put("success", false);
                result.put("message", "文件内容为空");
                return result;
            }
            // 去除 BOM
            headerLine = headerLine.replace("\uFEFF", "").trim();
            List<String> headerList = parseCsvLine(headerLine);
            String[] headers = headerList.toArray(new String[0]);
            for (int i = 0; i < headers.length; i++) {
                headers[i] = normalizeAccountImportHeader(headers[i]);
            }
            Set<String> headerSet = new HashSet<>(Arrays.asList(headers));
            boolean firstLineIsData = false;
            if (!headerSet.contains("username")) {
                // 兼容“列表导出CSV”场景：列通常为 注册日期,状态,账号,...
                if (headers.length >= 3 && isLikelyAccountListHeader(headerList)) {
                    headers[2] = "username";
                    headerSet = new HashSet<>(Arrays.asList(headers));
                } else if (headers.length >= 3) {
                    // 兜底：把首行当成数据，使用位置映射（第3列=username）
                    headers = buildPositionalAccountImportHeaders(headers.length);
                    headerSet = new HashSet<>(Arrays.asList(headers));
                    // 只有首行明显是“数据行”时，才把首行计入数据，避免把表头误导入
                    firstLineIsData = !isLikelyHeaderRow(headerList);
                }
                if (!headerSet.contains("username")) {
                    result.put("success", false);
                    result.put("message", "CSV缺少必填列：username（可用表头：username/账号/用户名）");
                    return result;
                }
            }

            if (firstLineIsData) {
                Map<String, Object> firstRow = new HashMap<>();
                for (int i = 0; i < headers.length && i < headerList.size(); i++) {
                    firstRow.put(headers[i], headerList.get(i).trim());
                }
                if (!isHeaderLikeDataRow(firstRow)) {
                    rows.add(firstRow);
                }
            }

            while (lineIdx < lines.size()) {
                String line = lines.get(lineIdx++);
                line = line.trim();
                if (line.isEmpty()) continue;
                List<String> values = parseCsvLine(line);
                if (values.size() > headers.length) {
                    log.warn("账号导入第{}行字段数超出标题列数，超出字段将忽略", rows.size() + 2);
                }
                Map<String, Object> row = new HashMap<>();
                for (int i = 0; i < headers.length && i < values.size(); i++) {
                    row.put(headers[i], values.get(i).trim());
                }
                if (isHeaderLikeDataRow(row)) {
                    continue;
                }
                rows.add(row);
            }
            log.info("账号导入：解析到 {} 行", rows.size());
            return ttRegisterService.batchUpsertAccounts(rows);
        } catch (Exception e) {
            log.error("账号导入CSV解析失败", e);
            result.put("success", false);
            result.put("message", "文件解析失败：" + e.getMessage());
            return result;
        }
    }

    /**
     * 简易CSV解析：支持双引号包裹字段、字段内逗号、双引号转义("")
     */
    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        if (line == null) {
            return values;
        }
        // 兼容 Excel 导出的“制表符分隔”文本
        char delimiter = detectDelimiter(line);
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }
            if (c == delimiter && !inQuotes) {
                values.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        values.add(cur.toString());
        return values;
    }

    private List<String> readCsvLinesWithAutoCharset(MultipartFile file) throws Exception {
        byte[] bytes = file.getBytes();
        List<String> utf8Lines = readLines(bytes, StandardCharsets.UTF_8);
        if (!utf8Lines.isEmpty() && looksMojibake(utf8Lines.get(0))) {
            return readLines(bytes, Charset.forName("GB18030"));
        }
        return utf8Lines;
    }

    private List<String> readLines(byte[] bytes, Charset charset) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private boolean looksMojibake(String text) {
        if (text == null || text.isEmpty()) return false;
        // 常见 UTF-8/GBK 错配时会出现替代字符 �
        return text.contains("�");
    }

    private char detectDelimiter(String line) {
        if (line == null || line.isEmpty()) return ',';
        int comma = 0, tab = 0;
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (!inQuotes) {
                if (c == ',') comma++;
                if (c == '\t') tab++;
            }
        }
        return tab > comma ? '\t' : ',';
    }

    private String normalizeAccountImportHeader(String rawHeader) {
        if (rawHeader == null) return "";
        String header = rawHeader.replace("\uFEFF", "").replace("\u200B", "").trim().toLowerCase();
        if ("账号".equals(header) || "用户名".equals(header) || "user".equals(header) || "username".equals(header)) return "username";
        if ("账号类别".equals(header) || "accounttype".equals(header) || "account_type".equals(header)) return "account_type";
        if ("邮箱".equals(header) || "email".equals(header)) return "email";
        if ("密码".equals(header) || "pass".equals(header) || "pwd".equals(header) || "password".equals(header)) return "password";
        if ("状态".equals(header) || "status".equals(header)) return "status";
        if ("备注".equals(header) || "note".equals(header)) return "note";
        if ("国家".equals(header) || "country".equals(header)) return "country";
        if ("换绑邮箱".equals(header) || "new_email".equals(header) || "newemail".equals(header)) return "new_email";
        return header;
    }

    private boolean isHeaderLikeDataRow(Map<String, Object> row) {
        if (row == null || row.isEmpty()) return false;
        String username = asLowerTrim(row.get("username"));
        String status = asLowerTrim(row.get("status"));
        String note = asLowerTrim(row.get("note"));
        if ("username".equals(username) || "账号".equals(username) || "用户名".equals(username) || "account".equals(username)) {
            return true;
        }
        if ("status".equals(status) || "状态".equals(status)) {
            return true;
        }
        if ("note".equals(note) || "备注".equals(note)) {
            return true;
        }
        return false;
    }

    private String asLowerTrim(Object v) {
        if (v == null) return "";
        return String.valueOf(v).replace("\uFEFF", "").replace("\u200B", "").trim().toLowerCase();
    }

    private boolean isLikelyAccountListHeader(List<String> rawHeaders) {
        if (rawHeaders == null || rawHeaders.size() < 3) return false;
        String h0 = rawHeaders.get(0) == null ? "" : rawHeaders.get(0).replace("\uFEFF", "").trim();
        String h1 = rawHeaders.get(1) == null ? "" : rawHeaders.get(1).replace("\uFEFF", "").trim();
        String h2 = rawHeaders.get(2) == null ? "" : rawHeaders.get(2).replace("\uFEFF", "").trim();
        boolean thirdIsAccount = "账号".equals(h2) || "用户名".equals(h2) || "username".equalsIgnoreCase(h2) || "user".equalsIgnoreCase(h2);
        boolean firstTwoLooksLikeList = "注册日期".equals(h0) || "createdat".equalsIgnoreCase(h0) || "状态".equals(h1) || "status".equalsIgnoreCase(h1);
        return thirdIsAccount || firstTwoLooksLikeList;
    }

    private boolean isLikelyHeaderRow(List<String> cells) {
        if (cells == null || cells.isEmpty()) return false;
        int headerHit = 0;
        for (String cell : cells) {
            String h = normalizeAccountImportHeader(cell);
            if ("username".equals(h)
                    || "email".equals(h)
                    || "password".equals(h)
                    || "status".equals(h)
                    || "note".equals(h)
                    || "country".equals(h)
                    || "new_email".equals(h)) {
                headerHit++;
            }
            String raw = cell == null ? "" : cell.replace("\uFEFF", "").replace("\u200B", "").trim();
            if ("注册日期".equals(raw) || "账号类别".equals(raw) || "安卓版本".equals(raw) || "换绑状态".equals(raw)) {
                headerHit++;
            }
        }
        return headerHit >= 2;
    }

    private String[] buildPositionalAccountImportHeaders(int size) {
        String[] headers = new String[size];
        for (int i = 0; i < size; i++) {
            headers[i] = "col_" + i;
        }
        // 默认兼容账号列表导出列顺序：第2列状态，第3列账号，第10列备注
        if (size > 1) headers[1] = "status";
        if (size > 2) headers[2] = "username";
        if (size > 9) headers[9] = "note";
        return headers;
    }

    /**
     * 更新任务配置（任务小窝中编辑）
     */
    @PostMapping("/task/update")
    public Map<String, Object> updateTaskConfig(@RequestBody Map<String, Object> request) {
        log.info("更新任务配置请求: {}", request);
        return ttRegisterService.updateTaskConfig(request);
    }

    @PostMapping("/task/create")
    public Map<String, Object> createTask(@RequestBody Map<String, Object> request) {
        log.info("手工新增任务请求: {}", request);
        return ttRegisterService.createTask(request);
    }

    /**
     * 立即刷新任务配置缓存，使正在运行的任务下一循环即可读到最新配置。
     * 直接改库后调此接口可跳过 5 分钟 TTL 立即生效。
     */
    @PostMapping("/task/config/refresh/{taskId}")
    public Map<String, Object> refreshTaskConfig(@PathVariable String taskId) {
        log.info("手动刷新任务配置缓存，taskId: {}", taskId);
        ttRegisterService.refreshTaskConfig(taskId);
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("message", "配置缓存已刷新，下一循环生效");
        res.put("taskId", taskId);
        return res;
    }

    /**
     * 恢复任务，将状态改回 PENDING
     */
    @PostMapping("/task/resume/{taskId}")
    public Map<String, Object> resumeTask(@PathVariable String taskId) {
        log.info("恢复任务请求，taskId: {}", taskId);
        return ttRegisterService.resumeTask(taskId);
    }
    
    /**
     * 多设备并行注册Outlook邮箱账号（多个设备同时注册，每个设备可注册多个账号）
     * 
     * 请求参数格式:
     * {
     *   "phoneIds": ["phone_id_1", "phone_id_2", "phone_id_3"],
     *   "serverIp": "10.7.107.224",
     *   "maxConcurrency": 10,
     *   "targetCountPerDevice": 1,
     *   "resetParams": {
     *     "country": "BR",
     *     "sdk": "33",
     *     "imagePath": "uhub.service.ucloud.cn/phone/android13_cpu:20251120",
     *     "gaidTag": "20250410",
     *     "dynamicIpChannel": "closeli",
     *     "staticIpChannel": "",
     *     "biz": ""
     *   }
     * }
     */
    @PostMapping("/outlook/parallel")
    public Map<String, Object> parallelRegisterOutlookAccounts(@RequestBody Map<String, Object> request) {
        log.info("接收到多设备并行注册Outlook账号请求，参数: {}", request);
        
        @SuppressWarnings("unchecked")
        List<String> phoneIds = (List<String>) request.get("phoneIds");
        String serverIp = (String) request.get("serverIp");
        Object maxConcurrencyObj = request.get("maxConcurrency");
        Object targetCountPerDeviceObj = request.get("targetCountPerDevice");
        
        if (phoneIds == null || phoneIds.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "phoneIds参数不能为空");
            return errorResponse;
        }
        
        if (serverIp == null || serverIp.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "serverIp参数不能为空");
            return errorResponse;
        }
        
        Integer maxConcurrency = null;
        if (maxConcurrencyObj != null) {
            if (maxConcurrencyObj instanceof Integer) {
                maxConcurrency = (Integer) maxConcurrencyObj;
            } else if (maxConcurrencyObj instanceof String) {
                try {
                    maxConcurrency = Integer.parseInt((String) maxConcurrencyObj);
                } catch (NumberFormatException e) {
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("message", "maxConcurrency参数格式错误，必须是数字");
                    return errorResponse;
                }
            }
        }
        
        if (maxConcurrency != null && (maxConcurrency <= 0 || maxConcurrency > 100)) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "maxConcurrency参数必须在1-100之间");
            return errorResponse;
        }
        
        Integer targetCountPerDevice = null;
        if (targetCountPerDeviceObj != null) {
            if (targetCountPerDeviceObj instanceof Integer) {
                targetCountPerDevice = (Integer) targetCountPerDeviceObj;
            } else if (targetCountPerDeviceObj instanceof String) {
                try {
                    targetCountPerDevice = Integer.parseInt((String) targetCountPerDeviceObj);
                } catch (NumberFormatException e) {
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("message", "targetCountPerDevice参数格式错误，必须是数字");
                    return errorResponse;
                }
            }
        }
        
        // 允许0（无限循环）或1-1000之间的值
        if (targetCountPerDevice != null && targetCountPerDevice != 0 && (targetCountPerDevice < 1 || targetCountPerDevice > 1000)) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "targetCountPerDevice参数必须是0（无限循环）或1-1000之间的数字");
            return errorResponse;
        }
        
        // 获取TikTok版本目录（必填）
        String tiktokVersionDir = (String) request.get("tiktokVersionDir");
        if (tiktokVersionDir == null || tiktokVersionDir.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "tiktokVersionDir参数不能为空");
            return errorResponse;
        }
        
        // 获取ResetPhoneEnv参数（可选）
        @SuppressWarnings("unchecked")
        Map<String, String> resetParams = (Map<String, String>) request.get("resetParams");
        
        return ttRegisterService.parallelRegisterOutlookAccounts(phoneIds, serverIp, maxConcurrency, targetCountPerDevice, tiktokVersionDir, resetParams);
    }
    
    /**
     * 查询任务列表（从数据库）
     * 
     * @param status 任务状态（可选，PENDING/RUNNING/COMPLETED/FAILED/STOPPED）
     * @param taskType 任务类型（可选，FAKE_EMAIL/REAL_EMAIL）
     * @param serverIp 服务器IP（可选）
     * @param phoneId 设备ID（可选）
     * @param page 页码（可选，默认1）
     * @param size 每页大小（可选，默认20）
     * @return 任务列表
     */
    @GetMapping("/task/list")
    public Map<String, Object> getTaskList(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String serverIp,
            @RequestParam(required = false) String phoneId,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        log.info("查询任务列表，status={}, taskType={}, serverIp={}, phoneId={}, page={}, size={}", 
                status, taskType, serverIp, phoneId, page, size);
        return ttRegisterService.getTaskList(status, taskType, serverIp, phoneId, page, size);
    }
    
    /**
     * 根据任务ID查询任务详情
     * 
     * @param taskId 任务ID
     * @return 任务详情
     */
    @GetMapping("/task/{taskId}")
    public Map<String, Object> getTaskById(@PathVariable String taskId) {
        log.info("查询任务详情，taskId: {}", taskId);
        return ttRegisterService.getTaskById(taskId);
    }
    
    /**
     * 停止任务（数据库任务）
     * 
     * @param taskId 任务ID
     * @return 停止结果
     */
    @PostMapping("/task/stop/{taskId}")
    public Map<String, Object> stopTaskById(@PathVariable String taskId) {
        log.info("停止任务请求，taskId: {}", taskId);
        return ttRegisterService.stopTaskById(taskId);
    }
    
    /**
     * 批量停止任务
     * 
     * @param request 请求参数，包含 taskIds 数组
     * @return 停止结果
     */
    @PostMapping("/task/stop/batch")
    public Map<String, Object> stopTasksBatch(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> taskIds = (List<String>) request.get("taskIds");
        log.info("批量停止任务请求，taskIds: {}", taskIds);
        return ttRegisterService.stopTasksBatch(taskIds);
    }
    
    /**
     * 删除任务
     * 
     * @param taskId 任务ID
     * @return 删除结果
     */
    @DeleteMapping("/task/{taskId}")
    public Map<String, Object> deleteTask(@PathVariable String taskId) {
        log.info("删除任务请求，taskId: {}", taskId);
        return ttRegisterService.deleteTask(taskId);
    }
    
    /**
     * 批量删除任务
     * 
     * @param request 请求参数，包含 taskIds 数组
     * @return 删除结果
     */
    @DeleteMapping("/task/batch")
    public Map<String, Object> deleteTasksBatch(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> taskIds = (List<String>) request.get("taskIds");
        log.info("批量删除任务请求，taskIds: {}", taskIds);
        return ttRegisterService.deleteTasksBatch(taskIds);
    }
    
    /**
     * 重置任务状态为 PENDING（用于重新执行失败的任务）
     * 
     * @param taskId 任务ID
     * @return 重置结果
     */
    @PostMapping("/task/reset/{taskId}")
    public Map<String, Object> resetTask(@PathVariable String taskId) {
        log.info("重置任务状态请求，taskId: {}", taskId);
        return ttRegisterService.resetTask(taskId);
    }
}

