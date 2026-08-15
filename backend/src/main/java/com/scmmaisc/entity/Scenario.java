package com.scmmaisc.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 场景：单个知识点的仿真单元，定义由 V2 文档转写、数据驱动加载（FR-002）。 */
@Data
@TableName("scenario")
public class Scenario {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("chapter_id")
    private Long chapterId;

    /** 模块 ID，如 CH2-003（与 V2 文档一致，SC-001）。 */
    @TableField("module_id")
    private String moduleId;

    private String name;

    /** 执行器标识，如 eoq、beer-game（R-11：Spring 注入 Map 按此装配）。 */
    @TableField("engine_key")
    private String engineKey;

    /** intro / basic / advanced。 */
    private String difficulty;

    @TableField("class_hours")
    private Integer classHours;

    /** 是否角色扮演（FR-003/FR-014）。 */
    @TableField("is_role_play")
    private Boolean isRolePlay;

    private String concept;

    private String description;

    /** 依赖模块 ID 数组 JSON，如 ["CH2-003"]（FR-013）。 */
    private String deps;

    /** 输入参数定义数组 JSON（结构见 data-model.md）。 */
    private String params;

    /** 输出指标定义数组 JSON。 */
    private String outputs;

    /** 约束表达式数组 JSON（FR-005，expression 仅作展示与提示文案，R-11）。 */
    private String constraints;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
