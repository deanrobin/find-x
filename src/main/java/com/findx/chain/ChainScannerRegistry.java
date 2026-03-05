package com.findx.chain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 链扫描器注册中心
 * 所有 ChainScanner Bean 自动注册，service 层按 chainId 查找
 */
@Slf4j
@Component
public class ChainScannerRegistry {

    private final Map<String, ChainScanner> scanners;

    public ChainScannerRegistry(List<ChainScanner> scannerList) {
        this.scanners = scannerList.stream()
                .collect(Collectors.toMap(
                        s -> s.chainId().toUpperCase(),
                        Function.identity()
                ));
        log.info("已注册链扫描器: {}", scanners.keySet());
    }

    /**
     * 按 chainId 获取对应扫描器
     *
     * @param chainId 链标识（大小写不敏感）
     * @return ChainScanner
     * @throws IllegalArgumentException 不支持的链
     */
    public ChainScanner get(String chainId) {
        ChainScanner scanner = scanners.get(chainId.toUpperCase());
        if (scanner == null) {
            throw new IllegalArgumentException(
                    "不支持的链: " + chainId + "，已支持: " + scanners.keySet());
        }
        return scanner;
    }

    public boolean supports(String chainId) {
        return scanners.containsKey(chainId.toUpperCase());
    }
}
