package com.scmmaisc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 三维结论：讨论收敛产物，与会话一对一（data-model.md §4，FR-004）。 */
@Data
@TableName("discussion_conclusion")
public class DiscussionConclusion {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private Long sessionId;

    /** 理论结论 JSON：{core_model, derivation, assumptions, knowledge_location}。 */
    @TableField("theory_json")
    private String theoryJson;

    /** 实操结论 JSON：{param_business, case_benchmark, sim_reality_gap, suggestions}。 */
    @TableField("practice_json")
    private String practiceJson;

    /** 前沿结论 JSON：{industry, academic, student_advice, vote_item}。 */
    @TableField("frontier_json")
    private String frontierJson;

    private LocalDateTime createdAt;
}
