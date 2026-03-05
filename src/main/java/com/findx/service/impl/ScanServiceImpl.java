package com.findx.service.impl;

import com.findx.chain.ChainScanner;
import com.findx.chain.ChainScannerRegistry;
import com.findx.domain.dto.ScanRequest;
import com.findx.domain.dto.ScanResult;
import com.findx.domain.entity.TokenBuyer;
import com.findx.repository.TokenBuyerRepository;
import com.findx.service.ScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScanServiceImpl implements ScanService {

    private static final long HOURS_48 = 48L;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ChainScannerRegistry registry;
    private final TokenBuyerRepository repository;

    @Override
    @Transactional
    public ScanResult scan(ScanRequest request) throws Exception {
        // 1. 确定链 & 扫描器
        ChainScanner scanner = registry.get(request.getChainId());

        // 2. 计算时间范围
        LocalDateTime endTime   = request.getEndTime() != null
                ? request.getEndTime() : LocalDateTime.now();
        LocalDateTime startTime = endTime.minusHours(HOURS_48);

        log.info("[{}] 开始扫描 token={} 时间范围: {} ~ {}",
                scanner.chainId(), request.getTokenAddress(),
                startTime.format(FMT), endTime.format(FMT));

        // 3. 时间 → 区块号
        long toBlock   = scanner.getBlockNumberByTime(endTime);
        long fromBlock = scanner.getBlockNumberByTime(startTime);
        log.info("[{}] 区块范围: {} ~ {}", scanner.chainId(), fromBlock, toBlock);

        // 4. 获取代币精度
        int decimals = scanner.getTokenDecimals(request.getTokenAddress());
        log.info("[{}] token decimals={}", scanner.chainId(), decimals);

        // 5. 扫描链上数据
        List<TokenBuyer> buyers = scanner.scanBuyers(
                request.getTokenAddress().toLowerCase(),
                fromBlock, toBlock,
                request.getMinAmount(),
                decimals
        );

        // 6. 去重后批量保存（txHash 唯一索引防重）
        List<TokenBuyer> toSave = buyers.stream()
                .filter(b -> !repository.existsByTxHash(b.getTxHash()))
                .collect(Collectors.toList());

        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
            log.info("[{}] 保存 {} 条新记录（跳过 {} 条重复）",
                    scanner.chainId(), toSave.size(), buyers.size() - toSave.size());
        }

        // 7. 组装返回结果
        List<ScanResult.BuyerRecord> records = buyers.stream()
                .map(b -> ScanResult.BuyerRecord.builder()
                        .buyerAddress(b.getBuyerAddress())
                        .amount(b.getAmount().toPlainString())
                        .txHash(b.getTxHash())
                        .blockNumber(b.getBlockNumber())
                        .blockTime(b.getBlockTime().format(FMT))
                        .build())
                .collect(Collectors.toList());

        return ScanResult.builder()
                .chainId(scanner.chainId())
                .tokenAddress(request.getTokenAddress().toLowerCase())
                .fromBlock(fromBlock)
                .toBlock(toBlock)
                .fromTime(startTime.format(FMT))
                .toTime(endTime.format(FMT))
                .totalFound(records.size())
                .records(records)
                .build();
    }
}
