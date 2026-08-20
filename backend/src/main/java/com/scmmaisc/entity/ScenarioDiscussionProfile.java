package com.scmmaisc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 场景讨论配置：与场景一一对应，84 份全覆盖（data-model.md §5，FR-006）。 */
@Data
@TableName("scenario_discussion_profile")
public class ScenarioDiscussionProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("scenario_id")
    private Long scenarioId;

    /** 冗余便于装载比对。 */
    @TableField("module_id")
    private String moduleId;

    /** 概念标签数组 JSON（concept 自动拆分或人工覆盖）。 */
    @TableField("concept_tags")
    private String conceptTags;

    /** 教材章节定位（如"第2章第3节"）。 */
    @TableField("chapter_section")
    private String chapterSection;

    /** 前序知识数组 JSON（deps 场景名 + 链路映射）。 */
    @TableField("prev_knowledge")
    private String prevKnowledge;

    /** 后续延伸数组 JSON（链路映射）。 */
    @TableField("next_extension")
    private String nextExtension;

    /** 典型讨论切入点数组 JSON（未配置时通用模板）。 */
    @TableField("discussion_starters")
    private String discussionStarters;

    /** 柳经理案例库索引数组 JSON（默认空）。 */
    @TableField("case_library")
    private String caseLibrary;

    /** 霍教授理论库索引数组 JSON（默认空）。 */
    @TableField("theory_library")
    private String theoryLibrary;

    /** AUTO（自动生成）/ MANUAL（人工覆盖）。 */
    private String source;

    private LocalDateTime updatedAt;
}
