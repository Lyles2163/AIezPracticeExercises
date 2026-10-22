package org.leon.aimodule.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import org.leon.aimodule.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class IndexRegistry {

    private final ElasticsearchClient client;
    private final RagProperties props;

    public IndexRegistry(ElasticsearchClient client, RagProperties props) {
        this.client = client;
        this.props = props;
    }

    public void ensureKbIndex() throws IOException {
        ensure(props.getIndex().getKbAlias(), props.getIndex().getKbPhysical(),
                IndexMappings.kb(props.getAnalyzer()));
    }

    public void ensureQuestionIndex() throws IOException {
        ensure(props.getIndex().getQuestionAlias(), props.getIndex().getQuestionPhysical(),
                IndexMappings.question(props.getAnalyzer()));
    }

    private void ensure(String alias, String physical, MappingSpec spec) throws IOException {
        if (client.indices().existsAlias(a -> a.name(alias)).value()) {
            return;
        }
        if (client.indices().exists(e -> e.index(physical)).value()) {
            client.indices().updateAliases(u -> u.actions(
                    Action.of(a -> a.add(add -> add.index(physical).alias(alias)))));
            return;
        }
        client.indices().create(c -> c
                .index(physical)
                .aliases(alias, a -> a)
                .settings(s -> s.withJson(new StringReader(spec.settings())))
                .mappings(m -> m.withJson(new StringReader(spec.properties()))));
    }

    /** 原子切别名：reindex 完成后唯一动作 */
    public void swapAlias(String alias, String newPhysical) throws IOException {
        String oldPhysical = resolvePhysical(alias);
        client.indices().updateAliases(u -> u.actions(
                Action.of(a -> a.remove(r -> {
                    r.index(oldPhysical).alias(alias);
                    return r;
                })),
                Action.of(a -> a.add(add -> add.index(newPhysical).alias(alias)))));
    }

    public Map<String, Long> stats() throws IOException {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String alias : new String[]{props.getIndex().getKbAlias(), props.getIndex().getQuestionAlias()}) {
            if (client.indices().existsAlias(a -> a.name(alias)).value()) {
                result.put(alias, client.count(c -> c.index(alias)).count());
            }
        }
        return result;
    }

    public void refresh(String alias) throws IOException {
        client.indices().refresh(r -> r.index(alias));
    }

    public void deleteIndex(String physical) throws IOException {
        if (client.indices().exists(e -> e.index(physical)).value()) {
            client.indices().delete(d -> d.index(physical));
        }
    }

    private String resolvePhysical(String alias) throws IOException {
        var response = client.indices().getAlias(a -> a.name(alias));
        return response.aliases().keySet().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("别名不存在: " + alias));
    }
}
