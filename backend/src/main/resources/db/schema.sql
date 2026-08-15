-- 电商物流与供应链参数化模拟平台 - 幂等建表脚本（MySQL 8 / H2 MySQL 模式均可执行）
-- 说明：JSON 列以 TEXT 存储（跨库兼容），由 Java 侧 Jackson 解析，运行期不依赖数据库 JSON 函数。

CREATE TABLE IF NOT EXISTS chapter (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(16)  NOT NULL UNIQUE COMMENT '章节编号，如 CH1',
    name       VARCHAR(64)  NOT NULL COMMENT '章节名称',
    sort_no    INT          NOT NULL COMMENT '展示顺序',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL
);

CREATE TABLE IF NOT EXISTS scenario (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    chapter_id   BIGINT       NOT NULL,
    module_id    VARCHAR(16)  NOT NULL UNIQUE COMMENT '模块 ID，如 CH2-003',
    name         VARCHAR(128) NOT NULL COMMENT '场景名称（与 V2 文档一致）',
    engine_key   VARCHAR(64)  NOT NULL COMMENT '执行器标识',
    difficulty   VARCHAR(16)  NOT NULL COMMENT 'intro/basic/advanced',
    class_hours  INT          NULL COMMENT '课时',
    is_role_play TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否角色扮演',
    concept      TEXT         NOT NULL COMMENT '核心概念',
    description  TEXT         NOT NULL COMMENT '流程描述',
    deps         TEXT         NULL COMMENT '依赖模块 ID 数组 JSON',
    params       TEXT         NOT NULL COMMENT '输入参数定义数组 JSON',
    outputs      TEXT         NOT NULL COMMENT '输出指标定义数组 JSON',
    constraints  TEXT         NULL COMMENT '约束表达式数组 JSON',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL,
    CONSTRAINT fk_scenario_chapter FOREIGN KEY (chapter_id) REFERENCES chapter (id)
);

CREATE TABLE IF NOT EXISTS simulation_run (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    scenario_id   BIGINT       NOT NULL,
    client_id     VARCHAR(64)  NOT NULL COMMENT '浏览器 UUID（无账号体系下的归属校验）',
    params        TEXT         NOT NULL COMMENT '本次运行参数快照 JSON',
    seed          BIGINT       NOT NULL COMMENT '随机种子',
    status        VARCHAR(16)  NOT NULL COMMENT 'RUNNING/COMPLETED/CANCELLED/FAILED',
    step_total    INT          NULL COMMENT '预估总步数（可为空=不确定）',
    step_count    INT          NOT NULL DEFAULT 0 COMMENT '已执行步数',
    result        TEXT         NULL COMMENT '输出指标结果 JSON',
    error_message VARCHAR(512) NULL COMMENT '失败原因',
    duration_ms   BIGINT       NULL COMMENT '执行耗时',
    started_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at   DATETIME     NULL,
    CONSTRAINT fk_run_scenario FOREIGN KEY (scenario_id) REFERENCES scenario (id),
    INDEX idx_run_client (client_id),
    INDEX idx_run_status (status)
);

CREATE TABLE IF NOT EXISTS simulation_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id     BIGINT       NOT NULL,
    step_no    INT          NOT NULL COMMENT '步骤序号（从 1 开始）',
    event_type VARCHAR(16)  NOT NULL COMMENT 'STEP/INFO/WARN/ERROR',
    message    VARCHAR(512) NOT NULL COMMENT '步骤说明（中文，展示于分步回放）',
    data       TEXT         NULL COMMENT '步骤快照 JSON',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_log_run FOREIGN KEY (run_id) REFERENCES simulation_run (id) ON DELETE CASCADE,
    INDEX idx_log_run_step (run_id, step_no)
);
