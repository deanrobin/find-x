package com.findx.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 代币购买记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "token_buyer",
        indexes = {
                @Index(name = "idx_chain_token", columnList = "chain_id,token_address"),
                @Index(name = "idx_buyer_address", columnList = "buyer_address"),
                @Index(name = "idx_tx_hash", columnList = "tx_hash", unique = true)
        })
public class TokenBuyer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 链标识，如 BSC / ETH / POLYGON */
    @Column(name = "chain_id", nullable = false, length = 20)
    private String chainId;

    /** 代币合约地址 */
    @Column(name = "token_address", nullable = false, length = 42)
    private String tokenAddress;

    /** 买家钱包地址 */
    @Column(name = "buyer_address", nullable = false, length = 42)
    private String buyerAddress;

    /** 买入数量（原始单位，已转换为可读精度） */
    @Column(name = "amount", nullable = false, precision = 36, scale = 18)
    private BigDecimal amount;

    /** 交易 Hash */
    @Column(name = "tx_hash", nullable = false, length = 66)
    private String txHash;

    /** 区块高度 */
    @Column(name = "block_number", nullable = false)
    private Long blockNumber;

    /** 区块时间 */
    @Column(name = "block_time", nullable = false)
    private LocalDateTime blockTime;

    /** 记录创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
