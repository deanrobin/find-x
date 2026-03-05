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
 *
 * 数据源策略（按优先级）：
 *   1. BSCScan API    — 当 bscscan.api-key 配置时启用（支持任意历史区块）
 *   2. Web3j RPC      — 默认，适合近 24-48h 数据（公共节点不支持历史存档）
 *
 * 注：查询 2周+ 以上历史数据推荐配置 bscscan.api-key 或私有归档节点。
 */
@Slf4j
@Component
public class BscScanner implements ChainScanner {

    // ─── ERC-20 Transfer 事件 ──────────────────────────────────────
    private static final Event TRANSFER_EVENT = new Event("Transfer",
            Arrays.asList(
                    new TypeReference<Address>(true) {},
                    new TypeReference<Address>(true) {},
                    new TypeReference<Uint256>(false) {}
            ));
    private static final String TRANSFER_TOPIC = EventEncoder.encode(TRANSFER_EVENT);

    /** BSC 出块时间（秒） */
    private static final int BSC_BLOCK_TIME_SECONDS = 3;

    /**
     * 每次 eth_getLogs 最大区块跨度
     * 公共节点一般限制 500~2000，保守取 500
     */
    private static final int MAX_BLOCK_RANGE_WEB3J = 500;

    /** BSCScan API 每次最多返回 10000 条，分页 offset 最大 10000 */
    private static final int BSCSCAN_PAGE_SIZE = 1000;

    /** 每批请求之间的间隔（ms），避免触发限速 */
    private static final int BATCH_DELAY_MS = 300;

    /** 429 重试最大次数 */
    private static final int MAX_RETRY = 5;

    @Value("${chain.bsc.rpc-url:https://bsc-dataseed1.binance.org/}")
    private String rpcUrl;

    /**
     * BSCScan API Key（可选）
     * 配置后自动切换为 BSCScan 数据源，支持任意历史区块
     * 申请地址：https://bscscan.com/myapikey
     */
    @Value("${chain.bsc.bscscan-api-key:}")
    private String bscscanApiKey;

    private Web3j web3j;

    @PostConstruct
    public void init() {
        this.web3j = Web3j.build(new HttpService(rpcUrl));
        String source = hasBscscanKey() ? "BSCScan API + Web3j" : "Web3j RPC only";
        log.info("BSC Scanner 初始化完成 | RPC: {} | 数据源: {}", rpcUrl, source);
        if (!hasBscscanKey()) {
            log.warn("未配置 chain.bsc.bscscan-api-key，仅支持近期数据（约 24-48h）。" +
                     "历史存档查询请配置 BSCScan API Key");
        }
    }

    @Override
    public String chainId() { return "BSC"; }

    // ─── 时间 → 区块号 ─────────────────────────────────────────────

    @Override
    public long getBlockNumberByTime(LocalDateTime time) throws Exception {
        EthBlock latest = web3j
                .ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false)
                .send();
        long latestNum = latest.getBlock().getNumber().longValue();
        long latestTs  = latest.getBlock().getTimestamp().longValue();
        long targetTs  = time.atZone(ZoneId.of("Asia/Shanghai")).toInstant().getEpochSecond();

