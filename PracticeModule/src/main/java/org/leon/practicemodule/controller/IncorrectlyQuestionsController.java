package org.leon.practicemodule.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.practicemodule.mapper.LatestAnswerRow;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.QbUserAnswerRecordMapper;
import org.leon.practicemodule.mapper.QbUserWrongMapper;
import org.leon.practicemodule.mapper.WrongDetailRow;
import org.leon.practicemodule.pojo.QbQuestionOption;
import org.leon.practicemodule.pojo.vo.WrongQuestionVO;
import org.leon.practicemodule.service.LatestAnswers;
import org.leon.practicemodule.service.PracticeMasker;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 给 AI 调用的错题查询接口。
 *
 * 与前端错题本 {@code PracticeWrongController}（/practice/wrong）的差别：
 * - 那条是浏览器分页卡片，只给题干摘要，绝不泄漏答案真值（spec 5.4）；
 * - 这条是给 AI 答疑用的，返回题干、选项、我的上次作答、正确答案和解析，AI 才能基于真实错题讲解。
 *
 * 身份仍走 {@code X-User-Name}（由网关注入，或调用方显式带），绝不接受请求参数传 userId——那是越权入口。
 */
@RestController
@RequestMapping("/incorrectlyQuestions")
public class IncorrectlyQuestionsController {

    /** AI 一次最多拿这么多道错题，避免一次把上百道题塞进模型上下文撑爆 token。 */
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final QbUserWrongMapper wrongMapper;
    private final QbQuestionOptionMapper optionMapper;
    private final QbUserAnswerRecordMapper recordMapper;
    private final QbSubjectMapper subjectMapper;
    private final UserIdentityService userIdentityService;

    public IncorrectlyQuestionsController(QbUserWrongMapper wrongMapper,
                                          QbQuestionOptionMapper optionMapper,
                                          QbUserAnswerRecordMapper recordMapper,
                                          QbSubjectMapper subjectMapper,
                                          UserIdentityService userIdentityService) {
        this.wrongMapper = wrongMapper;
        this.optionMapper = optionMapper;
        this.recordMapper = recordMapper;
        this.subjectMapper = subjectMapper;
        this.userIdentityService = userIdentityService;
    }

    /**
     * 查询当前用户的未掌握错题（AI 答疑用）。
     *
     * 身份优先走 {@code X-User-Id}（网关注入，可信），没有时回退到 {@code X-User-Name} 查库换 userId。
     * 绝不接受请求参数传 userId——那是越权入口。
     *
     * @param userId    X-User-Id 请求头，由网关注入；内部 Feign 调用时也可传
     * @param userName  X-User-Name 请求头，X-User-Id 缺失时的回退
     * @param subjectId 可选，按科目过滤；不传则跨科目
     * @param limit     可选，返回条数，默认 10、上限 50
     */
    @GetMapping("/list")
    public Result<List<WrongQuestionVO>> list(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                              @RequestHeader(value = "X-User-Name", required = false) String userName,
                                              @RequestParam(required = false) Long subjectId,
                                              @RequestParam(required = false) Integer limit) {
        Long uid = userId != null
                ? userIdentityService.resolveUserIdFromHeader(userId)
                : userIdentityService.resolveUserId(userName);

        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1) {
            size = DEFAULT_LIMIT;
        }
        if (size > MAX_LIMIT) {
            size = MAX_LIMIT;
        }

        List<WrongDetailRow> rows = wrongMapper.selectWrongDetails(uid, subjectId, size);
        if (rows.isEmpty()) {
            return Result.success(List.of());
        }

        List<Long> questionIds = rows.stream().map(WrongDetailRow::questionId).toList();
        Map<Long, LatestAnswerRow> latest = LatestAnswers.byQuestion(recordMapper, uid, questionIds);
        // subjectId -> name 缓存：跨科目时多题同科目，别每条都查一次
        Map<Long, String> subjectNames = new HashMap<>();

        List<WrongQuestionVO> items = new ArrayList<>(rows.size());
        for (WrongDetailRow row : rows) {
            WrongQuestionVO vo = new WrongQuestionVO();
            vo.setQuestionId(row.questionId());
            vo.setSubjectId(row.subjectId());
            vo.setSubjectName(subjectNames.computeIfAbsent(row.subjectId(), subjectMapper::selectNameById));
            vo.setType(row.type());
            vo.setDifficulty(row.difficulty());
            vo.setTitle(row.title());
            vo.setTags(PracticeMasker.parseTags(row.tagsJson()));
            vo.setWrongCount(row.wrongCount());
            vo.setLastWrongAt(row.lastWrongAt() == null ? null : row.lastWrongAt().getTime());
            vo.setStatus(row.status());
            vo.setAnswerJson(row.answerJson());
            vo.setAnalysis(row.analysis());

            LatestAnswerRow last = latest.get(row.questionId());
            vo.setLastUserAnswerJson(last == null ? null : last.userAnswerJson());

            // 只给单选/多选带选项；判断/填空/简答没有选项行
            if (row.type() != null && (row.type() == 1 || row.type() == 2)) {
                vo.setOptions(toOptions(optionMapper.selectDisplayOptions(row.questionId())));
            } else {
                vo.setOptions(List.of());
            }
            items.add(vo);
        }
        return Result.success(items);
    }

    private static List<WrongQuestionVO.Option> toOptions(List<QbQuestionOption> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        List<WrongQuestionVO.Option> out = new ArrayList<>(options.size());
        for (QbQuestionOption o : options) {
            WrongQuestionVO.Option opt = new WrongQuestionVO.Option();
            opt.setOptionKey(o.getOptionKey());
            opt.setContent(o.getContent());
            out.add(opt);
        }
        return out;
    }
}
