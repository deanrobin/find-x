# find-x

## 技术栈
- **Java**: JDK 17
- **框架**: Spring Boot 3.2.3
- **构建工具**: Maven
- **数据库**: MySQL 8.x
- **ORM**: Spring Data JPA (Hibernate)

## 环境要求
- JDK 17+
- Maven 3.8+
- MySQL 8.0+

## 快速开始

### 1. 创建数据库
```sql
CREATE DATABASE findx CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 2. 修改配置
编辑 `src/main/resources/application.yml`，填写数据库连接信息：
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/findx?...
    username: root
    password: your_password
```

### 3. 构建运行
```bash
# 编译
mvn clean package -DskipTests

# 运行
java -jar target/find-x-1.0.0-SNAPSHOT.jar

# 或者直接用 Maven 启动
mvn spring-boot:run
```

## 项目结构
```
find-x/
├── src/
│   ├── main/
│   │   ├── java/com/findx/
│   │   │   └── FindXApplication.java
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│       └── java/com/findx/
│           └── FindXApplicationTests.java
└── pom.xml
```
