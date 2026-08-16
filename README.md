# 🍽️ Diet Agent - 智能饮食推荐系统

面向 "不知道吃什么" 的日常就餐决策难题场景，打造一款结合用户实时心情、场景、健康诉求等多维动态信息的智能饮食推荐 Agent。
通过编排多 Agent 协作，实现意图识别 → 槽位澄清 → 标签检索重排 → LLM 推荐理由生成的完整对话式推荐链路，并设计 Trace 全链路监控与离线评估体系。

## ✨ 核心能力

- **多 Agent 协作**：意图识别、澄清追问、推荐应答、评估判断四大 Agent 智能协作
- **自然语言交互**：通过自然语言描述需求，智能解析并填充 7 个关键槽位
- **个性化推荐**：支持个人菜谱库和公共菜谱库，基于用户偏好智能推荐
- **风险守卫机制**：自动检测健康风险，拦截不当医疗建议
- **全链路追踪**：完整记录对话决策过程，支持质量评估
- **多餐次规划**：支持一日三餐、夜宵等多场景规划

## 🛠️ 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 21 |
| 框架 | Spring Boot | 3.3.13 |
| AI 框架 | AgentScope | 1.0.11 |
| 数据库 | MySQL | 8.x |
| ORM | MyBatis | 3.0.4 |
| 工具 | Hutool | 5.8.30 |
| 构建 | Maven | 3.x |
| LLM | DashScope/Qwen | qwen-max / qwen-turbo |

## 📋 环境要求

- **JDK 21**（必须）
- **Maven 3.9+**
- **MySQL 8.x**
- **DashScope API Key**

## ⚙️ 配置说明

### 1. 数据库配置

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/diet_db?createDatabaseIfNotExist=true
    username: root
    password: your_password

agentscope:
  dashscope:
    api-key: your-dashscope-api-key

server:
  port: 8080
```

### 2. 主要配置项

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `spring.datasource.url` | MySQL 连接地址 | jdbc:mysql://localhost:3306/diet_db |
| `agentscope.dashscope.api-key` | DashScope API Key | - |
| `diet.llm.main-model` | 主模型（推荐） | qwen-max |
| `diet.llm.light-model` | 轻量模型（澄清等） | qwen-turbo |


## 🚀 项目启动简洁版流程
1. 创建MySQL数据库 diet_db
2. 执行sql脚本 src/main/resources/db/diet_db.sql
3. 在阿里云百炼平台申请DashScope API Key，将api-key填写到src/main/resources/application.yml 配置文件的agentscope.dashscope.api-key之下
4. 启动项目，项目可以直接本地启动，前后端的代码都在一起


## 🚀 项目启动详细版流程

### 1. 数据库初始化，创建数据库

```sql
CREATE DATABASE diet_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 2. 导入初始化脚本

```bash
mysql -u root -p diet_db < db/diet_db.sql
```

该脚本会自动创建以下表：
- `diet_sessions` - 会话管理
- `diet_messages` - 消息记录
- `diet_request_trace` - 链路追踪
- `meal_item` - 菜品信息
- `diet_slot_option` - 槽位选项

### 3. 验证数据

```sql
-- 查看菜品数据
SELECT * FROM meal_item;

-- 查看槽位选项
SELECT * FROM diet_slot_option;
```

### 4. 编译项目

```bash
cd diet-agent
mvn clean package -DskipTests
```

### 5. 启动服务

```bash
java -jar target/diet-agent-1.0-SNAPSHOT.jar
```


## 📖 访问服务示例

启动成功后，访问以下地址：

- **API 根路径**: http://localhost:8080/api/v1/diet/
- **Web 管理界面**: http://localhost:8080/
- **健康检查**: http://localhost:8080/actuator/health


## 📖 快速开始示例

### 1. 创建会话

```bash
curl -X POST http://localhost:8080/api/v1/diet/sessions \
  -H "X-User-Id: 1"
```

### 2. 开始对话

```bash
curl -X POST http://localhost:8080/api/v1/diet/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 1" \
  -d '{
    "sessionId": "your-session-id",
    "message": "我想吃清淡点的午餐"
  }'
```

