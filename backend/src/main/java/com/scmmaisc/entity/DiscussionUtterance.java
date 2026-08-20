package com.scmmaisc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 讨论发言：单条智能体发言（data-model.md §2）。 */
@Data
@TableName("discussion_utterance")
public class DiscussionUtterance {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private Long sessionId;

    /** 1..5（结论段不在此表）。 */
    @TableField("round_no")
    private Integer roundNo;

    /** LIU（柳经理）/ HUO（霍教授）/ JING（景同学）/ ZHONG（钟同学）。 */
    @TableField("agent_role")
    private String agentRole;

    /** 发言内容（后端截断保护：上限 4000 字）。 */
    private String content;

    /** 本条发言回应的学生问题（有则关联）。 */
    @TableField("reply_question_id")
    private Long replyQuestionId;

    private LocalDateTime createdAt;
}
