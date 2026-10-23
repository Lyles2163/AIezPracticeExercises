package org.leon.aimodule.rag.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF 融合的纯函数实现：score(doc) = Σ 1 / (rankConstant + rank_leg)。
 * ES 原生 rrf retriever 需要 Platinum+ 许可，本机与生产都是 basic，故在 Java 侧融合。
 */
public final class RrfFusion {

    public record FusedHit(String id, double score, int rank) {
    }

    private RrfFusion() {
    }

    public static List<FusedHit> fuse(List<String> bm25Order, List<String> knnOrder,
                                      int rankConstant, int limit) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Integer> bestRank = new HashMap<>();
        accumulate(scores, bestRank, bm25Order, rankConstant);
        accumulate(scores, bestRank, knnOrder, rankConstant);

        List<FusedHit> fused = new ArrayList<>(scores.size());
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            String id = entry.getKey();
            fused.add(new FusedHit(id, entry.getValue(), bestRank.get(id)));
        }
        fused.sort(Comparator.comparingDouble(FusedHit::score).reversed()
                .thenComparingInt(FusedHit::rank)
                .thenComparing(FusedHit::id));

        List<FusedHit> limited = fused.size() > limit ? new ArrayList<>(fused.subList(0, limit)) : fused;
        List<FusedHit> ranked = new ArrayList<>(limited.size());
        for (int i = 0; i < limited.size(); i++) {
            FusedHit hit = limited.get(i);
            ranked.add(new FusedHit(hit.id(), hit.score(), i + 1));
        }
        return ranked;
    }

    private static void accumulate(Map<String, Double> scores, Map<String, Integer> bestRank,
                                   List<String> order, int rankConstant) {
        for (int i = 0; i < order.size(); i++) {
            String id = order.get(i);
            int rank = i + 1;
            scores.merge(id, 1d / (rankConstant + rank), Double::sum);
            bestRank.merge(id, rank, Math::min);
        }
    }
}
