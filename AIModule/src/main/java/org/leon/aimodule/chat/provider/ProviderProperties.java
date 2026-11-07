package org.leon.aimodule.chat.provider;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "chat.provider")
public class ProviderProperties {
    /** 本地自建网关常是 http，默认关闭；生产环境不要打开。 */
    private boolean allowHttp = false;
    /**
     * 主密钥：只从环境变量/Nacos 注入，绝不落库。
     * 留空时加解密一律失败，供应商功能整体不可用，而不是退化成明文存储。
     */
    private String encryptionKey = "";
    /** 测试连接的整体超时；上游迟迟不回时不能让设置页一直转圈。 */
    private int probeTimeoutSeconds = 15;
    /** 每个用户最多几个供应商，挡住刷表。 */
    private int maxProvidersPerUser = 10;
    /** 单个供应商最多几个模型。 */
    private int maxModelsPerProvider = 30;
}
