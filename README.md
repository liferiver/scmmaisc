# scmmaisc — 供应链物流模拟仿真教学平台

面向高校《电商物流与供应链管理》课程的**可视化仿真 + 多智能体研讨**教学平台：将教材知识点做成可运行、可复现、可对比、可导出的仿真场景，支撑课堂演示、学生实验与课后作业。

## 功能特性

- **场景目录**：按教材 11 章组织 84 个仿真场景（首期 15 + 二期 69），编号/名称/难度（入门/基础/进阶/综合）与《供应链物流模拟仿真场景列表V2》一致（FR-001/SC-001）
- **数据驱动**：场景定义（概念、流程、参数、输出、约束、依赖）存放于 `backend/src/main/resources/scenarios/*.json`，启动自动装载入库，新增场景无需改代码（FR-002）
- **仿真引擎**：84 个场景执行器（EOQ、啤酒游戏、跨境综合、供应链设计十步法、回购/收益共享、保理融资、碳足迹等），统一校验 → 分步执行 → 步骤日志（FR-009/FR-014）；综合场景子模型分层聚合（StepAggregator，单 run 步骤 ≤5000）
- **可复现**：随机种子显式注入，同 seed 结果完全一致（FR-008/SC-005）
- **过程即日志**：每一步执行事件落库 `simulation_log`，前端分步回放（上一步/下一步/自动播放）
- **语义化约束组**：纯求和比较约束（如六维权重和=1）由后端提取为 constraintGroups 下发，前端按组实时合计校验、违规不可提交，服务端执行器 validate 兑底（FR-005）
- **方案管理**：参数快照 + 结果保存到浏览器 localStorage，任意两组同指标并排对比（FR-011）
- **报告导出**：CSV（参数快照 + 全部指标）与 PNG（图表）两种格式，可直接提交作业（FR-012）
- **多智能体研讨**（三期）：仿真完成后一键发起五轮四人组讨论（柳经理·实操 / 霍教授·理论 / 景同学·计算 / 钟同学·提问），角色化发言 + 学生插话互动 + 三维结论卡片（理论/实操/前沿各 4 要素），历史回看 + Markdown 实验报告导出；84 场景讨论配置自动生成 + 人工覆盖（跨章知识图谱，引用零虚构）
- **安全与健壮**：clientId 白名单、params 结构守卫、错误信息不泄露内部细节、30 天日志自动清理（含讨论数据级联清理）

## 技术架构

```
┌────────────────────────── 前端（Vue 3 + TypeScript）──────────────────────────┐
│  Element Plus（目录/参数/表格/弹窗） · ECharts（曲线/拓扑/热力图/仪表盘）        │
│  Pinia（scenarioStore / runStore / planStore） · Vue Router                    │
└───────────────────────────────┬───────────────────────────────────────────────┘
                                │ REST（/api，Vite 代理）
┌───────────────────────────────▼───────────────────────────────────────────────┐
│                    后端（Spring Boot 3.3.5 + Java 17）                          │
│   Controller 层：章节/场景/运行/健康/讨论（契约 C1–C8 + D1–D8）                       │
│  Service 层：RunService（状态机 RUNNING→COMPLETED/CANCELLED/FAILED）、           │
│              ScenarioDataLoader、SimLogService、LogRetentionService（@Scheduled）、│
│              GroupConstraintExtractor（语义化求和组约束提取）、                     │
│              discussion：DiscussionService/DiscussionOrchestrator（五轮编排）       │
│              + PromptBuilder/ConclusionParser + ScenarioDiscussionProfileService      │
│  Engine 层（纯 Java 可独立测试）：SimulationEngine + 84 个 ScenarioExecutor       │
│              + StepAggregator + RandomSource(seed) + ExecutorRegistry             │
│  LLM 层：LlmClient 接口（HttpLlmClient 真实 / StubLlmClient 确定性演示）          │
│  MyBatis-Plus → MySQL 8（chapter / scenario / simulation_run / simulation_log /   │
│              discussion_session / utterance / question / conclusion / profile）    │
└────────────────────────────────────────────────────────────────────────────────┘
```

- 前端：Vue 3.4 + Vite 5 + TypeScript（strict）+ Element Plus 2.7 + ECharts 5 + Pinia + Vitest
- 后端：Spring Boot 3.3.5 + MyBatis-Plus + MySQL 8（测试用 H2 MySQL 模式）
- 测试：后端 `mvn test`（126 用例：引擎算例/复现/极端参数/84 场景冒烟 + 服务层 + MockMvc 契约 + 五轮讨论编排/插话/知识图谱）；前端 `npm run test`（108 用例：组件与 stores）+ `npm run build`（vue-tsc 类型检查）

## 目录结构

