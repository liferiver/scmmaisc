package com.scmmaisc.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 模拟执行日志（"过程保存为日志"）：逐步记录执行过程，支撑分步回放（FR-009）。 */
@Data
@TableName("simulation_log")
public class SimulationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("run_id")
    private Long runId;

    /** 步骤序号（从 1 开始）。 */
    @TableField("step_no")
    private Integer stepNo;

    /** STEP / INFO / WARN / ERROR。 */
    @TableField("event_type")
    private String eventType;

    /** 步骤说明（中文，展示于分步回放）。 */
    private String message;

    /** 步骤快照 JSON（中间状态，如当期库存/订单量）。 */
    private String data;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
