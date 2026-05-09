package com.videoai.vo;

import lombok.Data;

/**
 * STS临时凭证
 */
@Data
public class StsTokenVO {

    private String accessKeyId;
    private String accessKeySecret;
    private String securityToken;
    private String expiration;
    private String region;
    private String bucket;
    private String endpoint;
}
