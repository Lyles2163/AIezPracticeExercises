package org.leon.aimodule.tool;

/**
 * AI 可调用的工具（function calling）。
 *
 * 每个实现对应一个给模型声明的 function：
 * - name() 是工具名（模型用它来决定调哪个），全小写英文、用下划线分词；
 * - description() 是给模型看的说明，要讲清"什么时候该调"；
 * - parametersSchema() 是 JSON Schema 字符串，描述参数形状（OpenAI 工具调用规范）；
 * - execute(argumentsJson) 拿到模型生成的参数 JSON 后真的去干活，返回值会作为
 *   "tool 执行结果"再发回模型，让它基于结果继续回答。
 *
 * 新增工具的步骤：
 * 1. 写一个实现类，加 @Component；
 * 2. 它会被 {@code ToolRegistry} 自动收集；
 * 3. 模型就能在对话里调用它，不需要改 ChatStreamService。
 */
public interface ChatTool {

    /** 工具名，必须全小写英文，模型调用时用这个名字匹配。 */
    String name();

    /** 给模型看的用途说明，越具体越能减少误调用。 */
    String description();

    /** 参数的 JSON Schema（type=object，properties 里列每个参数）。 */
    String parametersSchema();

    /**
     * 执行工具。
     *
     * @param argumentsJson 模型生成的参数 JSON 字符串（可能为空串或不合法，实现方要容错）
     * @param userName      当前聊天用户的用户名（查用户相关数据用）
     * @return 工具执行结果的字符串，会原样塞回给模型；建议返回结构化 JSON 方便模型理解
     */
    String execute(String argumentsJson, String userName);
}
