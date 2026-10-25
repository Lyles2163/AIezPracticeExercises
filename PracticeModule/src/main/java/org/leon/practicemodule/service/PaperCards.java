package org.leon.practicemodule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 卡片上的两类纯计算：徽章文案与进度数字（段 4 裁定三的实现点）。
 *
 * <p>{@link #progressPercent} 的舍入与 {@code SubjectServiceImpl.accuracy} 完全一致
 * （2 位 HALF_UP + 除零回 0.0），不为卡片另起一套数字风格——两处口径不同，
 * 前端就会在同一个页面上看到两种"半对半错"的百分比。
 */
@Slf4j
public final class PaperCards {

    private static final String[] CN_ORDINALS = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PaperCards() {
    }

    /** 顺序固定为 年 / 套 / 月 / 时长（spec 5.6 的 badges 例子就是这个顺序），缺字段就跳过 */
    public static List<String> badges(Integer examYear, Integer examMonth, Integer setNo, Integer durationMinutes) {
        List<String> out = new ArrayList<>(4);
        if (examYear != null) {
            out.add(examYear + "年");
        }
        if (setNo != null) {
            out.add("第" + ordinal(setNo) + "套");
        }
        if (examMonth != null) {
            out.add(examMonth + "月");
        }
        if (durationMinutes != null) {
            out.add(durationMinutes + "分钟");
        }
        return List.copyOf(out);
    }

    private static String ordinal(int setNo) {
        if (setNo >= 1 && setNo <= CN_ORDINALS.length) {
            return CN_ORDINALS[setNo - 1];
        }
        // 套次是 tinyint unsigned，理论上能到 255；中文序数表只覆盖到"十"，超出就用数字兜住，
        // 宁可文案丑一点，也不抛异常——卡片页不该因为一枚徽章整个报 500。
        return String.valueOf(setNo);
    }

    public static double progressPercent(int answeredCount, int questionCount) {
        if (questionCount <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(answeredCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(questionCount), 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * 草稿的已答题数 = {@code answered_json} 这个 map 的 key 数。
     *
     * <p>脏数据（不是 JSON、或不是对象）回 0 并打日志，不抛：读路径不能因为一行坏草稿
     * 让整页卡片报 400，处置与 {@code PracticeMasker.parseTags} 一致。
     * 值为 null 的键仍算"这题被答过"（草稿 map 的键集就是答过的题）。
     */
    public static int answeredJsonKeyCount(String answeredJson) {
        if (answeredJson == null || answeredJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode node = MAPPER.readTree(answeredJson);
            return node != null && node.isObject() ? node.size() : 0;
        } catch (Exception e) {
            log.warn("answered_json 解析失败，按已答 0 处理：{}", e.getMessage());
            return 0;
        }
    }
}
