# scmmaisc — 供应链物流模拟仿真教学平台

面向高校《电商物流与供应链管理》课程的**可视化仿真 + 多智能体研讨**教学平台：将教材知识点做成可运行、可复现、可对比、可导出的仿真场景，支撑课堂演示、学生实验与课后作业。

## 功能特性

- **场景目录**：按教材 11 章组织 15 个仿真场景（首期 MVP），编号/名称与《供应链物流模拟仿真场景列表V2》一致（FR-001）
- **数据驱动**：场景定义（概念、流程、参数、输出、约束、依赖）存放于 `backend/src/main/resources/scenarios/*.json`，启动自动装载入库，新增场景无需改代码（FR-002）
- **仿真引擎**：15 个场景执行器（EOQ、啤酒游戏、跨境三段式、预测对比、碳足迹等），统一校验 → 分步执行 → 步骤日志（FR-009/FR-014）
- **可复现**：随机种子显式注入，同 seed 结果完全一致（FR-008/SC-005）
- **过程即日志**：每一步执行事件落库 `simulation_log`，前端分步回放（上一步/下一步/自动播放）
- **方案管理**：参数快照 + 结果保存到浏览器 localStorage，任意两组同指标并排对比（FR-011）
- **报告导出**：CSV（参数快照 + 全部指标）与 PNG（图表）两种格式，可直接提交作业（FR-012）
- **安全与健壮**：clientId 白名单、params 结构守卫、错误信息不泄露内部细节、30 天日志自动清理

## 技术架构

```
┌────────────────────────── 前端（Vue 3 + TypeScript）──────────────────────────┐
│  Element Plus（目录/参数/表格/弹窗） · ECharts（曲线/拓扑/热力图/仪表盘）        │
│  Pinia（scenarioStore / runStore / planStore） · Vue Router                    │
└───────────────────────────────┬───────────────────────────────────────────────┘
                                │ REST（/api，Vite 代理）
┌───────────────────────────────▼───────────────────────────────────────────────┐
│                    后端（Spring Boot 3.3.5 + Java 17）                          │
│  Controller 层：章节/场景/运行/健康（契约 C1–C8）                                │
│  Service 层：RunService（状态机 RUNNING→COMPLETED/CANCELLED/FAILED）、           │
│              ScenarioDataLoader、SimLogService、LogRetentionService（@Scheduled）│
│  Engine 层（纯 Java 可独立测试）：SimulationEngine + 15 个 ScenarioExecutor     │
│              + RandomSource(seed) + ExecutorRegistry                            │
│  MyBatis-Plus → MySQL 8（chapter / scenario / simulation_run / simulation_log） │
└────────────────────────────────────────────────────────────────────────────────┘
```

- 前端：Vue 3.4 + Vite 5 + TypeScript（strict）+ Element Plus 2.7 + ECharts 5 + Pinia + Vitest
- 后端：Spring Boot 3.3.5 + MyBatis-Plus + MySQL 8（测试用 H2 MySQL 模式）
- 测试：后端 `mvn test`（引擎算例/复现/极端参数 + 服务层 + MockMvc 契约）；前端 `npm run test`（组件与 stores）

## 目录结构

```
├── backend/
│   ├── src/main/java/com/scmmaisc/
│   │   ├── controller/        # C1–C8 REST 接口
│   │   ├── service/           # 运行/场景/日志/保留清理
│   │   ├── engine/            # 引擎框架 + executor/ 15 个场景执行器
│   │   ├── common/            # 统一响应、错误码、异常处理、ParamsGuard
│   │   ├── entity/ mapper/ config/
│   └── src/main/resources/
│       ├── db/schema.sql      # 幂等建表
│       └── scenarios/*.json   # 15 个场景定义（启动装载）
├── frontend/
│   ├── src/views/             # 目录 / 场景详情 / 运行 / 方案对比
│   ├── src/components/        # ParamPanel / StepTimeline / OutputChart / ExportButton / SavePlanDialog
│   ├── src/stores/            # scenarioStore / runStore / planStore
│   ├── src/api/  src/utils/  src/types/
│   └── tests/                 # Vitest 组件与 stores 测试
├── specs/001-scm-sim-platform/  # 需求/契约/任务/验收（Spec-Driven 工件）
└── docs/                        # 教学场景列表 V2、教学大纲、学生使用说明
```

