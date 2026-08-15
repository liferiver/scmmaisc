package com.scmmaisc.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 仿真运行：一次执行的记录（含参数快照、随机种子、状态与结果）。 */
@Data
@TableName("simulation_run")
public class SimulationRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("scenario_id")
    private Long scenarioId;

    /** 浏览器 UUID（无账号体系下的最低限度归属校验）。 */
    @TableField("client_id")
    private String clientId;

    /** 本次运行参数快照 JSON。 */
    private String params;

    private Long seed;

    /** RUNNING / COMPLETED / CANCELLED / FAILED。 */
    private String status;

    @TableField("step_total")
    private Integer stepTotal;

    @TableField("step_count")
    private Integer stepCount;

    /** 输出指标结果 JSON。 */
    private String result;

    @TableField("error_message")
    private String errorMessage;

    @TableField("duration_ms")
    private Long durationMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
