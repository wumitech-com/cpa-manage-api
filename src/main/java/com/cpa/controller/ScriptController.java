package com.cpa.controller;

import com.cpa.service.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本执行控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/scripts")
@RequiredArgsConstructor
public class ScriptController {

    private final ScriptService scriptService;

    /**
     * 执行创建云手机脚本
     */
    @PostMapping("/create-phone")
    public Map<String, Object> executeCreatePhoneScript(@RequestBody Map<String, Object> params) {
        log.info("接收到创建云手机脚本请求，参数: {}", params);
        return scriptService.executeCreatePhoneScript(params);
    }

    /**
     * 批量执行注册脚本
     */
    @PostMapping("/register")
    public Map<String, Object> executeRegisterScript() {
        log.info("接收到批量注册脚本请求");
        return scriptService.executeRegisterScript();
    }

    /**
     * 查询注册脚本执行状态
     */
    @GetMapping("/register/status")
    public Map<String, Object> getRegisterScriptStatus() {
        log.info("查询注册脚本执行状态");
        return scriptService.getRegisterScriptStatus();
    }

    /**
     * 执行编辑Bio脚本
     */
    @PostMapping("/edit-bio")
    public Map<String, Object> executeEditBioScript(@RequestBody Map<String, Object> params) {
        log.info("接收到编辑Bio脚本请求，参数: {}", params);
        return scriptService.executeEditBioScript(params);
    }

    /**
     * 查询编辑Bio脚本执行状态
     */
    @GetMapping("/edit-bio/status")
    public Map<String, Object> getEditBioScriptStatus() {
        log.info("查询编辑Bio脚本执行状态");
        return scriptService.getEditBioScriptStatus();
    }

    /**
     * 批量执行关注脚本
     */
    @PostMapping("/follow")
    public Map<String, Object> executeFollowScript(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Long> accountIds = (List<Long>) request.get("accountIds");
        String targetUsername = (String) request.get("targetUsername");
        
        log.info("接收到批量关注脚本请求，账号数量: {}, 目标用户: {}", accountIds.size(), targetUsername);
        return scriptService.executeFollowScript(accountIds, targetUsername);
    }

    /**
     * 批量执行刷视频脚本
     */
    @PostMapping("/watch-video")
    public Map<String, Object> executeWatchVideoScript(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Long> accountIds = (List<Long>) request.get("accountIds");
        
        log.info("接收到批量刷视频脚本请求，账号数量: {}", accountIds.size());
        return scriptService.executeWatchVideoScript(accountIds);
    }

    /**
     * 批量执行脚本（通用接口）
     */
    @PostMapping("/batch-execute")
    public Map<String, Object> batchExecuteScript(@RequestBody Map<String, Object> request) {
        String scriptType = (String) request.get("scriptType");
        @SuppressWarnings("unchecked")
        List<Long> targetIds = (List<Long>) request.get("targetIds");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.get("params");
        
        log.info("接收到批量执行脚本请求，类型: {}, 目标数量: {}", scriptType, targetIds.size());
        return scriptService.batchExecuteScript(scriptType, targetIds, params);
    }

    /**
     * 获取脚本执行状态
     */
    @GetMapping("/status/{taskId}")
    public Map<String, Object> getScriptExecutionStatus(@PathVariable String taskId) {
        log.info("获取脚本执行状态，任务ID: {}", taskId);
        return scriptService.getScriptExecutionStatus(taskId);
    }

    /**
     * 获取支持的脚本类型
     */
    @GetMapping("/types")
    public Map<String, Object> getSupportedScriptTypes() {
        Map<String, Object> response = new HashMap<>();
        
        Map<String, String> scriptTypes = new HashMap<>();
        scriptTypes.put(ScriptService.SCRIPT_TYPE_CREATE_PHONE, "创建云手机");
        scriptTypes.put(ScriptService.SCRIPT_TYPE_REGISTER, "注册账号");
        scriptTypes.put(ScriptService.SCRIPT_TYPE_FOLLOW, "关注用户");
        scriptTypes.put(ScriptService.SCRIPT_TYPE_WATCH_VIDEO, "刷视频");
        scriptTypes.put(ScriptService.SCRIPT_TYPE_UPLOAD, "上传视频");
        
        response.put("success", true);
        response.put("data", scriptTypes);
        
        return response;
    }

    /**
     * 查询异步任务状态
     */
    @GetMapping("/async-status")
    public Map<String, Object> getAsyncTaskStatus(
            @RequestParam String host,
            @RequestParam String pid,
            @RequestParam String logFile) {
        
        log.info("查询异步任务状态，服务器: {}, 进程ID: {}", host, pid);
        return scriptService.getAsyncTaskStatus(host, pid, logFile);
    }

    /**
     * 测试SSH配置
     */
    @GetMapping("/test-ssh-config")
    public Map<String, Object> testSshConfig() {
        log.info("测试SSH配置");
        return scriptService.testSshConfig();
    }

    /**
     * 测试读取日志并解析云手机名称（使用配置的跳板机连接）
     * @param targetHost 目标服务器地址（可选，不传则使用配置文件中的默认服务器）
     * @param pkgName TikTok包名（必传）
     */
    @GetMapping("/test-parse-log")
    public Map<String, Object> testParseLog(
            @RequestParam(required = false) String targetHost,
            @RequestParam String logFile,
            @RequestParam String pkgName,
            @RequestParam(required = false, defaultValue = "0") Integer lines) {
        
        log.info("测试读取日志文件，服务器: {}, 日志: {}, 包名: {}, 行数: {}", targetHost, logFile, pkgName, lines);
        return scriptService.testParseLog(targetHost, logFile, pkgName, lines);
    }

    /**
     * 获取脚本参数模板
     */
    @GetMapping("/params-template/{scriptType}")
    public Map<String, Object> getScriptParamsTemplate(@PathVariable String scriptType) {
        Map<String, Object> response = new HashMap<>();
        
        Map<String, Object> template = new HashMap<>();
        
        switch (scriptType) {
            case ScriptService.SCRIPT_TYPE_CREATE_PHONE:
                template.put("country", "US");
                template.put("pkgName", "com.zhiliaoapp.musically");
                template.put("scriptPath", "/scripts/create_phone.py");
                break;
                
            case ScriptService.SCRIPT_TYPE_REGISTER:
                template.put("scriptPath", "/scripts/register_outlook_tt.py");
                template.put("timeout", 300);
                break;
                
            case ScriptService.SCRIPT_TYPE_FOLLOW:
                template.put("targetUsername", "");
                template.put("scriptPath", "/scripts/follow_user.py");
                template.put("timeout", 60);
                break;
                
            case ScriptService.SCRIPT_TYPE_WATCH_VIDEO:
                template.put("videoCount", 10);
                template.put("scriptPath", "/scripts/watch_video.py");
                template.put("timeout", 180);
                break;
                
            case ScriptService.SCRIPT_TYPE_UPLOAD:
                template.put("videoPath", "");
                template.put("caption", "");
                template.put("scriptPath", "/scripts/upload_video.py");
                template.put("timeout", 300);
                break;
                
            default:
                response.put("success", false);
                response.put("message", "不支持的脚本类型");
                return response;
        }
        
        response.put("success", true);
        response.put("data", template);
        
        return response;
    }
}
