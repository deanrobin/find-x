package com.findx.chain;

import com.findx.domain.entity.TokenBuyer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 链扫描器抽象接口
 * 每条链实现此接口，service 层不感知具体链细节
 */
public interface ChainScanner {

    /**
     * 返回该扫描器支持的链标识（大写），如 "BSC" / "ETH" / "POLYGON"
     */
    String chainId();

    /**
     * 将时间戳转换为对应的区块号（取最近的区块）
     *
     * @param time 目标时间
     * @return 对应区块号
     */
    long getBlockNumberByTime(LocalDateTime time) throws Exception;

    /**
     * 扫描指定区块范围内，购买 token 数量 >= minAmount 的所有买入记录
     *
     * @param tokenAddress 代币合约地址
     * @param fromBlock    起始区块（含）
     * @param toBlock      结束区块（含）
     * @param minAmount    最小买入量（可读精度）
     * @param decimals     代币精度（用于单位换算）
     * @return 买入记录列表
     */
    List<TokenBuyer> scanBuyers(String tokenAddress,
                                 long fromBlock,
                                 long toBlock,
                                 BigDecimal minAmount,
                                 int decimals) throws Exception;

    /**
     * 获取代币精度（decimals），默认实现可返回 18
     *
     * @param tokenAddress 代币合约地址
     * @return decimals
     */
    default int getTokenDecimals(String tokenAddress) throws Exception {
        return 18;
    }
}
