package org.leon.aimodule.rag.search;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.leon.aimodule.rag.exception.RagException;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限过滤的唯一 Java 出处。调用方必须在 BM25 与 kNN 两条腿上各用一次；
 * 任何一条腿漏掉 filter，泄露的就是乙的私人讲义。
 * <p>
 * 缺省语义：scopes 为 null/空时等于 official+personal（仍按 owner 过滤），
 * 不允许产出"无权限过滤"的查询。
 */
public final class PermissionFilters {

    private static final List<String> DEFAULT_SCOPES = List.of("official", "personal");

    private PermissionFilters() {
    }

    public static List<Query> build(List<String> scopes, String ownerName, Long subjectId) {
        List<String> effective = (scopes == null || scopes.isEmpty()) ? DEFAULT_SCOPES : scopes;
        List<Query> filters = new ArrayList<>();
        filters.add(scopeFilter(effective, ownerName));
        if (subjectId != null) {
            filters.add(subjectFilter(subjectId));
        }
        return filters;
    }

    private static Query scopeFilter(List<String> scopes, String ownerName) {
        boolean official = scopes.contains("official");
        boolean personal = scopes.contains("personal");
        if (personal && (ownerName == null || ownerName.isBlank())) {
            throw new RagException("个人库检索需要登录身份");
        }
        if (!personal) {
            return term("scope", "official");
        }
        if (!official) {
            return personalFilter(ownerName);
        }
        return Query.of(q -> q.bool(b -> b.minimumShouldMatch("1")
                .should(term("scope", "official"), personalFilter(ownerName))));
    }

    private static Query personalFilter(String ownerName) {
        return Query.of(q -> q.bool(b -> b.filter(
                term("scope", "personal"),
                term("owner_name", ownerName))));
    }

    private static Query subjectFilter(Long subjectId) {
        if (subjectId == 0) {
            return term("subject_id", 0L);
        }
        return Query.of(q -> q.bool(b -> b.minimumShouldMatch("1")
                .should(term("subject_id", subjectId), term("subject_id", 0L))));
    }

    private static Query term(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    private static Query term(String field, long value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }
}
