# find-x

链上代币买入地址扫描工具。输入合约地址、截止时间、最小买入量，自动往前倒推 48 小时，找出所有符合条件的买入记录并持久化到 MySQL。

## 技术栈
- **Java**: JDK 17
- **框架**: Spring Boot 3.2.3
- **构建**: Maven
- **数据库**: MySQL 8.x (Spring Data JPA)
- **链交互**: Web3j 4.10.3

## 项目架构

```
com.findx
├── api/controller/      # REST 接口层
├── service/             # 业务逻辑（接口 + impl）
├── chain/               # 链抽象层
│   ├── ChainScanner     # 链扫描接口（多链扩展点）
│   ├── ChainScannerRegistry  # 扫描器注册/路由
│   └── bsc/BscScanner   # BSC 实现
├── domain/
│   ├── entity/          # JPA 实体
│   └── dto/             # 请求/响应 DTO
├── repository/          # 数据访问层
└── config/              # 配置
```

> **扩展新链**：只需实现 `ChainScanner` 接口并注册为 Spring Bean，无需改动任何业务代码。

## API

### POST /api/scan

```json
{
  "chainId":      "BSC",
  "tokenAddress": "0x...",
  "endTime":      "2024-03-05T15:00:00",
  "minAmount":    100.5
}
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| chainId | string | 否 | 默认 `BSC` |
| tokenAddress | string | 是 | 代币合约地址 |
| endTime | string | 否 | 截止时间，默认当前时间 |
| minAmount | number | 是 | 最小买入量（可读单位） |

**响应示例：**
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "chainId": "BSC",
    "tokenAddress": "0x...",
    "fromBlock": 37000000,
    "toBlock":   37057600,
    "fromTime": "2024-03-03 15:00:00",
    "toTime":   "2024-03-05 15:00:00",
    "totalFound": 42,
    "records": [
      {
        "buyerAddress": "0xabc...",
        "amount": "150.5",
        "txHash": "0xdef...",
        "blockNumber": 37023456,
        "blockTime": "2024-03-04 08:23:11"
      }
    ]
  }
}
```

## 数据库表

```sql
CREATE TABLE token_buyer (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  chain_id      VARCHAR(20)  NOT NULL,
  token_address VARCHAR(42)  NOT NULL,
  buyer_address VARCHAR(42)  NOT NULL,
  amount        DECIMAL(36,18) NOT NULL,
  tx_hash       VARCHAR(66)  NOT NULL,
  block_number  BIGINT       NOT NULL,
  block_time    DATETIME     NOT NULL,
  created_at    DATETIME     NOT NULL,
  UNIQUE KEY idx_tx_hash (tx_hash),
  INDEX idx_chain_token (chain_id, token_address),
  INDEX idx_buyer_address (buyer_address)
);
```
（JPA `ddl-auto: update` 自动建表）

## 快速开始

```bash
# 1. 启动（需设置 DB_PASSWORD 环境变量）
DB_PASSWORD=xxx mvn spring-boot:run

# 2. 调用接口
curl -X POST http://localhost:8080/api/scan \
  -H "Content-Type: application/json" \
  -d '{
    "chainId": "BSC",
    "tokenAddress": "0x0e09fabb73bd3ade0a17ecc321fd13a19e81ce82",
    "minAmount": 1000
  }'
```

## 扩展新链（示例）

```java
@Component
public class EthScanner implements ChainScanner {
    @Override
    public String chainId() { return "ETH"; }
    // ... 实现其他方法
}
```
注册后即可通过 `chainId: "ETH"` 调用，无需改动其他代码。
