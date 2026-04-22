package com.cpa.controller;

import com.cpa.entity.TtPhoneServer;
import com.cpa.service.TtPhoneServerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tt-phone-server")
@RequiredArgsConstructor
public class TtPhoneServerController {

    private final TtPhoneServerService service;

    @GetMapping("/list")
    public Map<String, Object> list(
            @RequestParam(required = false) String serverIp,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String usageScope,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(service.list(serverIp, status, usageScope, page, size));
    }

    @GetMapping("/enabled-list")
    public Map<String, Object> enabledList() {
        return ok(service.enabledList());
    }

    @GetMapping("/register-list")
    public Map<String, Object> registerList() {
        return ok(service.registerServerList());
    }

    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody TtPhoneServer body) {
        try {
            return ok(service.create(body));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @PostMapping("/batch-create")
    public Map<String, Object> batchCreate(@RequestBody List<TtPhoneServer> rows) {
        try {
            return ok(service.batchCreate(rows));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @PutMapping("/update/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody TtPhoneServer patch) {
        try {
            return ok(service.update(id, patch));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @PutMapping("/enable/{id}")
    public Map<String, Object> enable(@PathVariable Long id) {
        try {
            service.enable(id);
            return ok(null);
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @PutMapping("/disable/{id}")
    public Map<String, Object> disable(@PathVariable Long id) {
        try {
            return ok(service.disable(id));
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
