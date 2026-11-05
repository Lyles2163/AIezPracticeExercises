package org.leon.practicemodule.oj.service;

import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.oj.mapper.OjProblemMapper;
import org.leon.practicemodule.oj.mapper.OjSubmissionMapper;
import org.leon.practicemodule.oj.mapper.OjTestcaseMapper;
import org.leon.practicemodule.oj.mq.OjJudgePublisher;
import org.leon.practicemodule.oj.pojo.OjProblem;
import org.leon.practicemodule.oj.pojo.OjSubmission;
import org.leon.practicemodule.oj.pojo.OjSubmitReq;
import org.leon.practicemodule.oj.pojo.OjTestcase;
import org.leon.practicemodule.oj.pojo.vo.OjProblemItemVO;
import org.leon.practicemodule.oj.pojo.vo.OjProblemVO;
import org.leon.practicemodule.oj.pojo.vo.OjSubmissionVO;
import org.leon.practicemodule.oj.pojo.vo.OjSubmitVO;
import org.leon.practicemodule.oj.pojo.vo.OjTestcaseSampleVO;
import org.leon.practicemodule.oj.sandbox.JudgeLanguage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OJ（Online Judge）核心业务服务。
 * <p>
 * 职责：
 * <ul>
 *     <li>提交编排：参数校验 → 题目状态检查 → 落库 → 投递 MQ，接口立刻返回</li>
 *     <li>查询编排：提交记录查询、题目列表分页、题目详情（含样例）</li>
 * </ul>
 * <p>
 * 线程安全：本类为无状态 Spring Service，所有依赖均为线程安全的 Mapper/Publisher，可安全并发访问。
 *
 * @author PracticeModule
 */
@Service
public class OjService {

    /** 题目数据访问层，线程安全 */
    private final OjProblemMapper problemMapper;
    /** 测试用例数据访问层，线程安全 */
    private final OjTestcaseMapper testcaseMapper;
    /** 提交记录数据访问层，线程安全 */
    private final OjSubmissionMapper submissionMapper;
    /** MQ 消息发布者，用于异步判题，线程安全 */
    private final OjJudgePublisher publisher;
    /** 源码最大字节数，构造时注入，不可变 */
    private final int maxSourceBytes;

    /**
     * 构造器注入所有依赖。
     *
     * @param problemMapper    题目 Mapper
     * @param testcaseMapper   测试用例 Mapper
     * @param submissionMapper 提交记录 Mapper
     * @param publisher        判题 MQ 发布者
     * @param maxSourceBytes   源码最大字节数，读取配置 {@code oj.sandbox.max-source-bytes}，缺省 65536
     */
    public OjService(OjProblemMapper problemMapper,
                     OjTestcaseMapper testcaseMapper,
                     OjSubmissionMapper submissionMapper,
                     OjJudgePublisher publisher,
                     @Value("${oj.sandbox.max-source-bytes:65536}") int maxSourceBytes) {
        this.problemMapper = problemMapper;
        this.testcaseMapper = testcaseMapper;
        this.submissionMapper = submissionMapper;
        this.publisher = publisher;
        this.maxSourceBytes = maxSourceBytes;
    }

