package com.findx.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 扫描请求参数
 */
@Data
public class ScanRequest {

    /** 链标识，默认 BSC */
    private String chainId = "BSC";

    /** 代币合约地址 */
    @NotBlank(message = "tokenAddress 不能为空")
    private String tokenAddress;

    /**
     * 截止时间（往前推 48 小时的起点），不传则使用当前时间
     * 格式: yyyy-MM-ddTHH:mm:ss，如 2024-03-05T15:00:00
     */
    private LocalDateTime endTime;

    /**
     * 买入数量阈值（可读精度，如 100.5 表示 100.5 个代币）
     * 只记录买入量 >= 此值的地址
     */
    @NotNull(message = "minAmount 不能为空")
    @Positive(message = "minAmount 必须大于 0")
    private BigDecimal minAmount;
}