```
├── backend/
│   ├── Dockerfile              # 多阶段构建：maven 打包 → JRE 运行
│   ├── .dockerignore
│   ├── src/main/java/com/scmmaisc/
│   │   ├── controller/        # C1–C8 REST 接口 + D1–D8 讨论接口
│   │   ├── service/           # 运行/场景/日志/保留清理
│   │   ├── service/discussion/# 讨论会话/五轮编排/Prompt/结论解析/知识图谱配置
│   │   ├── llm/               # LlmClient 接口 + HttpLlmClient + StubLlmClient
│   │   ├── engine/            # 引擎框架 + executor/ 84 个场景执行器 + StepAggregator
│   │   ├── common/            # 统一响应、错误码、异常处理、ParamsGuard
│   │   ├── entity/ mapper/ config/
│   └── src/main/resources/
│       ├── db/schema.sql      # 幂等建表（含 5 张讨论表）
│       ├── scenarios/*.json   # 84 个场景定义（启动装载）
│       ├── discussion-profiles/*.json  # 场景讨论配置人工覆盖（AUTO 生成的替代）
│       └── prompts/personas/*.md       # 四角色 system prompt 文案
├── frontend/
│   ├── Dockerfile              # 多阶段构建：vue-tsc + vite 打包 → nginx 运行
│   ├── nginx.conf              # 静态服务 + history 回退 + /api 代理
│   ├── .dockerignore
│   ├── src/views/             # 目录 / 场景详情 / 运行 / 方案对比 / 讨论 / 讨论历史
│   ├── src/components/        # ParamPanel / StepTimeline / OutputChart / ExportButton / SavePlanDialog / UtteranceList / ConclusionCards
│   ├── src/stores/            # scenarioStore / runStore / planStore / discussionStore
│   ├── src/api/  src/utils/  src/types/  src/router/
│   └── tests/                 # Vitest 组件与 stores 测试（含讨论/历史视图）
├── docker-compose.yml           # 一键编排：mysql + backend + frontend
├── .env.example                 # 环境变量模板（复制为 .env 使用）
├── specs/001-scm-sim-platform/  # 需求/契约/任务/验收（Spec-Driven 工件）
├── specs/002-multi-agent-interaction/  # 三期多智能体研讨（Spec-Driven 工件）
└── docs/                        # 教学场景列表 V2、教学大纲、学生使用说明
```

## 快速开始

前置要求：JDK 17+、Maven 3.8+、Node.js 18+、npm 9+、MySQL 8。

### 1. 初始化数据库

```sql
CREATE DATABASE scmmaisc CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

表结构由后端启动时按 `schema.sql` 幂等创建；84 个场景由 `ScenarioDataLoader` 自动装载（无需手工导入）。

### 2. 配置 LLM（多智能体讨论，可跳过）

默认 **stub 模式**（`LLM_STUB=true`）：无需密钥、无网络调用，四角色发言为确定性预设文本，可完整演示五轮讨论/插话/历史导出，适合课堂离线演示。

接入真实模型（OpenAI 兼容接口）时设置：

```powershell
$env:LLM_STUB="false"
$env:LLM_BASE_URL="http://localhost:8000/v1"   # 兼容 /chat/completions 的服务地址
$env:LLM_MODEL="qwen2.5-72b-instruct"
$env:LLM_API_KEY="你的密钥"                      # 密钥只走环境变量，不写入任何文件
```

### 3. 启动后端（默认 8080 端口）

```powershell
cd backend
$env:DB_URL="jdbc:mysql://localhost:3306/scmmaisc?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$env:DB_USER="root"
$env:DB_PASSWORD="你的密码"
mvn spring-boot:run
```

预期：启动日志出现 `Scenarios loaded: 84`；`GET http://localhost:8080/api/health` 返回 `{"code":0,...,"data":{"status":"UP","db":"UP"}}`。
若 8080 被占用，可用 `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081` 换端口，并同步修改前端代理目标。

### 4. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

访问 http://localhost:5173（Vite 代理 `/api` → 8080）。使用说明见 [docs/学生使用说明.md](docs/学生使用说明.md)。

### 多智能体研讨使用指引（三期）

1. **开始讨论**：仿真运行完成后点击「开始讨论」，异步产出五轮发言（现象解读 → 深度剖析 → 跨章连接 → 前沿延伸 → 三维收敛），可随时查看进度；多人并发时自动排队
2. **插话互动**：第 2 轮起可在「我也想问…」输入框提交问题（≤200 字），下一轮全部角色优先回应，同辈角色（景/钟）优先
3. **放弃**：讨论进行中可主动放弃；失败时已生成内容保留，可重新发起
4. **回看与导出**：「讨论历史」列表回看任意已完成讨论；「导出报告」生成 Markdown 实验报告附录（场景信息 + 五轮发言 + 三维结论 12 要素 + 投票结果）
5. **知识图谱**：跨章连接轮自动引用该场景的前序知识/后续延伸（84 场景自动生成 + 高活跃场景人工覆盖，引用零虚构）

### 5. 运行测试

```powershell
cd backend; mvn test        # 引擎算例/复现/极端参数 + 服务层 + MockMvc 契约
cd frontend; npm run test   # 组件与 stores 单元测试
cd frontend; npm run build  # 类型检查（vue-tsc）+ 生产构建
```

