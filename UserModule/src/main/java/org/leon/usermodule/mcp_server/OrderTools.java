package org.leon.usermodule.mcp_server;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OrderTools {

    // 模拟数据库
    private static final Map<String, String> ORDERS = Map.of(
            "A88888", "已发货, 顺丰 SF1234567890",
            "A99999", "待支付, 30 分钟后自动取消"
    );

    @Tool(description = "根据订单号查询订单状态和物流信息")
    public String getOrderStatus(@ToolParam(description = "订单号，格式如A88888") String orderId) {
        return ORDERS.getOrDefault(orderId, "订单号不存在");
    }


}