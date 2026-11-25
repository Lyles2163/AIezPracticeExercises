package org.leon.practicemodule.manage.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.manage.mapper.MyQuestionManageMapper;
import org.leon.practicemodule.manage.mapper.QbImportBatchItemMapper;
import org.leon.practicemodule.manage.mapper.QbImportBatchMapper;
import org.leon.practicemodule.manage.parse.DocumentTextExtractor;
import org.leon.practicemodule.manage.parse.ImportTemplateGenerator;
import org.leon.practicemodule.manage.parse.ParseResult;
import org.leon.practicemodule.manage.parse.ParsedQuestion;
import org.leon.practicemodule.manage.parse.QuestionTextParser;
import org.leon.practicemodule.manage.pojo.ManageRequests;
import org.leon.practicemodule.manage.pojo.QbImportBatch;
import org.leon.practicemodule.manage.pojo.QbImportBatchItem;
import org.leon.practicemodule.manage.pojo.vo.ManageVOs;
import org.leon.practicemodule.mapper.QbPaperMapper;
import org.leon.practicemodule.mapper.QbQuestionMapper;
import org.leon.practicemodule.mapper.QbQuestionOptionMapper;
import org.leon.practicemodule.mapper.QbSubjectMapper;
import org.leon.practicemodule.mapper.StatCountRow;
import org.leon.practicemodule.pojo.QbPaper;
import org.leon.practicemodule.pojo.QbQuestion;
import org.leon.practicemodule.pojo.QbQuestionOption;
import org.leon.practicemodule.pojo.QbSubject;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.service.PaperCards;
import org.leon.practicemodule.service.PaperLoad;
import org.leon.practicemodule.service.PaperStructure;
import org.leon.practicemodule.service.PaperStructureParser;
import org.leon.practicemodule.service.PaperStructureValidator;
import org.leon.practicemodule.service.PracticeMasker;
import org.leon.practicemodule.service.PracticePages;
import org.leon.practicemodule.service.SubjectGuards;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 个人题库数据管理与文档导入服务。
 *
 * <p>核心边界：
 * <ul>
 *   <li>所有写操作严格限定在当前用户的个人科目（{@code owner_id = userId && owner_id != 1}），
 *       公共题库（{@code owner_id = 1}）绝对只读。</li>
 *   <li>文档上传同步提取、解析并事务写入数据库，支持可选一键打包成个人套卷（{@code qb_paper}）。</li>
 *   <li>单库本地事务（不加 @GlobalTransactional），与 PracticeModule 既定口径一致。</li>
 * </ul>
 */
