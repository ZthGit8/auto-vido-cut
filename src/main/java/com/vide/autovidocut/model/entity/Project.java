package com.vide.autovidocut.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vide.autovidocut.model.enums.ProjectStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("project")
public class Project {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String name;

    @TableField("promotion_goal")
    private String promotionGoal;

    private ProjectStatus status;

    @TableField("script_json")
    private String scriptJson;

    @TableField("output_video_path")
    private String outputVideoPath;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}