package org.leon.aimodule.rag.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Token 计数与按 token 切分的工具类。
 * <p>
 * 基于 jtokkit 的 CL100K_BASE 编码（OpenAI 早期模型使用的分词方案）对文本进行
 * token 级别的统计、截尾与硬切，供 RAG 切片时控制每个 chunk 的 token 预算。
 */
@Component
public class TokenCounter {

    /** 全局共享的编码器，Encoding 线程安全，可复用于所有计数/切分操作 */
    private final Encoding encoding = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    /**
     * 统计文本的 token 数量。
     *
     * @param text 待统计文本，为 null 或空白时按 0 计
     * @return token 数量
     */
    public int count(String text) {
        return text == null || text.isBlank() ? 0 : encoding.countTokens(text);
    }

    /**
     * 取末尾 maxTokens 个 token 对应的文本，用作切片重叠。
     * <p>
     * 当整段文本的 token 数不超过预算时原样返回，否则仅保留尾部一段，
     * 以便与下一个 chunk 形成重叠上下文。
     */
    public String tail(String text, int maxTokens) {
        // 先将文本编码为 token id 序列，再按尾部区间截取
        IntArrayList ids = encoding.encode(text);
        if (ids.size() <= maxTokens) {
            return text;
        }
        // 复制末尾 maxTokens 个 token id 后解码回文本
        IntArrayList tail = new IntArrayList(maxTokens);
        for (int i = ids.size() - maxTokens; i < ids.size(); i++) {
            tail.add(ids.get(i));
        }
        return encoding.decode(tail);
    }

    /**
     * 单句超过预算时的最后手段：按 token 硬切。
     * <p>
     * 以 maxTokens 为步长将 token id 序列分段解码，保证每段不超过预算；
     * 由于是硬切，可能在词中间断开，仅在无法按句/段切分时使用。
     */
    public List<String> hardSplit(String text, int maxTokens) {
        IntArrayList ids = encoding.encode(text);
        List<String> parts = new ArrayList<>();
        // 按 maxTokens 步长遍历，逐段截取并解码
        for (int from = 0; from < ids.size(); from += maxTokens) {
            int to = Math.min(from + maxTokens, ids.size());
            IntArrayList part = new IntArrayList(to - from);
            for (int i = from; i < to; i++) {
                part.add(ids.get(i));
            }
            parts.add(encoding.decode(part));
        }
        return parts;
    }
}