@Service
public class PracticeManageService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<Integer, String> TYPE_NAMES = Map.of(
            1, "单选题",
            2, "多选题",
            3, "判断题",
            4, "填空题",
            5, "简答题"
    );

    private final QbSubjectMapper subjectMapper;
    private final QbQuestionMapper questionMapper;
    private final QbQuestionOptionMapper optionMapper;
    private final QbPaperMapper paperMapper;
    private final QbImportBatchMapper batchMapper;
    private final QbImportBatchItemMapper batchItemMapper;
    private final MyQuestionManageMapper manageMapper;
    private final DocumentTextExtractor extractor;
    private final QuestionTextParser textParser;
    private final ImportTemplateGenerator templateGenerator;
    private final PaperPackager paperPackager;
    private final QuestionPayloadValidator payloadValidator;

    public PracticeManageService(QbSubjectMapper subjectMapper,
                                 QbQuestionMapper questionMapper,
                                 QbQuestionOptionMapper optionMapper,
                                 QbPaperMapper paperMapper,
                                 QbImportBatchMapper batchMapper,
                                 QbImportBatchItemMapper batchItemMapper,
                                 MyQuestionManageMapper manageMapper,
                                 DocumentTextExtractor extractor,
                                 QuestionTextParser textParser,
                                 ImportTemplateGenerator templateGenerator,
                                 PaperPackager paperPackager,
                                 QuestionPayloadValidator payloadValidator) {
        this.subjectMapper = subjectMapper;
        this.questionMapper = questionMapper;
        this.optionMapper = optionMapper;
        this.paperMapper = paperMapper;
        this.batchMapper = batchMapper;
        this.batchItemMapper = batchItemMapper;
        this.manageMapper = manageMapper;
        this.extractor = extractor;
        this.textParser = textParser;
        this.templateGenerator = templateGenerator;
        this.paperPackager = paperPackager;
        this.payloadValidator = payloadValidator;
    }

    // ==================== 1. 个人科目管理 ====================

    public List<ManageVOs.MySubjectVO> listMySubjects(Long userId) {
        ensurePersonalUser(userId);
        List<QbSubject> subjects = subjectMapper.selectPersonalSubjects(userId);
        if (subjects.isEmpty()) {
            return List.of();
        }
        List<Long> ids = subjects.stream().map(QbSubject::getId).toList();
        Map<Long, Long> qCounts = toCountMap(subjectMapper.countAllQuestionRows(ids));
        Map<Long, Long> pCounts = toCountMap(subjectMapper.countPersonalPaperRows(userId, ids));

        List<ManageVOs.MySubjectVO> out = new ArrayList<>(subjects.size());
        for (QbSubject s : subjects) {
            ManageVOs.MySubjectVO vo = new ManageVOs.MySubjectVO();
            vo.setId(s.getId());
            vo.setName(s.getName());
            vo.setSubjectKey(s.getSubjectKey());
            vo.setIcon(s.getIcon());
            vo.setDescription(s.getDescription());
            vo.setStatus(s.getStatus());
            vo.setQuestionCount(qCounts.getOrDefault(s.getId(), 0L));
            vo.setPaperCount(pCounts.getOrDefault(s.getId(), 0L));
            vo.setCreatedAt(s.getCreatedAt() == null ? null : s.getCreatedAt().getTime());
            vo.setUpdatedAt(s.getUpdatedAt() == null ? null : s.getUpdatedAt().getTime());
            out.add(vo);
        }
        return out;
    }

    @Transactional
    public ManageVOs.MySubjectVO createSubject(Long userId, ManageRequests.SubjectSaveReq req) {
        ensurePersonalUser(userId);
        if (req == null || req.getName() == null || req.getName().isBlank()) {
            throw badRequest("科目名称不能为空");
        }
        String name = req.getName().trim();
        if (name.length() > 64) {
            throw badRequest("科目名称不能超过 64 字");
        }
        QbSubject subject = buildNewPersonalSubject(userId, name, req.getIcon(), req.getDescription());
        subjectMapper.insert(subject);
        return toSubjectVo(subject, 0L, 0L);
    }

    @Transactional
    public ManageVOs.MySubjectVO updateSubject(Long userId, Long subjectId, ManageRequests.SubjectSaveReq req) {
        ensurePersonalUser(userId);
        QbSubject subject = SubjectGuards.requireOwnedPersonal(subjectMapper, userId, subjectId);
        if (req == null) {
            throw badRequest("请求体不能为空");
        }
        if (req.getName() != null) {
            String name = req.getName().trim();
            if (name.isEmpty() || name.length() > 64) {
                throw badRequest("科目名称必须在 1~64 字之间");
            }
            subject.setName(name);
        }
        if (req.getIcon() != null) {
            subject.setIcon(trimTo(req.getIcon(), 64));
        }
        if (req.getDescription() != null) {
            subject.setDescription(trimTo(req.getDescription(), 255));
        }
        if (req.getStatus() != null) {
            if (req.getStatus() != 0 && req.getStatus() != 1) {
                throw badRequest("科目状态只能是 1启用 或 0停用");
            }
            subject.setStatus(req.getStatus());
        }
        subjectMapper.updateById(subject);
        long qCount = toCountMap(subjectMapper.countAllQuestionRows(List.of(subjectId)))
                .getOrDefault(subjectId, 0L);
        long pCount = toCountMap(subjectMapper.countPersonalPaperRows(userId, List.of(subjectId)))
                .getOrDefault(subjectId, 0L);
        return toSubjectVo(subject, qCount, pCount);
    }

    @Transactional
    public void deleteSubject(Long userId, Long subjectId, boolean cascade) {
        ensurePersonalUser(userId);
        SubjectGuards.requireOwnedPersonal(subjectMapper, userId, subjectId);

        List<Long> qIds = manageMapper.selectQuestionIdsBySubject(subjectId);
        List<QbPaper> papers = manageMapper.selectMyPapersBySubject(userId, subjectId);
        if (!cascade && (!qIds.isEmpty() || !papers.isEmpty())) {
            throw new PracticeException(PracticeErrorCode.CONFLICT,
                    "该科目下仍有 " + qIds.size() + " 道题目和 " + papers.size()
                            + " 份套卷，请确认级联删除或先清空数据");
        }

        List<Long> batchIds = batchMapper.selectIdsBySubject(userId, subjectId);
        if (!batchIds.isEmpty()) {
            batchItemMapper.deleteByBatchIds(batchIds);
            batchMapper.deleteBySubject(userId, subjectId);
        }
        if (!papers.isEmpty()) {
            manageMapper.deleteMyPapersBySubject(userId, subjectId);
        }
        if (!qIds.isEmpty()) {
            batchItemMapper.deleteByQuestionIds(qIds);
            manageMapper.deleteOptionsByQuestionIds(qIds);
            manageMapper.deleteQuestionsByIds(qIds);
        }
        subjectMapper.deleteById(subjectId);
    }

    // ==================== 2. 文档上传导入与模板 ====================

    public ImportTemplateGenerator.TemplateFile generateTemplate(String format) {
        return templateGenerator.generate(format);
    }

    @Transactional
    public ManageVOs.ImportResultVO importDocument(Long userId,
                                                   byte[] fileBytes,
                                                   String originalFileName,
                                                   Long subjectId,
                                                   String newSubjectName,
                                                   boolean generatePaper,
                                                   String paperTitle,
                                                   Integer durationMinutes,
                                                   BigDecimal defaultScore) {
        ensurePersonalUser(userId);
        DocumentTextExtractor.ExtractedDocument extracted = extractor.extract(fileBytes, originalFileName);
        ParseResult parsed = textParser.parse(extracted.text());
        if (parsed.questions().isEmpty()) {
            String detail = parsed.warnings().isEmpty()
                    ? "未能从文档中解析出合法题目，请下载示例模板核对格式"
                    : "未解析出合法题目：" + String.join("；", parsed.warnings().stream().limit(3).toList());
            throw badRequest(detail);
        }

        // 确定落入的个人科目：指定 subjectId 或现场新建个人科目
        QbSubject subject;
        if (subjectId != null) {
            subject = SubjectGuards.requireOwnedPersonal(subjectMapper, userId, subjectId);
        } else {
            String name = (newSubjectName != null && !newSubjectName.isBlank())
                    ? newSubjectName.trim()
                    : baseNameOf(extracted.fileName());
            if (name.length() > 64) {
                name = name.substring(0, 64);
            }
            subject = buildNewPersonalSubject(userId, name, null, "由文档《" + extracted.fileName() + "》导入创建");
            subjectMapper.insert(subject);
        }

        int sortOrder = manageMapper.selectMaxSortOrder(subject.getId());
        List<QbQuestion> insertedQuestions = new ArrayList<>(parsed.questions().size());
        Map<Long, PaperStructureValidator.QuestionBrief> briefs = new HashMap<>();
        List<PaperPackager.PackItem> packItems = new ArrayList<>(parsed.questions().size());

        for (ParsedQuestion pq : parsed.questions()) {
            sortOrder++;
            QbQuestion q = new QbQuestion();
            q.setSubjectId(subject.getId());
            q.setType(pq.type());
            q.setTitle(pq.title());
            q.setAnswerJson(pq.answerJson());
            q.setAnalysis(pq.analysis());
            q.setDifficulty(pq.difficulty());
            q.setTagsJson(toJson(pq.tags()));
            q.setStatus(1);
            q.setSortOrder(sortOrder);
            questionMapper.insert(q);

            int optSort = 1;
            for (ParsedQuestion.ParsedOption po : pq.options()) {
                QbQuestionOption opt = new QbQuestionOption();
                opt.setQuestionId(q.getId());
                opt.setOptionKey(po.optionKey());
                opt.setContent(po.content());
                opt.setIsCorrect(po.correct() ? 1 : 0);
                opt.setSortOrder(optSort++);
                optionMapper.insert(opt);
            }

            insertedQuestions.add(q);
            briefs.put(q.getId(), new PaperStructureValidator.QuestionBrief(q.getId(), subject.getId(), 1));
            packItems.add(new PaperPackager.PackItem(q.getId(), pq.type(), pq.sectionTitle(), pq.score()));
        }

        Long createdPaperId = null;
        String createdPaperTitle = null;
        if (generatePaper) {
            String resolvedTitle = (paperTitle != null && !paperTitle.isBlank())
                    ? paperTitle.trim()
                    : baseNameOf(extracted.fileName());
            int resolvedDuration = durationMinutes == null ? 60 : durationMinutes;
            QbPaper paper = paperPackager.buildAndValidate(
                    new PaperPackager.PackCommand(
                            userId,
                            subject.getId(),
                            resolvedTitle,
                            null,
                            null,
                            null,
                            resolvedDuration,
                            "由文档《" + extracted.fileName() + "》自动打包生成",
                            defaultScore,
                            packItems),
                    briefs);
            paperMapper.insert(paper);
            createdPaperId = paper.getId();
            createdPaperTitle = paper.getTitle();
        }

        int importedCount = insertedQuestions.size();
        int skippedCount = Math.max(0, parsed.totalBlocks() - importedCount);
        String batchStatus = skippedCount > 0 ? "PARTIAL" : "SUCCESS";

        QbImportBatch batch = new QbImportBatch();
        batch.setOwnerId(userId);
        batch.setSubjectId(subject.getId());
        batch.setPaperId(createdPaperId);
        batch.setFileName(extracted.fileName());
        batch.setFileType(extracted.fileType());
        batch.setFileSize(extracted.fileSize());
        batch.setTotalParsed(parsed.totalBlocks());
        batch.setImportedCount(importedCount);
        batch.setSkippedCount(skippedCount);
        batch.setWarningsJson(parsed.warnings().isEmpty() ? null : toJson(parsed.warnings()));
        batch.setStatus(batchStatus);
        batchMapper.insert(batch);

        for (int i = 0; i < insertedQuestions.size(); i++) {
            QbImportBatchItem item = new QbImportBatchItem();
            item.setBatchId(batch.getId());
            item.setQuestionId(insertedQuestions.get(i).getId());
            item.setSeqNo(i + 1);
            batchItemMapper.insert(item);
        }

        ManageVOs.ImportResultVO vo = new ManageVOs.ImportResultVO();
        vo.setBatchId(batch.getId());
        vo.setSubjectId(subject.getId());
        vo.setSubjectName(subject.getName());
        vo.setFileName(extracted.fileName());
        vo.setFileType(extracted.fileType());
        vo.setTotalParsed(parsed.totalBlocks());
        vo.setImportedCount(importedCount);
        vo.setSkippedCount(skippedCount);
        vo.setStatus(batchStatus);
        vo.setWarnings(parsed.warnings());
        vo.setPaperId(createdPaperId);
        vo.setPaperTitle(createdPaperTitle);
        return vo;
    }

    public PageVO<ManageVOs.ImportBatchVO> pageBatches(Long userId, Long subjectId, Long pageNum, Long pageSize) {
        ensurePersonalUser(userId);
        PracticePages.PageParam param = PracticePages.of(pageNum, pageSize);
        if (subjectId != null) {
            SubjectGuards.requireOwnedPersonal(subjectMapper, userId, subjectId);
        }

        long total = batchMapper.countByOwner(userId, subjectId);
        if (total == 0 || param.offset() >= total) {
            return PageVO.of(List.of(), total, param.pageNum(), param.pageSize());
        }
        List<QbImportBatch> rows = batchMapper.selectPageByOwner(userId, subjectId, param.offset(), param.pageSize());
        Map<Long, String> subjectNames = loadPersonalSubjectNameMap(userId);
        Map<Long, String> paperTitles = new HashMap<>();
        for (QbImportBatch b : rows) {
            if (b.getPaperId() != null && !paperTitles.containsKey(b.getPaperId())) {
                QbPaper p = paperMapper.selectById(b.getPaperId());
                if (p != null) {
                    paperTitles.put(b.getPaperId(), p.getTitle());
                }
            }
        }

        List<ManageVOs.ImportBatchVO> list = new ArrayList<>(rows.size());
        for (QbImportBatch b : rows) {
            ManageVOs.ImportBatchVO vo = new ManageVOs.ImportBatchVO();
            vo.setId(b.getId());
            vo.setSubjectId(b.getSubjectId());
            vo.setSubjectName(subjectNames.getOrDefault(b.getSubjectId(), "已删除科目"));
            vo.setPaperId(b.getPaperId());
            vo.setPaperTitle(b.getPaperId() == null ? null : paperTitles.get(b.getPaperId()));
            vo.setFileName(b.getFileName());
            vo.setFileType(b.getFileType());
            vo.setFileSize(b.getFileSize() == null ? 0L : b.getFileSize());
            vo.setTotalParsed(b.getTotalParsed() == null ? 0 : b.getTotalParsed());
            vo.setImportedCount(b.getImportedCount() == null ? 0 : b.getImportedCount());
            vo.setSkippedCount(b.getSkippedCount() == null ? 0 : b.getSkippedCount());
            vo.setWarnings(parseStringList(b.getWarningsJson()));
            vo.setStatus(b.getStatus());
            vo.setCreatedAt(b.getCreatedAt() == null ? null : b.getCreatedAt().getTime());
            list.add(vo);
        }
        return PageVO.of(list, total, param.pageNum(), param.pageSize());
    }

    @Transactional
    public void deleteBatch(Long userId, Long batchId, boolean deleteQuestions) {
        ensurePersonalUser(userId);
        if (batchId == null) {
            throw badRequest("batchId 不能为空");
        }
        QbImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw notFound("导入记录不存在");
        }
        if (!userId.equals(batch.getOwnerId())) {
            throw forbidden("无权操作该导入记录");
        }

        List<Long> questionIds = batchItemMapper.selectQuestionIdsByBatch(batchId);
        if (deleteQuestions) {
            // 若该批次生成了关联套卷，先删关联套卷再校验其余套卷引用
            Long linkedPaperId = batch.getPaperId();
            if (linkedPaperId != null) {
                QbPaper linked = paperMapper.selectById(linkedPaperId);
                if (linked != null && userId.equals(linked.getOwnerId())) {
                    paperMapper.deleteById(linkedPaperId);
                }
            }
            if (!questionIds.isEmpty()) {
                ensureNotReferencedByOtherPapers(userId, batch.getSubjectId(), new HashSet<>(questionIds));
                batchItemMapper.deleteByQuestionIds(questionIds);
                manageMapper.deleteOptionsByQuestionIds(questionIds);
                manageMapper.deleteQuestionsByIds(questionIds);
            }
        }
        batchItemMapper.deleteByBatch(batchId);
        batchMapper.deleteById(batchId);
    }

    // ==================== 3. 个人题目管理 ====================

    public PageVO<ManageVOs.MyQuestionVO> pageQuestions(Long userId,
                                                        Long subjectId,
                                                        Long batchId,
                                                        Integer type,
                                                        Integer difficulty,
                                                        Integer status,
                                                        String keyword,
                                                        Long pageNum,
                                                        Long pageSize) {
        ensurePersonalUser(userId);
        PracticePages.PageParam param = PracticePages.of(pageNum, pageSize);
        if (subjectId != null) {
            SubjectGuards.requireOwnedPersonal(subjectMapper, userId, subjectId);
        }
        if (batchId != null) {
            QbImportBatch batch = batchMapper.selectById(batchId);
            if (batch == null) {
                throw notFound("导入批次不存在");
            }
            if (!userId.equals(batch.getOwnerId())) {
                throw forbidden("无权查看该导入批次");
            }
        }
        String kw = keyword == null ? null : keyword.trim();

        long total = manageMapper.countMyQuestions(userId, subjectId, batchId, type, difficulty, status, kw);
        if (total == 0 || param.offset() >= total) {
            return PageVO.of(List.of(), total, param.pageNum(), param.pageSize());
        }
        List<QbQuestion> questions = manageMapper.selectMyQuestionsPage(
                userId, subjectId, batchId, type, difficulty, status, kw, param.offset(), param.pageSize());
        List<Long> qIds = questions.stream().map(QbQuestion::getId).toList();
        Map<Long, List<QbQuestionOption>> optByQ = groupOptionsByQuestion(qIds);
        Map<Long, String> subjectNames = loadPersonalSubjectNameMap(userId);

        List<ManageVOs.MyQuestionVO> list = new ArrayList<>(questions.size());
        for (QbQuestion q : questions) {
            list.add(toQuestionVo(q, subjectNames.getOrDefault(q.getSubjectId(), ""),
                    optByQ.getOrDefault(q.getId(), List.of())));
        }
        return PageVO.of(list, total, param.pageNum(), param.pageSize());
    }

    public ManageVOs.MyQuestionVO getQuestion(Long userId, Long questionId) {
        ensurePersonalUser(userId);
        QbQuestion q = requireOwnedQuestion(userId, questionId);
        QbSubject subject = subjectMapper.selectById(q.getSubjectId());
        List<QbQuestionOption> options = manageMapper.selectManageOptionsByQuestions(List.of(q.getId()));
        return toQuestionVo(q, subject == null ? "" : subject.getName(), options);
    }

    @Transactional
    public ManageVOs.MyQuestionVO createQuestion(Long userId, ManageRequests.QuestionSaveReq req) {
        ensurePersonalUser(userId);
        if (req == null || req.getSubjectId() == null) {
            throw badRequest("请选择所属个人科目");
        }
        QbSubject subject = SubjectGuards.requireOwnedPersonal(subjectMapper, userId, req.getSubjectId());
        QuestionPayloadValidator.NormalizedQuestion norm = payloadValidator.validateAndNormalize(req);

        int nextSort = manageMapper.selectMaxSortOrder(subject.getId()) + 1;
        QbQuestion q = new QbQuestion();
        q.setSubjectId(subject.getId());
        q.setType(norm.type());
        q.setTitle(norm.title());
        q.setAnswerJson(norm.answerJson());
        q.setAnalysis(norm.analysis());
        q.setDifficulty(norm.difficulty());
        q.setTagsJson(norm.tagsJson());
        q.setStatus(norm.status());
        q.setSortOrder(nextSort);
        questionMapper.insert(q);

        List<QbQuestionOption> savedOptions = saveOptions(q.getId(), norm.options());
        return toQuestionVo(q, subject.getName(), savedOptions);
    }

    @Transactional
    public ManageVOs.MyQuestionVO updateQuestion(Long userId, Long questionId, ManageRequests.QuestionSaveReq req) {
        ensurePersonalUser(userId);
        QbQuestion existing = requireOwnedQuestion(userId, questionId);
        Long targetSubjectId = req != null && req.getSubjectId() != null ? req.getSubjectId() : existing.getSubjectId();
        QbSubject targetSubject = SubjectGuards.requireOwnedPersonal(subjectMapper, userId, targetSubjectId);

        if (!existing.getSubjectId().equals(targetSubjectId)) {
            ensureNotReferencedByOtherPapers(userId, existing.getSubjectId(), Set.of(questionId));
        }

        QuestionPayloadValidator.NormalizedQuestion norm = payloadValidator.validateAndNormalize(req);
        existing.setSubjectId(targetSubjectId);
        existing.setType(norm.type());
        existing.setTitle(norm.title());
        existing.setAnswerJson(norm.answerJson());
        existing.setAnalysis(norm.analysis());
        existing.setDifficulty(norm.difficulty());
        existing.setTagsJson(norm.tagsJson());
        existing.setStatus(norm.status());
        questionMapper.updateById(existing);

        manageMapper.deleteOptionsByQuestionIds(List.of(questionId));
        List<QbQuestionOption> savedOptions = saveOptions(questionId, norm.options());
        return toQuestionVo(existing, targetSubject.getName(), savedOptions);
    }

    @Transactional
    public int deleteQuestions(Long userId, Collection<Long> ids) {
        ensurePersonalUser(userId);
        if (ids == null || ids.isEmpty()) {
            throw badRequest("请先选择要删除的题目");
        }
        Set<Long> uniqueIds = new LinkedHashSet<>(ids);
        Map<Long, Set<Long>> idsBySubject = new LinkedHashMap<>();
        for (Long qId : uniqueIds) {
            QbQuestion q = requireOwnedQuestion(userId, qId);
            idsBySubject.computeIfAbsent(q.getSubjectId(), k -> new HashSet<>()).add(qId);
        }
        for (Map.Entry<Long, Set<Long>> entry : idsBySubject.entrySet()) {
            ensureNotReferencedByOtherPapers(userId, entry.getKey(), entry.getValue());
        }
        batchItemMapper.deleteByQuestionIds(uniqueIds);
        manageMapper.deleteOptionsByQuestionIds(uniqueIds);
        return manageMapper.deleteQuestionsByIds(uniqueIds);
    }

    // ==================== 4. 个人套卷管理 ====================

    public PageVO<ManageVOs.MyPaperVO> pagePapers(Long userId, Long subjectId, Long pageNum, Long pageSize) {
        ensurePersonalUser(userId);
        PracticePages.PageParam param = PracticePages.of(pageNum, pageSize);
        if (subjectId != null) {
            SubjectGuards.requireOwnedPersonal(subjectMapper, userId, subjectId);
        }
        long total = manageMapper.countMyPapers(userId, subjectId);
        if (total == 0 || param.offset() >= total) {
            return PageVO.of(List.of(), total, param.pageNum(), param.pageSize());
        }
        List<QbPaper> papers = manageMapper.selectMyPapersPage(userId, subjectId, param.offset(), param.pageSize());
        Map<Long, String> subjectNames = loadPersonalSubjectNameMap(userId);
        List<ManageVOs.MyPaperVO> list = papers.stream()
                .map(p -> toPaperVo(p, subjectNames.getOrDefault(p.getSubjectId(), "")))
                .toList();
        return PageVO.of(list, total, param.pageNum(), param.pageSize());
    }

    @Transactional
    public ManageVOs.MyPaperVO packPaper(Long userId, ManageRequests.PaperPackReq req) {
        ensurePersonalUser(userId);
        if (req == null || req.getSubjectId() == null) {
            throw badRequest("请选择套卷所属个人科目");
        }
        QbSubject subject = SubjectGuards.requireOwnedPersonal(subjectMapper, userId, req.getSubjectId());
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw badRequest("请至少勾选 1 道题目打包成套卷");
        }

        Set<Long> seen = new HashSet<>();
        Map<Long, PaperStructureValidator.QuestionBrief> briefs = new HashMap<>();
        List<PaperPackager.PackItem> packItems = new ArrayList<>(req.getItems().size());
        for (ManageRequests.PaperPackItemReq item : req.getItems()) {
            if (item == null || item.getQuestionId() == null) {
                throw badRequest("题目 ID 不能为空");
            }
            if (!seen.add(item.getQuestionId())) {
                throw badRequest("套卷中不能重复包含同一道题：" + item.getQuestionId());
            }
            QbQuestion q = questionMapper.selectById(item.getQuestionId());
            if (q == null) {
                throw notFound("题目不存在：" + item.getQuestionId());
            }
            if (!subject.getId().equals(q.getSubjectId())) {
                throw badRequest("题目 " + q.getId() + " 不属于所选科目，不能打包进同一套卷");
            }
            if (q.getStatus() == null || q.getStatus() != 1) {
                throw new PracticeException(PracticeErrorCode.CONFLICT,
                        "题目 " + q.getId() + " 已停用，请先启用后再打包入卷");
            }
            briefs.put(q.getId(), new PaperStructureValidator.QuestionBrief(q.getId(), q.getSubjectId(), q.getStatus()));
            packItems.add(new PaperPackager.PackItem(q.getId(), q.getType(), null, item.getScore()));
        }

        int duration = req.getDurationMinutes() == null ? 60 : req.getDurationMinutes();
        QbPaper paper = paperPackager.buildAndValidate(
                new PaperPackager.PackCommand(
                        userId,
                        subject.getId(),
                        req.getTitle(),
                        req.getExamYear(),
                        req.getExamMonth(),
                        req.getSetNo(),
                        duration,
                        req.getDescription(),
                        req.getDefaultScore(),
                        packItems),
                briefs);
        paperMapper.insert(paper);
        return toPaperVo(paper, subject.getName());
    }

    @Transactional
    public ManageVOs.MyPaperVO updatePaper(Long userId, Long paperId, ManageRequests.PaperUpdateReq req) {
        ensurePersonalUser(userId);
        QbPaper paper = requireOwnedPaper(userId, paperId);
        if (req == null) {
            throw badRequest("请求体不能为空");
        }
        if (req.getTitle() != null) {
            String title = req.getTitle().trim();
            if (title.isEmpty() || title.length() > 200) {
                throw badRequest("套卷名称必须在 1~200 字之间");
            }
            paper.setTitle(title);
        }
        if (req.getExamYear() != null) {
            if (req.getExamYear() < 1990 || req.getExamYear() > 2100) {
                throw badRequest("年份范围不合法");
            }
            paper.setExamYear(req.getExamYear());
        }
        if (req.getExamMonth() != null) {
            if (req.getExamMonth() < 1 || req.getExamMonth() > 12) {
                throw badRequest("月份必须在 1~12 之间");
            }
            paper.setExamMonth(req.getExamMonth());
        }
        if (req.getSetNo() != null) {
            if (req.getSetNo() < 1 || req.getSetNo() > 99) {
                throw badRequest("套次必须在 1~99 之间");
            }
            paper.setSetNo(req.getSetNo());
        }
        if (req.getDurationMinutes() != null) {
            if (req.getDurationMinutes() <= 0 || req.getDurationMinutes() > 600) {
                throw badRequest("考试时长必须在 1~600 分钟之间");
            }
            PaperStructure structure = PaperStructureParser.parse(paper.getStructureJson());
            if (structure.sumPartMinutes() > req.getDurationMinutes()) {
                throw badRequest("新时长不能小于卷面各部分时长之和（" + structure.sumPartMinutes() + " 分钟）");
            }
            paper.setDurationMinutes(req.getDurationMinutes());
        }
        if (req.getDescription() != null) {
            paper.setDescription(trimTo(req.getDescription(), 500));
        }
        if (req.getStatus() != null) {
            if (req.getStatus() != 0 && req.getStatus() != 1) {
                throw badRequest("状态只能是 1启用 或 0停用");
            }
            paper.setStatus(req.getStatus());
        }
        paperMapper.updateById(paper);
        QbSubject subject = subjectMapper.selectById(paper.getSubjectId());
        return toPaperVo(paper, subject == null ? "" : subject.getName());
    }

    @Transactional
    public void deletePaper(Long userId, Long paperId) {
        ensurePersonalUser(userId);
        requireOwnedPaper(userId, paperId);
        batchMapper.clearPaperRef(userId, paperId);
        paperMapper.deleteById(paperId);
    }

    // ==================== 内部工具方法 ====================

    private QbQuestion requireOwnedQuestion(Long userId, Long questionId) {
        if (questionId == null) {
            throw badRequest("questionId 不能为空");
        }
        QbQuestion q = questionMapper.selectById(questionId);
        if (q == null) {
            throw notFound("题目不存在");
        }
        SubjectGuards.requireOwnedPersonal(subjectMapper, userId, q.getSubjectId());
        return q;
    }

    private QbPaper requireOwnedPaper(Long userId, Long paperId) {
        if (paperId == null) {
            throw badRequest("paperId 不能为空");
        }
        QbPaper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw notFound("套卷不存在");
        }
        if (paper.getOwnerId() == null
                || paper.getOwnerId() == PaperLoad.PUBLIC_OWNER
                || !paper.getOwnerId().equals(userId)) {
            throw forbidden("只能操作自己的个人套卷");
        }
        return paper;
    }

    private void ensureNotReferencedByOtherPapers(Long userId, Long subjectId, Set<Long> targetQuestionIds) {
        List<QbPaper> papers = manageMapper.selectMyPapersBySubject(userId, subjectId);
        for (QbPaper paper : papers) {
            if (paper.getStructureJson() == null || paper.getStructureJson().isBlank()) {
                continue;
            }
            PaperStructure structure = PaperStructureParser.parse(paper.getStructureJson());
            for (PaperStructure.QuestionRef ref : structure.questionsInOrder()) {
                if (targetQuestionIds.contains(ref.questionId())) {
                    throw new PracticeException(PracticeErrorCode.CONFLICT,
                            "题目（ID=" + ref.questionId() + "）正被套卷《" + paper.getTitle()
                                    + "》引用，请先删除该套卷后再操作");
                }
            }
        }
    }

    private List<QbQuestionOption> saveOptions(Long questionId, List<ParsedQuestion.ParsedOption> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        List<QbQuestionOption> saved = new ArrayList<>(options.size());
        int sort = 1;
        for (ParsedQuestion.ParsedOption po : options) {
            QbQuestionOption opt = new QbQuestionOption();
            opt.setQuestionId(questionId);
            opt.setOptionKey(po.optionKey());
            opt.setContent(po.content());
            opt.setIsCorrect(po.correct() ? 1 : 0);
            opt.setSortOrder(sort++);
            optionMapper.insert(opt);
            saved.add(opt);
        }
        return saved;
    }

    private Map<Long, List<QbQuestionOption>> groupOptionsByQuestion(List<Long> qIds) {
        if (qIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<QbQuestionOption>> map = new HashMap<>();
        for (QbQuestionOption opt : manageMapper.selectManageOptionsByQuestions(qIds)) {
            map.computeIfAbsent(opt.getQuestionId(), k -> new ArrayList<>()).add(opt);
        }
        return map;
    }

    private Map<Long, String> loadPersonalSubjectNameMap(Long userId) {
        Map<Long, String> map = new HashMap<>();
        for (QbSubject s : subjectMapper.selectPersonalSubjects(userId)) {
            map.put(s.getId(), s.getName());
        }
        return map;
    }

    private static QbSubject buildNewPersonalSubject(Long userId, String name, String icon, String description) {
        QbSubject subject = new QbSubject();
        subject.setOwnerId(userId);
        subject.setName(name);
        subject.setSubjectKey("u" + userId + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        subject.setIcon(trimTo(icon, 64));
        subject.setDescription(trimTo(description, 255));
        subject.setSortOrder(100);
        subject.setStatus(1);
        return subject;
    }

    private static ManageVOs.MySubjectVO toSubjectVo(QbSubject s, long qCount, long pCount) {
        ManageVOs.MySubjectVO vo = new ManageVOs.MySubjectVO();
        vo.setId(s.getId());
        vo.setName(s.getName());
        vo.setSubjectKey(s.getSubjectKey());
        vo.setIcon(s.getIcon());
        vo.setDescription(s.getDescription());
        vo.setStatus(s.getStatus());
        vo.setQuestionCount(qCount);
        vo.setPaperCount(pCount);
        vo.setCreatedAt(s.getCreatedAt() == null ? null : s.getCreatedAt().getTime());
        vo.setUpdatedAt(s.getUpdatedAt() == null ? null : s.getUpdatedAt().getTime());
        return vo;
    }

    private static ManageVOs.MyQuestionVO toQuestionVo(QbQuestion q, String subjectName, List<QbQuestionOption> options) {
        ManageVOs.MyQuestionVO vo = new ManageVOs.MyQuestionVO();
        vo.setQuestionId(q.getId());
        vo.setSubjectId(q.getSubjectId());
        vo.setSubjectName(subjectName);
        vo.setType(q.getType());
        vo.setTypeName(TYPE_NAMES.getOrDefault(q.getType(), "未知题型"));
        vo.setTitle(q.getTitle());
        List<ManageVOs.MyQuestionOptionVO> optVos = new ArrayList<>(options.size());
        for (QbQuestionOption opt : options) {
            ManageVOs.MyQuestionOptionVO ov = new ManageVOs.MyQuestionOptionVO();
            ov.setOptionKey(opt.getOptionKey());
            ov.setContent(opt.getContent());
            ov.setCorrect(opt.getIsCorrect() != null && opt.getIsCorrect() == 1);
            optVos.add(ov);
        }
        vo.setOptions(optVos);
        vo.setAnswerJson(q.getAnswerJson());
        vo.setAnalysis(q.getAnalysis() == null ? "" : q.getAnalysis());
        vo.setDifficulty(q.getDifficulty());
        vo.setTags(PracticeMasker.parseTags(q.getTagsJson()));
        vo.setStatus(q.getStatus());
        vo.setSortOrder(q.getSortOrder());
        vo.setCreatedAt(q.getCreatedAt() == null ? null : q.getCreatedAt().getTime());
        vo.setUpdatedAt(q.getUpdatedAt() == null ? null : q.getUpdatedAt().getTime());
        return vo;
    }

    private static ManageVOs.MyPaperVO toPaperVo(QbPaper p, String subjectName) {
        ManageVOs.MyPaperVO vo = new ManageVOs.MyPaperVO();
        vo.setPaperId(p.getId());
        vo.setSubjectId(p.getSubjectId());
        vo.setSubjectName(subjectName);
        vo.setTitle(p.getTitle());
        vo.setExamYear(p.getExamYear());
        vo.setExamMonth(p.getExamMonth());
        vo.setSetNo(p.getSetNo());
        vo.setBadges(PaperCards.badges(p.getExamYear(), p.getExamMonth(), p.getSetNo(), p.getDurationMinutes()));
        vo.setDurationMinutes(p.getDurationMinutes());
        vo.setTotalScore(p.getTotalScore());
        vo.setQuestionCount(p.getQuestionCount());
        vo.setDescription(p.getDescription());
        vo.setStatus(p.getStatus());
        vo.setCreatedAt(p.getCreatedAt() == null ? null : p.getCreatedAt().getTime());
        vo.setUpdatedAt(p.getUpdatedAt() == null ? null : p.getUpdatedAt().getTime());
        return vo;
    }

    private static void ensurePersonalUser(Long userId) {
        if (userId == null) {
            throw new PracticeException(PracticeErrorCode.IDENTITY_MISSING, "用户身份不存在，请重新登录");
        }
        if (userId == PaperLoad.PUBLIC_OWNER) {
            throw forbidden("公共题库占位账号（id=1）不允许执行个人题库管理操作");
        }
    }

    private static Map<Long, Long> toCountMap(List<StatCountRow> rows) {
        Map<Long, Long> map = new HashMap<>();
        if (rows != null) {
            for (StatCountRow row : rows) {
                map.put(row.subjectId(), row.cnt());
            }
        }
        return map;
    }

    private static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String baseNameOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.isBlank() ? "我的题库" : base.trim();
    }

    private static String trimTo(String s, int max) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static PracticeException badRequest(String msg) {
        return new PracticeException(PracticeErrorCode.BAD_REQUEST, msg);
    }

    private static PracticeException notFound(String msg) {
        return new PracticeException(PracticeErrorCode.NOT_FOUND, msg);
    }

    private static PracticeException forbidden(String msg) {
        return new PracticeException(PracticeErrorCode.FORBIDDEN, msg);
    }
}
