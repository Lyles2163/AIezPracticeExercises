package org.leon.aimodule.tool;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 收集所有 {@link ChatTool} 实现，按 name() 建索引。
 *
 * 是 ChatStreamService 发 tools 列表给模型、以及按 name 找到实现去执行的唯一入口。
 * 新增工具只要写实现 + @Component，这里不用改。
 */
@Component
public class ToolRegistry {

    private final Map<String, ChatTool> toolsByName = new HashMap<>();

    public ToolRegistry(List<ChatTool> tools) {
        for (ChatTool tool : tools) {
            toolsByName.put(tool.name(), tool);
        }
    }

    /** 按名字找工具，找不到返回 null（调用方据此决定要不要把"未知工具"回给模型）。 */
    public ChatTool find(String name) {
        return toolsByName.get(name);
    }

    /** 所有已注册的工具，用来拼成发给模型的 tools 数组。 */
    public List<ChatTool> all() {
        return List.copyOf(toolsByName.values());
    }

    public boolean isEmpty() {
        return toolsByName.isEmpty();
    }
}
