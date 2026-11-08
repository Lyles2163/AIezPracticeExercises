package org.leon.aimodule.imagegen.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "imagegen")
public class ImageGenProperties {

    private Minio minio = new Minio();
    private Limits limits = new Limits();

    @Data
    public static class Minio {
        /** 关掉时生图接口直接拒绝，而不是在启动期把服务拖死。 */
        private boolean enabled = true;
        private String endpoint = "http://127.0.0.1:9000";
        private String accessKey = "";
        private String secretKey = "";
        private String bucket = "ai-images";
    }

    @Data
    public static class Limits {
        private int maxPromptChars = 2000;
        private int maxImagesPerRequest = 4;
        /** 单张图上限，挡住上游回一个离谱的大文件把内存打满。 */
        private int maxImageBytes = 20 * 1024 * 1024;
        /** 生图上游普遍 10-60s，别套用探活那 15s。 */
        private int requestTimeoutSeconds = 120;
    }
}
