package org.leon.practicemodule.oj.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提交入口的守门逻辑：参数/语言/题目状态任一不合法都必须 40000/40400/40900 且不投递消息。
 * 重点是"坏请求绝不能进队列"——进了队列就得等判题机发现，反馈延迟还浪费资源。
 */
class OjServiceTest {

    private final OjProblemMapper problemMapper = mock(OjProblemMapper.class);
    private final OjTestcaseMapper testcaseMapper = mock(OjTestcaseMapper.class);
    private final OjSubmissionMapper submissionMapper = mock(OjSubmissionMapper.class);
    private final OjJudgePublisher publisher = mock(OjJudgePublisher.class);
    private final OjService service =
            new OjService(problemMapper, testcaseMapper, submissionMapper, publisher, 100);

    @Test
    @DisplayName("代码为空 -> 40000，不落库不投递")
    void rejectsBlankCode() {
        PracticeException ex = assertThrows(PracticeException.class,
                () -> service.submit(1L, req(1L, "java", "  ")));

        assertEquals(PracticeErrorCode.BAD_REQUEST, ex.errorCode());
        // BaseMapper 的 insert 有 insert(T) 和 insert(Collection<T>) 两个重载，
        // 无类型实参的 any() 会被判为引用不明确，这里显式给类型
        verify(submissionMapper, never()).insert(org.mockito.ArgumentMatchers.<OjSubmission>any());
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("不支持的语言 -> 40000")
    void rejectsUnknownLanguage() {
        PracticeException ex = assertThrows(PracticeException.class,
                () -> service.submit(1L, req(1L, "rust", "fn main(){}")));

        assertEquals(PracticeErrorCode.BAD_REQUEST, ex.errorCode());
    }

    @Test
    @DisplayName("题目已下架 -> 40900")
    void rejectsDisabledProblem() {
        OjProblem problem = new OjProblem();
        problem.setId(9L);
        problem.setStatus(0);
        when(problemMapper.selectById(9L)).thenReturn(problem);

        PracticeException ex = assertThrows(PracticeException.class,
                () -> service.submit(1L, req(9L, "java", "class Main{}")));

        assertEquals(PracticeErrorCode.CONFLICT, ex.errorCode());
    }

    @Test
    @DisplayName("题目没有用例 -> 40900")
    void rejectsProblemWithoutTestcases() {
        OjProblem problem = new OjProblem();
        problem.setId(9L);
        problem.setStatus(1);
        when(problemMapper.selectById(9L)).thenReturn(problem);
        when(testcaseMapper.selectByProblemId(9L)).thenReturn(List.of());

        PracticeException ex = assertThrows(PracticeException.class,
                () -> service.submit(1L, req(9L, "java", "class Main{}")));

        assertEquals(PracticeErrorCode.CONFLICT, ex.errorCode());
    }

    @Test
    @DisplayName("正常提交 -> 落库后立刻投递，返回 submissionId")
    void publishesAfterInsert() {
        OjProblem problem = new OjProblem();
        problem.setId(9L);
        problem.setStatus(1);
        when(problemMapper.selectById(9L)).thenReturn(problem);
        when(testcaseMapper.selectByProblemId(9L)).thenReturn(List.of(new OjTestcase()));
        // 模拟数据库回填自增主键
        // 模拟数据库回填自增主键。先赋给有类型的变量：
        // BaseMapper 同时有 insert(T) 和 insert(Collection<T>)，直接内联 any() 会被判为引用不明确。
        OjSubmission insertArg = any(OjSubmission.class);
        when(submissionMapper.insert(insertArg)).thenAnswer(inv -> {
            inv.getArgument(0, OjSubmission.class).setId(42L);
            return 1;
        });

        var vo = service.submit(7L, req(9L, "CPP", "int main(){}"));

        assertEquals(42L, vo.submissionId());
        verify(publisher).publish(42L);
    }

    @Test
    @DisplayName("查看别人的提交 -> 40300")
    void rejectsForeignSubmission() {
        OjSubmission submission = new OjSubmission();
        submission.setId(5L);
        submission.setUserId(2L);
        when(submissionMapper.selectById(5L)).thenReturn(submission);

        PracticeException ex = assertThrows(PracticeException.class,
                () -> service.getSubmission(1L, 5L));

        assertEquals(PracticeErrorCode.FORBIDDEN, ex.errorCode());
    }

    @Test
    @DisplayName("题目详情带提交统计：通过率 = AC 次数 / 提交次数，两位小数")
    void problemDetailCarriesAcceptStats() {
        OjProblem problem = new OjProblem();
        problem.setId(9L);
        problem.setTitle("A+B");
        problem.setStatus(1);
        when(problemMapper.selectById(9L)).thenReturn(problem);
        when(testcaseMapper.selectSamples(9L)).thenReturn(List.of());
        when(submissionMapper.countByProblemId(9L)).thenReturn(8L);
        when(submissionMapper.countAcceptedByProblemId(9L)).thenReturn(3L);

        var vo = service.getProblem(9L);

        assertEquals(8L, vo.submitCount());
        assertEquals(3L, vo.acceptedCount());
        // 3 / 8 = 0.375 -> 0.38；与列表 SQL 的 ROUND(..., 2) 同口径
        assertEquals(0.38, vo.acceptRate());
        // 代码长度限制直接透出配置值，前端要靠它显示"代码长度限制"
        assertEquals(100, vo.maxSourceBytes());
    }

    @Test
    @DisplayName("没人提交过时通过率是 0 而不是 NaN")
    void acceptRateIsZeroWhenNoSubmission() {
        OjProblem problem = new OjProblem();
        problem.setId(9L);
        problem.setStatus(1);
        when(problemMapper.selectById(9L)).thenReturn(problem);
        when(testcaseMapper.selectSamples(9L)).thenReturn(List.of());

        var vo = service.getProblem(9L);

        assertEquals(0L, vo.submitCount());
        assertEquals(0.0, vo.acceptRate());
    }

    private static OjSubmitReq req(Long problemId, String language, String code) {
        OjSubmitReq req = new OjSubmitReq();
        req.setProblemId(problemId);
        req.setLanguage(language);
        req.setSourceCode(code);
        return req;
    }
}
