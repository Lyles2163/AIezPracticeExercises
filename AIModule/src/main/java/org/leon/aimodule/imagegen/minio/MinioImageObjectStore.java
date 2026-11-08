package org.leon.aimodule.imagegen.minio;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.imagegen.config.ImageGenProperties;
import org.leon.aimodule.imagegen.exception.ImageGenException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * MinIO 适配。客户端与桶都是懒初始化：MinIO 没起、凭据没配时服务照常启动，
 * 只在真正生图/取图时给出一句可展示的中文错误 —— 启动期 fail-fast 会让
 * 一个可选功能把整个 AIModule 拖死。
 */
@Slf4j
@Component
public class MinioImageObjectStore implements ImageObjectStore {

    private static final String UNAVAILABLE = "图片存储不可用，请检查 MinIO 配置";

    private final ImageGenProperties properties;
    private volatile MinioClient client;
    private volatile boolean bucketReady;

    public MinioImageObjectStore(ImageGenProperties properties) {
        this.properties = properties;
    }

    @Override
    public String put(String userName, String contentType, byte[] data) {
        if (data == null || data.length == 0) {
            throw new ImageGenException("上游没有返回图片数据");
        }
        String objectKey = ImageObjectKeys.build(userName, contentType);
        try {
            MinioClient minio = ready();
            minio.putObject(PutObjectArgs.builder()
                    .bucket(properties.getMinio().getBucket())
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build());
            return objectKey;
        } catch (ImageGenException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[imagegen] 写入 MinIO 失败 objectKey={} err={}", objectKey, e.toString());
            throw new ImageGenException(UNAVAILABLE, e);
        }
    }

    @Override
    public StoredImage open(String objectKey) {
        String bucket = properties.getMinio().getBucket();
        try {
            MinioClient minio = ready();
            StatObjectResponse stat = minio.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
            GetObjectResponse stream = minio.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
            return new StoredImage(stream, stat.size(), stat.contentType());
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if (code != null && code.toLowerCase(Locale.ROOT).contains("nosuchkey")) {
                throw new ImageGenException("图片不存在");
            }
            log.warn("[imagegen] 读取 MinIO 失败 objectKey={} code={}", objectKey, code);
            throw new ImageGenException(UNAVAILABLE, e);
        } catch (Exception e) {
            log.warn("[imagegen] 读取 MinIO 失败 objectKey={} err={}", objectKey, e.toString());
            throw new ImageGenException(UNAVAILABLE, e);
        }
    }

    private MinioClient ready() {
        ImageGenProperties.Minio cfg = properties.getMinio();
        if (!cfg.isEnabled()) {
            throw new ImageGenException("生图存储未启用（imagegen.minio.enabled=false）");
        }
        if (isBlank(cfg.getEndpoint()) || isBlank(cfg.getAccessKey()) || isBlank(cfg.getSecretKey())) {
            throw new ImageGenException("图片存储不可用，请检查 MinIO 配置");
        }
        MinioClient minio = client;
        if (minio == null) {
            synchronized (this) {
                minio = client;
                if (minio == null) {
                    minio = MinioClient.builder()
                            .endpoint(cfg.getEndpoint().trim())
                            .credentials(cfg.getAccessKey().trim(), cfg.getSecretKey().trim())
                            .build();
                    client = minio;
                }
            }
        }
        ensureBucket(minio, cfg.getBucket());
        return minio;
    }

    private void ensureBucket(MinioClient minio, String bucket) {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            try {
                boolean exists = minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
                if (!exists) {
                    minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("[imagegen] 已创建 MinIO 桶 {}", bucket);
                }
                bucketReady = true;
            } catch (Exception e) {
                log.warn("[imagegen] MinIO 桶检查失败 bucket={} err={}", bucket, e.toString());
                throw new ImageGenException(UNAVAILABLE, e);
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
