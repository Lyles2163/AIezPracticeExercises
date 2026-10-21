package org.leon.aimodule.rag.web;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.InfoResponse;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import org.leon.commonjwt.pojo.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@RestController
@RequestMapping("/ai/kb")
public class KbPingController {

    private final ElasticsearchClient client;

    public KbPingController(ElasticsearchClient client) {
        this.client = client;
    }

    @GetMapping("/ping")
    public Result<Map<String, Object>> ping() throws IOException {
        InfoResponse info = client.info();
        GetAliasResponse aliasResponse = client.indices().getAlias(a -> a
                .index("*")
                .ignoreUnavailable(true)
                .allowNoIndices(true));

        TreeSet<String> aliases = new TreeSet<>();
        aliasResponse.aliases().values()
                .forEach(indexAliases -> aliases.addAll(indexAliases.aliases().keySet()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("clusterName", info.clusterName());
        data.put("version", info.version().number());
        data.put("aliases", List.copyOf(aliases));
        return Result.success(data);
    }
}
