package com.cpa.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cpa.entity.TtAccountData;
import com.cpa.entity.TtAccountDataOutlook;
import com.cpa.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    /**
     * 获取设备池列表（分页）
     */
    @GetMapping("/pool")
    public Map<String, Object> getDevicePool(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String pkgName,
            @RequestParam(required = false) Integer status) {
        
        Page<TtAccountDataOutlook> page = new Page<>(pageNum, pageSize);
        Page<TtAccountDataOutlook> result = deviceService.getDevicePool(page, country, pkgName, status);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", result.getRecords());
        response.put("total", result.getTotal());
        response.put("current", result.getCurrent());
        response.put("size", result.getSize());
        response.put("pages", result.getPages());
        
        return response;
    }

    /**
     * 获取账号库列表（分页）
     */
    @GetMapping("/accounts")
    public Map<String, Object> getAccountLibrary(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String pkgName,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer nurtureStatus) {
        
        Page<TtAccountData> page = new Page<>(pageNum, pageSize);
        Page<TtAccountData> result = deviceService.getAccountLibrary(page, country, pkgName, status, nurtureStatus);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", result.getRecords());
        response.put("total", result.getTotal());
        response.put("current", result.getCurrent());
        response.put("size", result.getSize());
        response.put("pages", result.getPages());
        
        return response;
    }

    /**
     * 获取需要注册的设备列表
     */
    @GetMapping("/need-register")
    public Map<String, Object> getDevicesNeedRegister() {
        List<TtAccountDataOutlook> devices = deviceService.getDevicesNeedRegister();
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", devices);
        response.put("count", devices.size());
        
        return response;
    }

    /**
     * 获取需要养号的账号列表
     */
    @GetMapping("/need-nurture")
    public Map<String, Object> getAccountsNeedNurture() {
        List<TtAccountData> accounts = deviceService.getAccountsNeedNurture();
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", accounts);
        response.put("count", accounts.size());
        
        return response;
    }

    /**
     * 获取养号完成的账号列表
     */
    @GetMapping("/nurtured")
    public Map<String, Object> getNurturedAccounts() {
        List<TtAccountData> accounts = deviceService.getNurturedAccounts();
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", accounts);
        response.put("count", accounts.size());
        
        return response;
    }

    /**
     * 添加设备到设备池
     */
    @PostMapping("/pool")
    public Map<String, Object> addDeviceToPool(@RequestBody TtAccountDataOutlook device) {
        boolean success = deviceService.addDeviceToPool(device);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "添加成功" : "添加失败");
        
        return response;
    }

    /**
     * 批量更新设备状态
     */
    @PostMapping("/batch-status")
    public Map<String, Object> batchUpdateDeviceStatus(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) request.get("ids");
        Integer status = (Integer) request.get("status");
        String tableType = (String) request.get("tableType");
        
        boolean success = deviceService.batchUpdateDeviceStatus(ids, status, tableType);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "更新成功" : "更新失败");
        
        return response;
    }

    /**
     * 批量更新刷视频天数
     */
    @PostMapping("/batch-video-days")
    public Map<String, Object> batchUpdateVideoDays(@RequestBody Map<String, Object> request) {
        Integer limit = (Integer) request.get("limit");
        if (limit == null) limit = 100;
        
        boolean success = deviceService.batchUpdateVideoDays(limit);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "更新成功" : "更新失败");
        
        return response;
    }

    /**
     * 批量更新养号状态
     */
    @PostMapping("/batch-nurture-status")
    public Map<String, Object> batchUpdateNurtureStatus(@RequestBody Map<String, Object> request) {
        Integer days = (Integer) request.get("days");
        if (days == null) days = 7;
        
        boolean success = deviceService.batchUpdateNurtureStatus(days);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "更新成功" : "更新失败");
        
        return response;
    }

    /**
     * 根据ID获取设备信息
     */
    @GetMapping("/pool/{id}")
    public Map<String, Object> getDeviceById(@PathVariable Long id) {
        TtAccountDataOutlook device = deviceService.getDeviceById(id);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", device);
        
        return response;
    }

    /**
     * 根据ID获取账号信息
     */
    @GetMapping("/accounts/{id}")
    public Map<String, Object> getAccountById(@PathVariable Long id) {
        TtAccountData account = deviceService.getAccountById(id);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", account);
        
        return response;
    }

    /**
     * 更新设备信息
     */
    @PutMapping("/pool/{id}")
    public Map<String, Object> updateDevice(@PathVariable Long id, @RequestBody TtAccountDataOutlook device) {
        device.setId(id);
        boolean success = deviceService.updateDevice(device);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "更新成功" : "更新失败");
        
        return response;
    }

    /**
     * 更新账号信息
     */
    @PutMapping("/accounts/{id}")
    public Map<String, Object> updateAccount(@PathVariable Long id, @RequestBody TtAccountData account) {
        account.setId(id);
        boolean success = deviceService.updateAccount(account);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "更新成功" : "更新失败");
        
        return response;
    }
}