## 快速开始

前置要求：JDK 17+、Maven 3.8+、Node.js 18+、npm 9+、MySQL 8。

### 1. 初始化数据库

```sql
CREATE DATABASE scmmaisc CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

表结构由后端启动时按 `schema.sql` 幂等创建；15 个场景由 `ScenarioDataLoader` 自动装载（无需手工导入）。

### 2. 启动后端（默认 8080 端口）

```powershell
cd backend
$env:DB_URL="jdbc:mysql://localhost:3306/scmmaisc?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$env:DB_USER="root"
$env:DB_PASSWORD="你的密码"
mvn spring-boot:run
```

预期：启动日志出现 `Scenarios loaded: 15`；`GET http://localhost:8080/api/health` 返回 `{"code":0,...,"data":{"status":"UP","db":"UP"}}`。
若 8080 被占用，可用 `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081` 换端口，并同步修改前端代理目标。

### 3. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

访问 http://localhost:5173（Vite 代理 `/api` → 8080）。使用说明见 [docs/学生使用说明.md](docs/学生使用说明.md)。

### 4. 运行测试

```powershell
cd backend; mvn test        # 引擎算例/复现/极端参数 + 服务层 + MockMvc 契约
cd frontend; npm run test   # 组件与 stores 单元测试
cd frontend; npm run build  # 类型检查（vue-tsc）+ 生产构建
```

## 教学场景清单（首期 15 个）

| 章节 | 模块 ID | 场景 | 引擎 | 难度 |
|---|---|---|---|---|
| 第 1 章 概论 | CH1-002 | 物流7R服务目标履约 | seven-r | 入门 |
| 第 1 章 概论 | CH1-004 | 自营/3PL/物流联盟/4PL模式对比 | mode-compare | 基础 |
| 第 2 章 物流系统控制 | CH2-002 | 物流成本背反动态仿真 | cost-tradeoff | 基础 |
| 第 2 章 物流系统控制 | CH2-003 | EOQ经济订货批量 | eoq | 入门 |
| 第 3 章 仓储管理 | CH3-004 | 仓储全流程作业仿真 | warehouse | 基础 |
| 第 4 章 电商物流 | CH4-006 | 电商最后一公里配送模式仿真 | last-mile | 基础 |
| 第 5 章 国际物流 | CH5-001 | 跨境物流三段式运输仿真 | cross-border | 进阶 |
| 第 6 章 供应链管理 | CH6-002 | 核心竞争力识别与外包决策仿真 | outsourcing | 基础 |
| 第 7 章 供应链设计 | CH7-002 | 推动/拉动/推拉结合策略仿真 | push-pull | 基础 |
| 第 8 章 供应链协同 | CH8-001 | 啤酒游戏——牛鞭效应 | beer-game | 基础 |
| 第 8 章 供应链协同 | CH8-004 | 批发价格合同与双重边际效应仿真 | contract | 基础 |
| 第 9 章 供应链金融 | CH9-001 | 应收账款融资（保理）仿真 | factoring | 基础 |
| 第 10 章 全球供应链 | CH10-001 | 全球供应链区位配置仿真 | location | 进阶 |
| 第 11 章 现代物流与供应链发展 | CH11-001 | 时间序列需求预测方法对比仿真 | forecast | 基础 |
| 第 11 章 现代物流与供应链发展 | CH11-004 | 绿色物流碳足迹追踪与碳税仿真 | carbon | 基础 |

## 文档索引

- 教学场景定义：[docs/供应链物流模拟仿真场景列表V2.md](docs/供应链物流模拟仿真场景列表V2.md)
- 教学大纲：[docs/电商物流与供应链管理教学大纲.md](docs/电商物流与供应链管理教学大纲.md)
- 学生使用说明：[docs/学生使用说明.md](docs/学生使用说明.md)
- Spec 工件：[specs/001-scm-sim-platform/](specs/001-scm-sim-platform/)（spec.md / contracts/api.md / ui.md / data-model.md / tasks.md / quickstart.md）
