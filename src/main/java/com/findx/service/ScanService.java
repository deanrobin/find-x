package com.findx.service;

import com.findx.domain.dto.ScanRequest;
import com.findx.domain.dto.ScanResult;

public interface ScanService {

    /**
     * 执行扫描：按参数扫链、过滤买入记录、持久化并返回摘要
     */
    ScanResult scan(ScanRequest request) throws Exception;
}
