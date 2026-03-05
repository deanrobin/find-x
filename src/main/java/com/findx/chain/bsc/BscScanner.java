package com.findx.chain.bsc;

import com.findx.chain.ChainScanner;
import com.findx.domain.entity.TokenBuyer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.http.HttpService;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * BSC 链扫描器
 * 通过 ERC-20 Transfer 事件识别买入行为：
 *   Transfer(from=DEX合约, to=用户地址, value>=阈值)
 */
@Slf4j
@Component
public class BscScanner implements ChainScanner {

    /** ERC-20 Transfer 事件签名 */
    private static final Event TRANSFER_EVENT = new Event("Transfer",
            Arrays.asList(
                    new TypeReference<Address>(true) {},   // from (indexed)
                    new TypeReference<Address>(true) {},   // to   (indexed)
                    new TypeReference<Uint256>(false) {}   // value
            ));

    private static final String TRANSFER_TOPIC =
            EventEncoder.encode(TRANSFER_EVENT);

    /** BSC 平均出块时间（秒） */
    private static final int BSC_BLOCK_TIME_SECONDS = 3;

    /** eth_getLogs 单次最大区块跨度（BSC 公共节点限制） */
    private static final int MAX_BLOCK_RANGE = 2000;

    @Value("${chain.bsc.rpc-url:https://bsc-dataseed1.binance.org/}")
    private String rpcUrl;

    private Web3j web3j;

    @PostConstruct
    public void init() {
        this.web3j = Web3j.build(new HttpService(rpcUrl));
        log.info("BSC Scanner 初始化完成, RPC: {}", rpcUrl);
    }

    @Override
    public String chainId() {
        return "BSC";
    }

    // ─── 时间 → 区块号 ─────────────────────────────────────────────

    @Override
    public long getBlockNumberByTime(LocalDateTime time) throws Exception {
        // 获取最新区块作为参考点，用平均出块时间倒推
        EthBlock latestBlock = web3j
                .ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false)
                .send();
        BigInteger latestNumber = latestBlock.getBlock().getNumber();
        long latestTs = latestBlock.getBlock().getTimestamp().longValue();

        long targetTs = time.atZone(ZoneId.of("Asia/Shanghai"))
                .toInstant().getEpochSecond();

        long diffSeconds = latestTs - targetTs;
        long diffBlocks  = diffSeconds / BSC_BLOCK_TIME_SECONDS;
        long targetBlock = latestNumber.longValue() - diffBlocks;

