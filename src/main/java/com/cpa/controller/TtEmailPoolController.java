package com.cpa.controller;

import com.cpa.service.TtEmailPoolService;
import com.cpa.service.EmailPoolAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/tt-email-pool")
@RequiredArgsConstructor
public class TtEmailPoolController {

    private final TtEmailPoolService service;
    private final EmailPoolAlertService emailPoolAlertService;

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(required = false) String email,
                                    @RequestParam(required = false) String channel,
                                    @RequestParam(required = false) String usageStatus,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        try {
            return ok(service.list(email, channel, usageStatus, page, size));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @PostMapping("/import-txt")
    public Map<String, Object> importTxt(@RequestParam("file") MultipartFile file,
                                         @RequestParam(required = false) String channel) {
        try {
            return ok(service.importTxt(file, channel));
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    @PostMapping("/alert/check-now")
    public Map<String, Object> checkAlertNow() {
        try {
            return ok(emailPoolAlertService.manualCheckNow());
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
