package org.leon.aimodule.demo;

import org.leon.aimodule.rag.search.PermissionFilters;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OrderTools {

    // 模拟数据库
    private static final Map<String, String> ORDERS = Map.of(
            "A1001", "已发货, 顺丰 SF1234567890",
            "A1002", "待支付, 30 分钟后自动取消"
    );

    @Tool(description = "根据订单号查询订单状态和物流信息") // 👈 核心
    public String getOrderStatus(@ToolParam(description = "订单号，格式如A1001") String orderId) {
        return ORDERS.getOrDefault(orderId, "订单号不存在");
    }


}