package com.videoai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * AI对话记录
 */
@Data
@TableName("t_ai_conversation")
public class AiConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话ID */
    private String conversationId;

    /** 关联视频ID (可选) */
    private Long videoId;

    /** 用户ID */
    private Long userId;

    /** 用户消息 */
    private String userMessage;

    /** AI回复 */
    private String aiResponse;

    /** Function Calling调用详情 (JSON) */
    private String functionCalls;

    /** 消耗的Token数 */
    private Integer tokenUsed;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