        log.debug("时间 {} → 估算区块 {} (最新区块 {})", time, targetBlock, latestNumber);
        return Math.max(targetBlock, 0);
    }

    // ─── 扫描买入记录 ──────────────────────────────────────────────

    @Override
    public List<TokenBuyer> scanBuyers(String tokenAddress,
                                        long fromBlock,
                                        long toBlock,
                                        BigDecimal minAmount,
                                        int decimals) throws Exception {
        log.info("[BSC] 扫描 token={} 区块 [{}, {}] minAmount={} decimals={}",
                tokenAddress, fromBlock, toBlock, minAmount, decimals);

        // minAmount 转换为原始 uint256 阈值
        BigDecimal factor    = BigDecimal.TEN.pow(decimals);
        BigInteger threshold = minAmount.multiply(factor).toBigInteger();

        List<TokenBuyer> result = new ArrayList<>();

        // 分批拉取（避免单次超限）
        for (long start = fromBlock; start <= toBlock; start += MAX_BLOCK_RANGE) {
            long end = Math.min(start + MAX_BLOCK_RANGE - 1, toBlock);
            List<TokenBuyer> batch = fetchBatch(tokenAddress, start, end,
                    threshold, decimals, factor);
            result.addAll(batch);
            log.debug("[BSC] 区块 [{},{}] 找到 {} 条", start, end, batch.size());
        }

        log.info("[BSC] 扫描完成，共找到 {} 条买入记录", result.size());
        return result;
    }

    private List<TokenBuyer> fetchBatch(String tokenAddress,
                                         long fromBlock, long toBlock,
                                         BigInteger threshold,
                                         int decimals, BigDecimal factor) throws Exception {
        EthFilter filter = new EthFilter(
                DefaultBlockParameter.valueOf(BigInteger.valueOf(fromBlock)),
                DefaultBlockParameter.valueOf(BigInteger.valueOf(toBlock)),
                tokenAddress
        ).addSingleTopic(TRANSFER_TOPIC);

        EthLog ethLog = web3j.ethGetLogs(filter).send();
        if (ethLog.hasError()) {
            log.warn("[BSC] getLogs 错误: {}", ethLog.getError().getMessage());
            return Collections.emptyList();
        }

        List<TokenBuyer> buyers = new ArrayList<>();
        for (EthLog.LogResult<?> logResult : ethLog.getLogs()) {
            EthLog.LogObject log0 = (EthLog.LogObject) logResult.get();
            try {
                TokenBuyer buyer = parseTransferLog(log0, tokenAddress,
                        threshold, decimals, factor);
                if (buyer != null) {
                    buyers.add(buyer);
                }
            } catch (Exception e) {
                log.warn("[BSC] 解析日志失败 txHash={}: {}", log0.getTransactionHash(), e.getMessage());
            }
        }
        return buyers;
    }

    /**
     * 解析一条 Transfer 日志
     * 判断为"买入"的条件：
     *   1. value >= threshold
     *   2. from 是合约地址（DEX pair），to 是 EOA（普通钱包）
     *      — 简化处理：只过 value 阈值，DEX 买入判断留给后续扩展
     */
    private TokenBuyer parseTransferLog(EthLog.LogObject log0,
                                         String tokenAddress,
                                         BigInteger threshold,
                                         int decimals,
                                         BigDecimal factor) throws Exception {
        List<String> topics = log0.getTopics();
        if (topics.size() < 3) return null;

        // 解析 from / to（topics[1] / topics[2]，32字节补齐，取后20字节）
        String from = "0x" + topics.get(1).substring(26);
        String to   = "0x" + topics.get(2).substring(26);

        // 解析 value（data 字段）
        BigInteger value = new BigInteger(
                log0.getData().substring(2), 16);

        // 过滤：数量不足
        if (value.compareTo(threshold) < 0) return null;

        // 过滤：from == to（自转账）或 to 是零地址（铸造/销毁）
        if (from.equalsIgnoreCase(to)) return null;
        if (to.equalsIgnoreCase("0x0000000000000000000000000000000000000000")) return null;

        // 获取区块时间
        EthBlock block = web3j
                .ethGetBlockByNumber(
                        DefaultBlockParameter.valueOf(log0.getBlockNumber()), false)
                .send();
        long ts = block.getBlock().getTimestamp().longValue();
        LocalDateTime blockTime = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(ts), ZoneId.of("Asia/Shanghai"));

        BigDecimal readableAmount = new BigDecimal(value).divide(factor);

        return TokenBuyer.builder()
                .chainId("BSC")
                .tokenAddress(tokenAddress.toLowerCase())
                .buyerAddress(to.toLowerCase())
                .amount(readableAmount)
                .txHash(log0.getTransactionHash())
                .blockNumber(log0.getBlockNumber().longValue())
                .blockTime(blockTime)
                .build();
    }

    // ─── 代币精度 ──────────────────────────────────────────────────

    @Override
    public int getTokenDecimals(String tokenAddress) throws Exception {
        Function function = new Function(
                "decimals",
                Collections.emptyList(),
                Collections.singletonList(new TypeReference<Uint8>() {}));
        String encoded = FunctionEncoder.encode(function);

        org.web3j.protocol.core.methods.request.Transaction tx =
                org.web3j.protocol.core.methods.request.Transaction
                        .createEthCallTransaction(null, tokenAddress, encoded);

        String result = web3j.ethCall(tx, DefaultBlockParameterName.LATEST)
                .send().getValue();

        if (result == null || result.equals("0x")) return 18;

        List<?> decoded = FunctionReturnDecoder.decode(result,
                function.getOutputParameters());
        if (decoded.isEmpty()) return 18;

        return ((Uint8) decoded.get(0)).getValue().intValue();
    }
}