## Docker 一键部署

前置要求：Docker 24+（含 Compose v2）。无需本机安装 JDK/Node/MySQL，三个容器（mysql + backend + frontend）一条命令拉起。

```bash
# 1.（可选）按需修改数据库口令：复制 .env.example 为 .env 并编辑
cp .env.example .env

# 2. 构建并启动（首次构建需拉取基础镜像与依赖，耗时几分钟）
docker compose up -d --build

# 3. 验证
docker compose ps              # 三个服务均应为 running/healthy
curl http://localhost:8080/api/health   # {"code":0,...,"data":{"status":"UP","db":"UP"}}
```

访问 http://localhost:8088 即可使用（前端 Nginx 已将 `/api` 反向代理到后端容器）。

| 服务 | 容器内 | 宿主机端口 | 说明 |
|---|---|---|---|
| mysql | 3306 | 不对外 | MySQL 8（utf8mb4），数据持久化在命名卷 `mysql-data` |
| backend | 8080 | 8080 | Spring Boot，启动自动建表并装载 84 个场景 |
| frontend | 80 | 8088 | Nginx 静态站点 + `/api` 代理 |

常用运维命令：

```bash
docker compose logs -f backend   # 跟踪后端日志（可见 “Scenarios loaded: 84”）
docker compose down              # 停止（数据卷保留，下次 up 数据仍在）
docker compose down -v           # 停止并删除数据卷（⚠️ 运行记录与日志将清空）
docker compose up -d --build     # 代码变更后重建镜像并热替换
```

> **安全提示**：默认口令 `scmmaisc123` 仅用于本地体验；部署到可被他人访问的环境前，务必在 `.env` 中设置强口令（`.env` 已被 .gitignore 忽略，不会入库）。
> 若宿主机 8080/8088 被占用，可修改 `docker-compose.yml` 中 `ports` 左侧宿主机端口，例如 `"8088:80"` → `"18088:80"`。

## 教学场景清单（84 个：一期 15 + 二期 69）

| 章节 | 场景数 | 代表场景 |
|---|---|---|
| 第 1 章 概论 | 8 | CH1-001 供应商铁三角（权重和=1 约束）、CH1-002 物流 7R、CH1-004 模式对比 |
| 第 2 章 物流系统控制 | 10 | CH2-003 EOQ、CH2-004 经济生产批量、CH2-005 缺货 EOQ、CH2-006 (s,Q) 策略 |
| 第 3 章 物流技术与信息系统 | 8 | CH3-002 条码/RFID、CH3-005 AGV 拣选、CH3-007 物流系统集成 |
| 第 4 章 电子商务物流 | 7 | CH4-002 电商物流模式、CH4-004 京东模式、CH4-005 菜鸟模式 |
| 第 5 章 跨境物流管理 | 8 | CH5-001 三段式运输、CH5-003 中欧班列、CH5-008 跨境综合（综合） |
| 第 6 章 供应链管理 | 6 | CH6-001 伙伴选择、CH6-002 外包决策、CH6-005 平衡计分卡 |
| 第 7 章 供应链设计与构建 | 8 | CH7-001 重心法选址、CH7-002 推拉策略、CH7-008 供应链设计十步法（综合） |
| 第 8 章 供应链协同 | 8 | CH8-001 啤酒游戏、CH8-005 回购合同、CH8-006 收益共享、CH8-008 合同谈判（综合） |
| 第 9 章 供应链金融 | 6 | CH9-001 保理融资、CH9-002 存货质押、CH9-006 供应链金融综合（综合） |
| 第 10 章 全球供应链 | 5 | CH10-001 区位配置、CH10-003 中断恢复、CH10-004 跨境金融 |
| 第 11 章 现代物流与供应链发展 | 10 | CH11-001 需求预测、CH11-004 碳足迹、CH11-008 应急物流网络 |

- 全量场景清单（moduleId/名称/难度）以 [docs/供应链物流模拟仿真场景列表V2.md](docs/供应链物流模拟仿真场景列表V2.md) 为准，装载后目录页逐章展示（SC-001）。
- 综合难度（4 个）：CH5-008、CH7-008、CH8-008、CH9-006，运行呈现 ≤5s、分步回放 ≤5000 步（SC-008）。

## 文档索引

- 教学场景定义：[docs/供应链物流模拟仿真场景列表V2.md](docs/供应链物流模拟仿真场景列表V2.md)
- 教学大纲：[docs/电商物流与供应链管理教学大纲.md](docs/电商物流与供应链管理教学大纲.md)
- 学生使用说明：[docs/学生使用说明.md](docs/学生使用说明.md)
- Spec 工件：[specs/001-scm-sim-platform/](specs/001-scm-sim-platform/)（spec.md / contracts/api.md / ui.md / data-model.md / tasks.md / quickstart.md）
- 三期讨论 Spec 工件：[specs/002-multi-agent-interaction/](specs/002-multi-agent-interaction/)（spec.md / plan.md / research.md / contracts/discussions.md / tasks.md / quickstart.md）
