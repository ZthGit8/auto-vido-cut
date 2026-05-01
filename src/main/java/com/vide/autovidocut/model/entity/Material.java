package com.vide.autovidocut.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("material")
public class Material {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("project_id")
    private String projectId;

    @TableField("file_path")
    private String filePath;

    @TableField("file_name")
    private String fileName;

    @TableField("file_size")
    private Long fileSize;

    @TableField("media_type")
    private String mediaType;

    private Double duration;

    private Integer width;

    private Integer height;

    private String codec;

    @TableField("ai_description")
    private String aiDescription;

    @TableField("created_at")
    private LocalDateTime createdAt;
}