package com.findx.repository;

import com.findx.domain.entity.TokenBuyer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TokenBuyerRepository extends JpaRepository<TokenBuyer, Long> {

    List<TokenBuyer> findByChainIdAndTokenAddressOrderByAmountDesc(
            String chainId, String tokenAddress);

    List<TokenBuyer> findByChainIdAndTokenAddressAndBlockNumberBetweenOrderByBlockNumberAsc(
            String chainId, String tokenAddress, long fromBlock, long toBlock);

    boolean existsByTxHash(String txHash);

    @Query("SELECT COUNT(DISTINCT t.buyerAddress) FROM TokenBuyer t " +
           "WHERE t.chainId = :chainId AND t.tokenAddress = :tokenAddress")
    long countDistinctBuyers(@Param("chainId") String chainId,
                              @Param("tokenAddress") String tokenAddress);
}
