package com.scmmaisc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 讨论会话：一次多智能体讨论的容器（data-model.md §1，澄清 Q4/Q5）。 */
@Data
@TableName("discussion_session")
public class DiscussionSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联运行（快照来源，FR-013）。 */
    @TableField("run_id")
    private Long runId;

    /** 冗余存储，历史列表免 join。 */
    @TableField("scenario_id")
    private Long scenarioId;

    /** 浏览器 UUID（无账号体系下的归属校验）。 */
    @TableField("client_id")
    private String clientId;

    /** QUEUED / RUNNING / COMPLETED / FAILED / ABANDONED（终态不可变）。 */
    private String status;

    /** 当前进度：0=排队/启动，1..5=进行中轮次，5+结论生成中。 */
    @TableField("round_no")
    private Integer roundNo;

    /** 排队时的位置（1 起；运行时置 NULL）。 */
    @TableField("queue_position")
    private Integer queuePosition;

    /** 结论降级说明（不泄露内部细节）。 */
    @TableField("conclusion_note")
    private String conclusionNote;

    /** 实际开始执行（出队）时间。 */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /** 终态时间。 */
    @TableField("finished_at")
    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
