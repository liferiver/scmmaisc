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

-- ==================== 三期：多智能体交互研讨（5 表，data-model.md） ====================

CREATE TABLE IF NOT EXISTS discussion_session (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id          BIGINT       NOT NULL COMMENT '关联运行（快照来源，FR-013）',
    scenario_id     BIGINT       NOT NULL COMMENT '冗余存储，历史列表免 join',
    client_id       VARCHAR(64)  NOT NULL COMMENT '浏览器 UUID（无账号体系下的归属校验）',
    status          VARCHAR(16)  NOT NULL COMMENT 'QUEUED/RUNNING/COMPLETED/FAILED/ABANDONED',
    round_no        INT          NOT NULL DEFAULT 0 COMMENT '当前进度：0=排队/启动，1..5=进行中，5+结论生成中',
    queue_position  INT          NULL COMMENT '排队时的位置（1 起；运行时置 NULL）',
    conclusion_note VARCHAR(512) NULL COMMENT '结论降级说明（不泄露内部细节）',
    started_at      DATETIME     NULL COMMENT '实际开始执行（出队）时间',
    finished_at     DATETIME     NULL COMMENT '终态时间',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL,
    CONSTRAINT fk_discussion_session_run FOREIGN KEY (run_id) REFERENCES simulation_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_discussion_session_scenario FOREIGN KEY (scenario_id) REFERENCES scenario (id),
    INDEX idx_discussion_client (client_id, created_at)
);

CREATE TABLE IF NOT EXISTS discussion_question (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT       NOT NULL,
    round_no   INT          NOT NULL COMMENT '提交时所处轮次（1..5）',
    content    VARCHAR(200) NOT NULL COMMENT '问题原文（超长截断）',
    responded  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已被后续发言回应（SC-006 校验依据）',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_question_session FOREIGN KEY (session_id) REFERENCES discussion_session (id) ON DELETE CASCADE,
    INDEX idx_question_session (session_id, responded)
);

CREATE TABLE IF NOT EXISTS discussion_utterance (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id       BIGINT       NOT NULL,
    round_no         INT          NOT NULL COMMENT '1..5（结论段不在此表）',
    agent_role       VARCHAR(16)  NOT NULL COMMENT 'LIU/HUO/JING/ZHONG',
    content          TEXT         NOT NULL COMMENT '发言内容（后端截断保护：上限 4000 字）',
    reply_question_id BIGINT      NULL COMMENT '本条发言回应的学生问题',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_utterance_session FOREIGN KEY (session_id) REFERENCES discussion_session (id) ON DELETE CASCADE,
    CONSTRAINT fk_utterance_question FOREIGN KEY (reply_question_id) REFERENCES discussion_question (id),
    INDEX idx_utterance_session (session_id, round_no, id)
);

CREATE TABLE IF NOT EXISTS discussion_conclusion (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id    BIGINT NOT NULL,
    theory_json   TEXT   NOT NULL COMMENT '理论结论 JSON：core_model/derivation/assumptions/knowledge_location',
    practice_json TEXT   NOT NULL COMMENT '实操结论 JSON：param_business/case_benchmark/sim_reality_gap/suggestions',
    frontier_json TEXT   NOT NULL COMMENT '前沿结论 JSON：industry/academic/student_advice/vote_item',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_conclusion_session FOREIGN KEY (session_id) REFERENCES discussion_session (id) ON DELETE CASCADE,
    UNIQUE KEY uk_conclusion_session (session_id)
);

CREATE TABLE IF NOT EXISTS scenario_discussion_profile (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    scenario_id         BIGINT       NOT NULL,
    module_id           VARCHAR(16)  NOT NULL COMMENT '冗余便于装载比对',
    concept_tags        TEXT         NOT NULL COMMENT '概念标签数组 JSON',
    chapter_section     VARCHAR(64)  NULL COMMENT '教材章节定位（如“第2章第3节”）',
    prev_knowledge      TEXT         NOT NULL COMMENT '前序知识数组 JSON',
    next_extension      TEXT         NOT NULL COMMENT '后续延伸数组 JSON',
    discussion_starters TEXT         NOT NULL COMMENT '典型讨论切入点数组 JSON',
    case_library        TEXT         NOT NULL COMMENT '柳经理案例库索引数组 JSON（默认空）',
    theory_library      TEXT         NOT NULL COMMENT '霍教授理论库索引数组 JSON（默认空）',
    source              VARCHAR(16)  NOT NULL COMMENT 'AUTO（自动生成）/ MANUAL（人工覆盖）',
    updated_at          DATETIME     NOT NULL,
    CONSTRAINT fk_profile_scenario FOREIGN KEY (scenario_id) REFERENCES scenario (id) ON DELETE CASCADE,
    UNIQUE KEY uk_profile_scenario (scenario_id),
    UNIQUE KEY uk_profile_module (module_id)
);
