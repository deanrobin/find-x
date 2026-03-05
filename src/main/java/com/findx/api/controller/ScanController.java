package com.findx.api.controller;

import com.findx.domain.dto.ScanRequest;
import com.findx.domain.dto.ScanResult;
import com.findx.service.ScanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * POST /api/scan
 * {
 *   "chainId":       "BSC",            // 可选，默认 BSC
 *   "tokenAddress":  "0x...",          // 必填
 *   "endTime":       "2024-03-05T15:00:00", // 可选，默认当前时间
 *   "minAmount":     100.5             // 必填，最小买入量
 * }
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ScanController {

    private final ScanService scanService;

    @PostMapping("/scan")
    public ResponseEntity<?> scan(@Valid @RequestBody ScanRequest request) {
        try {
            log.info("收到扫描请求: chain={} token={} minAmount={}",
                    request.getChainId(), request.getTokenAddress(), request.getMinAmount());
            ScanResult result = scanService.scan(request);
            return ResponseEntity.ok(Map.of(
                    "code", 0,
                    "msg",  "success",
                    "data", result
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 400,
                    "msg", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("扫描失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "code", 500,
                    "msg", "扫描失败: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