    /**
     * 提交代码。
     * <p>
     * 业务流程：
     * <ol>
     *     <li>参数校验（非空、长度、语言合法性）</li>
     *     <li>题目存在性与状态校验（必须为上架状态）</li>
     *     <li>测试用例配置校验（至少一条）</li>
     *     <li>创建提交记录并落库，状态默认为 PENDING</li>
     *     <li>投递 MQ 消息，异步触发判题</li>
     * </ol>
     *
     * @param userId 当前用户 ID
     * @param req    提交请求，包含题目 ID、源码、语言
     * @return 提交结果 VO，仅包含 submissionId
     * @throws PracticeException 参数不合法、题目不存在/已下架/无测试用例、代码超长
     */
    public OjSubmitVO submit(Long userId, OjSubmitReq req) {
        // 1. 基础参数校验
        if (req == null || req.getProblemId() == null) {
            throw badRequest("请求参数不合法");
        }
        if (req.getSourceCode() == null || req.getSourceCode().isBlank()) {
            throw badRequest("代码不能为空");
        }
        // 使用 UTF-8 编码计算字节长度，防止多字节字符导致误判
        if (req.getSourceCode().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxSourceBytes) {
            throw badRequest("代码长度超出限制");
        }

        // 2. 语言合法性校验
        JudgeLanguage language;
        try {
            language = JudgeLanguage.of(req.getLanguage());
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }

        // 3. 题目存在性与状态校验
        OjProblem problem = problemMapper.selectById(req.getProblemId());
        if (problem == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "题目不存在");
        }
        if (problem.getStatus() == null || problem.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "题目已下架");
        }
        // 4. 测试用例配置校验
        if (testcaseMapper.selectByProblemId(problem.getId()).isEmpty()) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "题目没有配置测试用例");
        }

        // 5. 构建提交记录并落库
        OjSubmission submission = new OjSubmission();
        submission.setUserId(userId);
        submission.setProblemId(problem.getId());
        submission.setLanguage(language.code());
        submission.setSourceCode(req.getSourceCode());
        submissionMapper.insert(submission);      // status 由数据库默认值 PENDING 给

        // 6. 投递 MQ，异步判题
        publisher.publish(submission.getId());
        return new OjSubmitVO(submission.getId());
    }

    /**
     * 查询指定提交记录详情。
     * <p>
     * 边界：仅允许提交者本人查询，防止越权访问。
     *
     * @param userId 当前用户 ID
     * @param id     提交记录 ID
     * @return 提交记录 VO
     * @throws PracticeException 记录不存在或无权访问
     */
    public OjSubmissionVO getSubmission(Long userId, Long id) {
        OjSubmission submission = submissionMapper.selectById(id);
        if (submission == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "提交记录不存在");
        }
        // 权限校验：仅提交者可查看
        if (!userId.equals(submission.getUserId())) {
            throw new PracticeException(PracticeErrorCode.FORBIDDEN, "无权查看该提交");
        }
        return OjSubmissionVO.from(submission);
    }

    /**
     * 分页查询题目列表（仅上架题目）。
     * <p>
     * 边界保护：
     * <ul>
     *     <li>page 最小为 1</li>
     *     <li>pageSize 范围为 [1, 100]，防止一次性拉取过多数据</li>
     * </ul>
     *
     * @param page     页码，从 1 开始
     * @param pageSize 每页条数，最大 100
     * @return 题目列表 VO
     */
    public List<OjProblemItemVO> listProblems(long page, long pageSize) {
        // 参数边界保护
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(pageSize, 1), 100);
        // 计算偏移量并查询
        return problemMapper.selectEnabledPage((safePage - 1) * safeSize, safeSize);
    }

    /**
     * 查询题目详情（含样例测试用例）。
     * <p>
     * 边界：题目必须存在且为上架状态，否则抛出异常。
     *
     * @param id 题目 ID
     * @return 题目详情 VO，包含样例输入输出
     * @throws PracticeException 题目不存在或已下架
     */
    public OjProblemVO getProblem(Long id) {
        OjProblem problem = problemMapper.selectById(id);
        if (problem == null) {
            throw new PracticeException(PracticeErrorCode.NOT_FOUND, "题目不存在");
        }
        if (problem.getStatus() == null || problem.getStatus() != 1) {
            throw new PracticeException(PracticeErrorCode.CONFLICT, "题目已下架");
        }
        // 查询样例测试用例（仅输入输出，不含评测数据）
        List<OjTestcaseSampleVO> samples = testcaseMapper.selectSamples(id).stream()
                .map(OjService::toSample)
                .toList();
        long submitCount = submissionMapper.countByProblemId(id);
        long acceptedCount = submissionMapper.countAcceptedByProblemId(id);
        return new OjProblemVO(problem.getId(), problem.getSubjectId(), problem.getTitle(),
                problem.getDescription(), problem.getTemplateCode(), problem.getDifficulty(),
                problem.getTimeLimitMs(), problem.getMemoryLimitMb(), maxSourceBytes,
                submitCount, acceptedCount, acceptanceRate(submitCount, acceptedCount), samples);
    }

    /**
     * 将测试用例实体转换为样例 VO。
     * <p>
     * 用途：仅暴露样例的输入输出，隐藏内部评测数据（如是否为主样例、权重等）。
     *
     * @param testcase 测试用例实体
     * @return 样例 VO
     */
    private static OjTestcaseSampleVO toSample(OjTestcase testcase) {
        return new OjTestcaseSampleVO(testcase.getSeq(), testcase.getInputData(),
                testcase.getExpectedOutput());
    }

    /**
     * 提交通过率（0~1，两位小数）。没有提交时返回 0 而不是 NaN —— 前端直接乘 100 展示，
     * NaN 会原样渲染成 "NaN%"。与列表 SQL 里 ROUND(..., 2) 的口径保持一致。
     */
    private static double acceptanceRate(long submitCount, long acceptedCount) {
        if (submitCount <= 0) {
            return 0.0;
        }
        return Math.round(acceptedCount * 100.0 / submitCount) / 100.0;
    }

    /**
     * 构造 BAD_REQUEST 类型的业务异常。
     * <p>
     * 使用场景：参数校验失败、格式不合法等客户端错误。
     *
     * @param message 错误信息
     * @return 业务异常实例
     */
    private static PracticeException badRequest(String message) {
        return new PracticeException(PracticeErrorCode.BAD_REQUEST, message);
    }
}
