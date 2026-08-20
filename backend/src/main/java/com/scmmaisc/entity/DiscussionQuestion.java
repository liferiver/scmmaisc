package com.scmmaisc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 学生插话：讨论中学生提交的问题（data-model.md §3，FR-007）。 */
@Data
@TableName("discussion_question")
public class DiscussionQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private Long sessionId;

    /** 提交时所处轮次（1..5）。 */
    @TableField("round_no")
    private Integer roundNo;

    /** 问题原文；提交时校验：空白忽略、超 200 字截断（US3-AC3）。 */
    private String content;

    /** 是否已被后续发言回应（SC-006 校验依据）。 */
    private Boolean responded;

    private LocalDateTime createdAt;
}
