package org.leon.practicemodule.manage.controller;

import org.leon.commonjwt.pojo.Result;
import org.leon.loggingmodule.annotation.LogBiz;
import org.leon.practicemodule.exception.PracticeErrorCode;
import org.leon.practicemodule.exception.PracticeException;
import org.leon.practicemodule.manage.parse.ImportTemplateGenerator;
import org.leon.practicemodule.manage.pojo.ManageRequests;
import org.leon.practicemodule.manage.pojo.vo.ManageVOs;
import org.leon.practicemodule.manage.service.PracticeManageService;
import org.leon.practicemodule.pojo.vo.PageVO;
import org.leon.practicemodule.service.UserIdentityService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 个人题库管理与文档上传入库接口。
 * 复用 /practice/** 网关路由与 X-User-Name 身份链路，所有写操作仅限本人个人数据。
 */
@RestController
@RequestMapping("/practice/my")
public class PracticeManageController {

    private final PracticeManageService manageService;
    private final UserIdentityService userIdentityService;

    public PracticeManageController(PracticeManageService manageService,
                                    UserIdentityService userIdentityService) {
        this.manageService = manageService;
        this.userIdentityService = userIdentityService;
    }

    // ==================== 个人科目 ====================

    @GetMapping("/subjects")
    public Result<List<ManageVOs.MySubjectVO>> listSubjects(@RequestHeader("X-User-Name") String userName) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(manageService.listMySubjects(userId));
    }

    @LogBiz(value = "新建个人科目", type = "PRACTICE_SUBJECT_CREATE")
    @PostMapping("/subjects")
    public Result<ManageVOs.MySubjectVO> createSubject(@RequestHeader("X-User-Name") String userName,
                                                       @RequestBody ManageRequests.SubjectSaveReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(manageService.createSubject(userId, req));
    }

    @LogBiz(value = "更新个人科目", type = "PRACTICE_SUBJECT_UPDATE")
    @PutMapping("/subjects/{id}")
    public Result<ManageVOs.MySubjectVO> updateSubject(@RequestHeader("X-User-Name") String userName,
                                                       @PathVariable Long id,
                                                       @RequestBody ManageRequests.SubjectSaveReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(manageService.updateSubject(userId, id, req));
    }

    @LogBiz(value = "删除个人科目", type = "PRACTICE_SUBJECT_DELETE")
    @DeleteMapping("/subjects/{id}")
    public Result<Void> deleteSubject(@RequestHeader("X-User-Name") String userName,
                                      @PathVariable Long id,
                                      @RequestParam(defaultValue = "false") boolean cascade) {
        Long userId = userIdentityService.resolveUserId(userName);
        manageService.deleteSubject(userId, id, cascade);
        return Result.success(null);
    }

    // ==================== 文档上传导入与模板 ====================

    @LogBiz(value = "上传文档导入题库", type = "PRACTICE_DOC_IMPORT")
    @PostMapping(value = "/import/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ManageVOs.ImportResultVO> upload(@RequestHeader("X-User-Name") String userName,
                                                   @RequestParam("file") MultipartFile file,
                                                   @RequestParam(required = false) Long subjectId,
                                                   @RequestParam(required = false) String newSubjectName,
                                                   @RequestParam(defaultValue = "false") boolean generatePaper,
                                                   @RequestParam(required = false) String paperTitle,
                                                   @RequestParam(required = false) Integer durationMinutes,
                                                   @RequestParam(required = false) BigDecimal defaultScore) {
        Long userId = userIdentityService.resolveUserId(userName);
        if (file == null || file.isEmpty()) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "请选择要上传的文档");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new PracticeException(PracticeErrorCode.BAD_REQUEST, "读取上传文件失败");
        }
        return Result.success(manageService.importDocument(
                userId,
                bytes,
                file.getOriginalFilename(),
                subjectId,
                newSubjectName,
                generatePaper,
                paperTitle,
                durationMinutes,
                defaultScore));
    }

    @GetMapping("/import/template")
    public ResponseEntity<byte[]> downloadTemplate(@RequestHeader("X-User-Name") String userName,
                                                   @RequestParam(defaultValue = "md") String format) {
        userIdentityService.resolveUserId(userName);
        ImportTemplateGenerator.TemplateFile tpl = manageService.generateTemplate(format);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(tpl.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(tpl.fileName(), StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(tpl.bytes());
    }

    @GetMapping("/import/batches")
    public Result<PageVO<ManageVOs.ImportBatchVO>> pageBatches(@RequestHeader("X-User-Name") String userName,
                                                               @RequestParam(required = false) Long subjectId,
                                                               @RequestParam(required = false) Long pageNum,
                                                               @RequestParam(required = false) Long pageSize) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(manageService.pageBatches(userId, subjectId, pageNum, pageSize));
    }

    @LogBiz(value = "删除导入批次", type = "PRACTICE_BATCH_DELETE")
    @DeleteMapping("/import/batches/{batchId}")
    public Result<Void> deleteBatch(@RequestHeader("X-User-Name") String userName,
                                    @PathVariable Long batchId,
                                    @RequestParam(defaultValue = "false") boolean deleteQuestions) {
        Long userId = userIdentityService.resolveUserId(userName);
        manageService.deleteBatch(userId, batchId, deleteQuestions);
        return Result.success(null);
    }

    // ==================== 个人题目 ====================

    @GetMapping("/questions")
    public Result<PageVO<ManageVOs.MyQuestionVO>> pageQuestions(@RequestHeader("X-User-Name") String userName,
                                                                @RequestParam(required = false) Long subjectId,
                                                                @RequestParam(required = false) Long batchId,
                                                                @RequestParam(required = false) Integer type,
                                                                @RequestParam(required = false) Integer difficulty,
                                                                @RequestParam(required = false) Integer status,
                                                                @RequestParam(required = false) String keyword,
                                                                @RequestParam(required = false) Long pageNum,
                                                                @RequestParam(required = false) Long pageSize) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(manageService.pageQuestions(
                userId, subjectId, batchId, type, difficulty, status, keyword, pageNum, pageSize));
    }

    @GetMapping("/questions/{id}")
    public Result<ManageVOs.MyQuestionVO> getQuestion(@RequestHeader("X-User-Name") String userName,
                                                      @PathVariable Long id) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(manageService.getQuestion(userId, id));
    }

    @LogBiz(value = "新增个人题目", type = "PRACTICE_QUESTION_CREATE")
    @PostMapping("/questions")
    public Result<ManageVOs.MyQuestionVO> createQuestion(@RequestHeader("X-User-Name") String userName,
                                                         @RequestBody ManageRequests.QuestionSaveReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(manageService.createQuestion(userId, req));
    }

    @LogBiz(value = "编辑个人题目", type = "PRACTICE_QUESTION_UPDATE")
    @PutMapping("/questions/{id}")
    public Result<ManageVOs.MyQuestionVO> updateQuestion(@RequestHeader("X-User-Name") String userName,
                                                         @PathVariable Long id,
                                                         @RequestBody ManageRequests.QuestionSaveReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(manageService.updateQuestion(userId, id, req));
    }

    @LogBiz(value = "删除个人题目", type = "PRACTICE_QUESTION_DELETE")
    @DeleteMapping("/questions/{id}")
    public Result<Integer> deleteQuestion(@RequestHeader("X-User-Name") String userName,
                                          @PathVariable Long id) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(manageService.deleteQuestions(userId, List.of(id)));
    }

    @LogBiz(value = "批量删除个人题目", type = "PRACTICE_QUESTION_BATCH_DELETE")
    @PostMapping("/questions/batch-delete")
    public Result<Integer> batchDeleteQuestions(@RequestHeader("X-User-Name") String userName,
                                                @RequestBody ManageRequests.BatchDeleteReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        List<Long> ids = req == null ? List.of() : req.getIds();
        return Result.success(manageService.deleteQuestions(userId, ids));
    }

    // ==================== 个人套卷 ====================

    @GetMapping("/papers")
    public Result<PageVO<ManageVOs.MyPaperVO>> pagePapers(@RequestHeader("X-User-Name") String userName,
                                                          @RequestParam(required = false) Long subjectId,
                                                          @RequestParam(required = false) Long pageNum,
                                                          @RequestParam(required = false) Long pageSize) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(manageService.pagePapers(userId, subjectId, pageNum, pageSize));
    }

    @LogBiz(value = "一键打包套卷", type = "PRACTICE_PAPER_PACK")
    @PostMapping("/papers")
    public Result<ManageVOs.MyPaperVO> packPaper(@RequestHeader("X-User-Name") String userName,
                                                 @RequestBody ManageRequests.PaperPackReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(manageService.packPaper(userId, req));
    }

    @LogBiz(value = "编辑个人套卷", type = "PRACTICE_PAPER_UPDATE")
    @PutMapping("/papers/{id}")
    public Result<ManageVOs.MyPaperVO> updatePaper(@RequestHeader("X-User-Name") String userName,
                                                   @PathVariable Long id,
                                                   @RequestBody ManageRequests.PaperUpdateReq req) {
        Long userId = userIdentityService.resolveUserId(userName);
        return Result.success(manageService.updatePaper(userId, id, req));
    }

    @LogBiz(value = "删除个人套卷", type = "PRACTICE_PAPER_DELETE")
    @DeleteMapping("/papers/{id}")
    public Result<Void> deletePaper(@RequestHeader("X-User-Name") String userName,
                                    @PathVariable Long id) {
        Long userId = userIdentityService.resolveUserId(userName);
        manageService.deletePaper(userId, id);
        return Result.success(null);
    }
}
