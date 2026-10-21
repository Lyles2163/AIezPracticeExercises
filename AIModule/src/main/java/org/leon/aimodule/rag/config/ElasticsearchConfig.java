package org.leon.aimodule.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.ElasticsearchTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {

    @Bean
    public ElasticsearchClient elasticsearchClient(RagProperties props) throws Exception {
        // 直接构造完整客户端，不使用 Boot 的 ElasticsearchClientAutoConfiguration：
        // 它依赖 8.x 的 RestClient，而 9.5.4 的传输层是 rest5_client。
        return EsClientFactory.create(props.getElasticsearch());
    }

    @Bean
    public ElasticsearchTransport elasticsearchTransport(ElasticsearchClient client) {
        return client._transport();
    }
}
