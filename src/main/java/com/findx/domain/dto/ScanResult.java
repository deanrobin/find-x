package com.findx.domain.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 扫描结果摘要
 */
@Data
@Builder
public class ScanResult {

    private String chainId;
    private String tokenAddress;
    private long   fromBlock;
    private long   toBlock;
    private String fromTime;
    private String toTime;
    private int    totalFound;
    private List<BuyerRecord> records;

    @Data
    @Builder
    public static class BuyerRecord {
        private String buyerAddress;
        private String amount;
        private String txHash;
        private long   blockNumber;
        private String blockTime;
    }
}
