package org.leon.aimodule.rag.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfFusionTest {

    private static final double DELTA = 1e-9;

    @Test
    @DisplayName("融合分按 1/(rankConstant+rank) 累加，两腿都出现的排前面")
    void sumsReciprocalRanks() {
        List<RrfFusion.FusedHit> fused = RrfFusion.fuse(
                List.of("A", "B", "C"), List.of("B", "A", "D"), 60, 10);

        assertEquals(List.of("A", "B", "C", "D"), fused.stream().map(RrfFusion.FusedHit::id).toList());
        assertEquals(1d / 61 + 1d / 62, fused.get(0).score(), DELTA);
        assertEquals(1, fused.get(0).rank());
        assertEquals(4, fused.get(3).rank());
        assertEquals(1d / 63, fused.get(3).score(), DELTA);
    }

    @Test
    @DisplayName("同分先比最优名次，再比 id，保证结果确定")
    void tieBreakIsDeterministic() {
        List<RrfFusion.FusedHit> fused = RrfFusion.fuse(
                List.of("X", "Y"), List.of("Y", "X"), 60, 10);

        assertEquals(1d / 61 + 1d / 62, fused.get(0).score(), DELTA);
        assertEquals(1d / 61 + 1d / 62, fused.get(1).score(), DELTA);
        assertEquals("X", fused.get(0).id(), "两腿名次互换后分数与最优名次都相同，按 id 定序");
        assertEquals("Y", fused.get(1).id());

        List<RrfFusion.FusedHit> sameRank = RrfFusion.fuse(
                List.of("D", "C"), List.of("C", "D"), 60, 10);
        assertEquals("C", sameRank.get(0).id(), "同分同名次时按 id 字典序");
    }

    @Test
    @DisplayName("limit 截断保留融合分最高的前 N 条")
    void limitsToTopK() {
        List<RrfFusion.FusedHit> fused = RrfFusion.fuse(
                List.of("A", "B", "C", "D"), List.of("D", "C"), 60, 2);

        assertEquals(2, fused.size());
        assertEquals(List.of("D", "C"), fused.stream().map(RrfFusion.FusedHit::id).toList());
    }

    @Test
    @DisplayName("任一腿为空不影响另一腿参与融合；两腿都空则返回空")
    void handlesEmptyLegs() {
        assertEquals(List.of("A"), RrfFusion.fuse(List.of("A"), List.of(), 60, 5)
                .stream().map(RrfFusion.FusedHit::id).toList());
        assertTrue(RrfFusion.fuse(List.of(), List.of(), 60, 5).isEmpty());
    }
}