        long diffBlocks = (latestTs - targetTs) / BSC_BLOCK_TIME_SECONDS;
        long targetBlock = latestNum - diffBlocks;
        log.debug("时间 {} → 估算区块 {} (最新区块 {})", time, targetBlock, latestNum);
        return Math.max(targetBlock, 1);
    }

    // ─── 扫描入口 ──────────────────────────────────────────────────

    @Override
    public List<TokenBuyer> scanBuyers(String tokenAddress,
                                        long fromBlock,
                                        long toBlock,
                                        BigDecimal minAmount,
                                        int decimals) throws Exception {
        if (hasBscscanKey()) {
            log.info("[BSC] 使用 BSCScan API 扫描 (支持历史存档)");
            return scanViaBscscan(tokenAddress, fromBlock, toBlock, minAmount, decimals);
        } else {
            log.info("[BSC] 使用 Web3j RPC 扫描 (近期数据)");
            return scanViaWeb3j(tokenAddress, fromBlock, toBlock, minAmount, decimals);
        }
    }

    // ─── 方式 1：BSCScan API ───────────────────────────────────────

    private List<TokenBuyer> scanViaBscscan(String tokenAddress,
                                             long fromBlock, long toBlock,
                                             BigDecimal minAmount, int decimals) throws Exception {
        BigDecimal factor    = BigDecimal.TEN.pow(decimals);
        BigInteger threshold = minAmount.multiply(factor).toBigInteger();

        List<TokenBuyer> result = new ArrayList<>();
        int page = 1;

        while (true) {
            String url = String.format(
                "https://api.bscscan.com/api?module=account&action=tokentx" +
                "&contractaddress=%s&startblock=%d&endblock=%d" +
                "&sort=asc&page=%d&offset=%d&apikey=%s",
                tokenAddress, fromBlock, toBlock, page, BSCSCAN_PAGE_SIZE, bscscanApiKey);

            String resp = httpGet(url);
            org.json.JSONObject json = new org.json.JSONObject(resp);

            if (!"1".equals(json.optString("status"))) {
                String msg = json.optString("message", "");
                if ("No transactions found".equals(msg)) break;
                log.warn("[BSC][BSCScan] 第{}页异常: {}", page, msg);
                break;
            }

            org.json.JSONArray txList = json.getJSONArray("result");
            log.debug("[BSC][BSCScan] 第{}页 {} 条", page, txList.length());

            boolean hasMore = false;
            for (int i = 0; i < txList.length(); i++) {
                org.json.JSONObject tx = txList.getJSONObject(i);
                BigInteger value = new BigInteger(tx.getString("value"));
                if (value.compareTo(threshold) < 0) continue;

                String to = tx.getString("to").toLowerCase();
                String from = tx.getString("from").toLowerCase();
                if (to.equals(from)) continue;
                if (to.equals("0x0000000000000000000000000000000000000000")) continue;

                BigDecimal readable = new BigDecimal(value).divide(factor);
                long ts = Long.parseLong(tx.getString("timeStamp"));
                LocalDateTime blockTime = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(ts), ZoneId.of("Asia/Shanghai"));

                result.add(TokenBuyer.builder()
                        .chainId("BSC")
                        .tokenAddress(tokenAddress)
                        .buyerAddress(to)
                        .amount(readable)
                        .txHash(tx.getString("hash"))
                        .blockNumber(Long.parseLong(tx.getString("blockNumber")))
                        .blockTime(blockTime)
                        .build());
                hasMore = true;
            }

            if (txList.length() < BSCSCAN_PAGE_SIZE) break;
            if (!hasMore) break;
            page++;
            Thread.sleep(200); // BSCScan 限速
        }

        log.info("[BSC][BSCScan] 共找到 {} 条符合记录", result.size());
        return result;
    }

    // ─── 方式 2：Web3j eth_getLogs ─────────────────────────────────

    private List<TokenBuyer> scanViaWeb3j(String tokenAddress,
                                           long fromBlock, long toBlock,
                                           BigDecimal minAmount, int decimals) throws Exception {
        BigDecimal factor    = BigDecimal.TEN.pow(decimals);
        BigInteger threshold = minAmount.multiply(factor).toBigInteger();
        List<TokenBuyer> result = new ArrayList<>();

        for (long start = fromBlock; start <= toBlock; start += MAX_BLOCK_RANGE_WEB3J) {
            long end = Math.min(start + MAX_BLOCK_RANGE_WEB3J - 1, toBlock);
            int retry = 0;
            while (retry <= MAX_RETRY) {
                try {
                    List<TokenBuyer> batch = fetchBatchViaWeb3j(
                            tokenAddress, start, end, threshold, decimals, factor);
                    result.addAll(batch);
                    if (!batch.isEmpty()) {
                        log.debug("[BSC][Web3j] 区块 [{},{}] 找到 {} 条", start, end, batch.size());
                    }
                    Thread.sleep(BATCH_DELAY_MS);
                    break;
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    if (msg.contains("429") && retry < MAX_RETRY) {
                        long wait = 1000L * (1 << retry); // 指数退避: 1s, 2s, 4s...
                        log.warn("[BSC][Web3j] 区块 [{},{}] 限速，{}ms 后重试 ({}/{})",
                                start, end, wait, retry + 1, MAX_RETRY);
                        Thread.sleep(wait);
                        retry++;
                    } else {
                        log.warn("[BSC][Web3j] 区块 [{},{}] 失败: {}", start, end,
                                msg.length() > 80 ? msg.substring(0, 80) : msg);
                        break;
                    }
                }
            }
        }
        log.info("[BSC][Web3j] 共找到 {} 条符合记录", result.size());
        return result;
    }

    private List<TokenBuyer> fetchBatchViaWeb3j(String tokenAddress,
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
            throw new RuntimeException(ethLog.getError().getMessage());
        }

        List<TokenBuyer> buyers = new ArrayList<>();
        for (EthLog.LogResult<?> lr : ethLog.getLogs()) {
            EthLog.LogObject log0 = (EthLog.LogObject) lr.get();
            try {
                List<String> topics = log0.getTopics();
                if (topics.size() < 3) continue;

                String from = "0x" + topics.get(1).substring(26);
                String to   = "0x" + topics.get(2).substring(26);
                BigInteger value = new BigInteger(log0.getData().substring(2), 16);

                if (value.compareTo(threshold) < 0) continue;
                if (from.equalsIgnoreCase(to)) continue;
                if (to.equalsIgnoreCase("0x0000000000000000000000000000000000000000")) continue;

                EthBlock block = web3j.ethGetBlockByNumber(
                        DefaultBlockParameter.valueOf(log0.getBlockNumber()), false).send();
                long ts = block.getBlock().getTimestamp().longValue();
                LocalDateTime blockTime = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(ts), ZoneId.of("Asia/Shanghai"));

                buyers.add(TokenBuyer.builder()
                        .chainId("BSC")
                        .tokenAddress(tokenAddress.toLowerCase())
                        .buyerAddress(to.toLowerCase())
                        .amount(new BigDecimal(value).divide(factor))
                        .txHash(log0.getTransactionHash())
                        .blockNumber(log0.getBlockNumber().longValue())
                        .blockTime(blockTime)
                        .build());
            } catch (Exception e) {
                log.warn("[BSC][Web3j] 解析 log 失败: {}", e.getMessage());
            }
        }
        return buyers;
    }

    // ─── 代币精度 ──────────────────────────────────────────────────

    @Override
    public int getTokenDecimals(String tokenAddress) throws Exception {
        Function function = new Function("decimals",
                Collections.emptyList(),
                Collections.singletonList(new TypeReference<Uint8>() {}));
        String encoded = FunctionEncoder.encode(function);

        org.web3j.protocol.core.methods.request.Transaction tx =
                org.web3j.protocol.core.methods.request.Transaction
                        .createEthCallTransaction(null, tokenAddress, encoded);
        String result = web3j.ethCall(tx, DefaultBlockParameterName.LATEST).send().getValue();
        if (result == null || result.equals("0x")) return 18;

        List<?> decoded = FunctionReturnDecoder.decode(result, function.getOutputParameters());
        return decoded.isEmpty() ? 18 : ((Uint8) decoded.get(0)).getValue().intValue();
    }

    // ─── 工具方法 ──────────────────────────────────────────────────

    private boolean hasBscscanKey() {
        return bscscanApiKey != null && !bscscanApiKey.isBlank();
    }

    private String httpGet(String url) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        try (java.io.InputStream is = conn.getInputStream();
             java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream()) {
            byte[] tmp = new byte[4096];
            int n;
            while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
            return buf.toString("UTF-8");
        }
    }
}
