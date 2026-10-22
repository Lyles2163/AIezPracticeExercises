package org.leon.aimodule.rag.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TokenCounter {

    private final Encoding encoding = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    public int count(String text) {
        return text == null || text.isBlank() ? 0 : encoding.countTokens(text);
    }

    /** 取末尾 maxTokens 个 token 对应的文本，用作切片重叠 */
    public String tail(String text, int maxTokens) {
        IntArrayList ids = encoding.encode(text);
        if (ids.size() <= maxTokens) {
            return text;
        }
        IntArrayList tail = new IntArrayList(maxTokens);
        for (int i = ids.size() - maxTokens; i < ids.size(); i++) {
            tail.add(ids.get(i));
        }
        return encoding.decode(tail);
    }

    /** 单句超过预算时的最后手段：按 token 硬切 */
    public List<String> hardSplit(String text, int maxTokens) {
        IntArrayList ids = encoding.encode(text);
        List<String> parts = new ArrayList<>();
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
