package org.leon.practicemodule.oj.pojo.mq;

import lombok.Data;

import java.io.Serializable;

/**
 * 判题任务消息：只投递 submissionId，源码由消费者回库读取。
 * 源码可能几十 KB，而数据库才是事实之源；MQ 消息保持小而稳。
 */
@Data
public class OjJudgeEvent implements Serializable {

    private Long submissionId;
}
