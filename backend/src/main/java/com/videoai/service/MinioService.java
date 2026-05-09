package com.videoai.service;

import com.videoai.config.MinioConfig;
import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.DeleteObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * MinIO对象存储服务 (替代阿里云OSS)
 * 提供：预签名URL生成、分片上传组合、bucket管理
 *
 * 分片上传方案：
 *   1. 前端获取每个分片的预签名URL，直传MinIO为临时chunk对象
 *   2. 全部上传完成后，服务端通过 composeObject 将chunks合并为最终文件
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioService {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    @PostConstruct
    public void initBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(minioConfig.getBucketName()).build());
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(minioConfig.getBucketName()).build());
                log.info("MinIO bucket创建成功: {}", minioConfig.getBucketName());
            } else {
                log.info("MinIO bucket已存在: {}", minioConfig.getBucketName());
            }
        } catch (Exception e) {
            log.warn("MinIO bucket初始化失败(服务可能未启动): {}", e.getMessage());
        }
    }

    /**
     * 生成预签名上传URL (替代STS临时凭证)
     * 前端直接PUT文件到此URL，无需经过业务服务器
     */
    public String getPresignedUploadUrl(String objectKey, int expiry) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(minioConfig.getBucketName())
                            .object(objectKey)
                            .expiry(expiry, TimeUnit.SECONDS)
                            .build());
        } catch (Exception e) {
            log.error("生成预签名上传URL失败: objectKey={}", objectKey, e);
            throw new RuntimeException("生成上传凭证失败", e);
        }
    }

    public String getPresignedUploadUrl(String objectKey) {
        return getPresignedUploadUrl(objectKey, minioConfig.getPresignedExpiry());
    }

    /**
     * 生成分片的预签名上传URL
     * 分片会被存储为临时对象: {taskDir}/chunks/{index}
     */
    public String getPresignedChunkUploadUrl(String taskId, String filename, int chunkIndex) {
        String chunkKey = "videos/" + taskId + "/chunks/" + chunkIndex;
        return getPresignedUploadUrl(chunkKey);
    }

    /**
     * 将所有分片合并为最终文件 (composeObject)
     * MinIO的composeObject可以在服务端零拷贝合并，无需下载分片
     *
     * @param taskId   任务ID
     * @param filename 文件名
     * @param totalChunks 总分片数
     * @return 最终文件的对象键
     */
    public String composeChunks(String taskId, String filename, int totalChunks) {
        String finalKey = "videos/" + taskId + "/" + filename;

        try {
            // 构建源对象列表
            List<ComposeSource> sources = IntStream.range(0, totalChunks)
                    .mapToObj(i -> {
                        try {
                            return ComposeSource.builder()
                                    .bucket(minioConfig.getBucketName())
                                    .object("videos/" + taskId + "/chunks/" + i)
                                    .build();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList());

            // 合并分片
            minioClient.composeObject(
                    ComposeObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(finalKey)
                            .sources(sources)
                            .build());

            log.info("分片合并完成: taskId={}, finalKey={}", taskId, finalKey);

            // 删除临时分片对象
            deleteChunkObjects(taskId, totalChunks);

            return finalKey;

        } catch (Exception e) {
            log.error("分片合并失败: taskId={}", taskId, e);
            throw new RuntimeException("文件合并失败", e);
        }
    }

    /**
     * 删除临时分片对象
     */
    private void deleteChunkObjects(String taskId, int totalChunks) {
        try {
            List<DeleteObject> objects = IntStream.range(0, totalChunks)
                    .mapToObj(i -> new DeleteObject("videos/" + taskId + "/chunks/" + i))
                    .collect(Collectors.toList());
            // removeObjects 是惰性执行，必须遍历才会真正删除
            minioClient.removeObjects(
                    RemoveObjectsArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .objects(objects)
                            .build())
                    .forEach(result -> {
                        try {
                            result.get(); // 触发删除，捕获可能的错误
                        } catch (Exception e) {
                            log.warn("删除分片对象失败: taskId={}, error={}", taskId, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("清理临时分片失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    /**
     * 生成预签名下载URL
     */
    public String getPresignedDownloadUrl(String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(minioConfig.getBucketName())
                            .object(objectKey)
                            .expiry(minioConfig.getPresignedExpiry(), TimeUnit.SECONDS)
                            .build());
        } catch (Exception e) {
            log.error("生成预签名下载URL失败: objectKey={}", objectKey, e);
            throw new RuntimeException("生成下载URL失败", e);
        }
    }

    /**
     * 服务端直接上传对象 (用于转码后文件等场景)
     */
    public void uploadObject(String objectKey, InputStream inputStream, long size, String contentType) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(objectKey)
                            .stream(inputStream, size, -1)
                            .contentType(contentType)
                            .build());
            log.info("对象上传成功: objectKey={}", objectKey);
        } catch (Exception e) {
            log.error("对象上传失败: objectKey={}", objectKey, e);
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 获取对象的公开访问URL
     */
    public String getObjectUrl(String objectKey) {
        return minioConfig.getEndpoint() + "/" + minioConfig.getBucketName() + "/" + objectKey;
    }

    /**
     * 下载对象到本地文件
     */
    public void downloadObject(String objectKey, Path targetPath) {
        try {
            minioClient.downloadObject(
                    DownloadObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(objectKey)
                            .filename(targetPath.toString())
                            .build());
            log.info("对象下载成功: objectKey={}, target={}", objectKey, targetPath);
        } catch (Exception e) {
            log.error("对象下载失败: objectKey={}", objectKey, e);
            throw new RuntimeException("文件下载失败", e);
        }
    }

    /**
     * 删除对象
     */
    public void deleteObject(String objectKey) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(minioConfig.getBucketName())
                            .object(objectKey)
                            .build());
            log.info("对象删除成功: objectKey={}", objectKey);
        } catch (Exception e) {
            log.warn("对象删除失败: objectKey={}", objectKey, e.getMessage());
        }
    }
}
