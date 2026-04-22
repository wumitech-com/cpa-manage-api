package com.cpa.controller;

import com.cpa.entity.TtPhoneDevice;
import com.cpa.service.TtPhoneDeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/tt-phone-device")
@RequiredArgsConstructor
public class TtPhoneDeviceController {

    private final TtPhoneDeviceService service;

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(required = false) String serverIp,
                                    @RequestParam(required = false) String phoneId,
                                    @RequestParam(required = false) String deviceStatus,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return ok(service.list(serverIp, phoneId, deviceStatus, page, size));
    }

    @GetMapping("/register-list")
    public Map<String, Object> registerList(@RequestParam(required = false) String serverIp,
                                            @RequestParam(required = false) String phoneId,
                                            @RequestParam(required = false) String deviceStatus,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return ok(service.registerList(serverIp, phoneId, deviceStatus, page, size));
    }

    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody TtPhoneDevice body) {
        try {
            return ok(service.create(body));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @PostMapping("/batch-create")
    public Map<String, Object> batchCreate(@RequestBody java.util.List<TtPhoneDevice> rows) {
        try {
            return ok(service.batchCreate(rows));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @PostMapping("/batch-create-by-rule")
    public Map<String, Object> batchCreateByRule(@RequestBody Map<String, Object> request) {
        try {
            String phonePrefix = request.get("phonePrefix") == null ? null : String.valueOf(request.get("phonePrefix"));
            String serverIp = request.get("serverIp") == null ? null : String.valueOf(request.get("serverIp"));
            Integer count = null;
            if (request.get("count") instanceof Number) {
                count = ((Number) request.get("count")).intValue();
            } else if (request.get("count") != null) {
                count = Integer.parseInt(String.valueOf(request.get("count")));
            }
            String note = request.get("note") == null ? null : String.valueOf(request.get("note"));
            return ok(service.batchCreateByRule(phonePrefix, serverIp, count, note));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @PostMapping("/count-by-server")
    public Map<String, Object> countByServer(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            java.util.List<String> serverIps = (java.util.List<String>) request.get("serverIps");
            return ok(service.countByServerIps(serverIps));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @PutMapping("/update/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody TtPhoneDevice patch) {
        try {
            return ok(service.update(id, patch));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @DeleteMapping("/delete/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ok(null);
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    private Map<String, Object> ok(Object data) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("data", data);
        return r;
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("message", message);
        return r;
    }
}
