package org.leon.aimodule.chat.web;

import jakarta.validation.Valid;
import org.leon.aimodule.chat.service.ConversationService;
import org.leon.aimodule.chat.service.MessageQueryService;
import org.leon.aimodule.chat.web.dto.ConversationPageVo;
import org.leon.aimodule.chat.web.dto.ConversationVo;
import org.leon.aimodule.chat.web.dto.CreateConversationRequest;
import org.leon.aimodule.chat.web.dto.MessagePageVo;
import org.leon.aimodule.chat.web.dto.ModelCatalogVo;
import org.leon.aimodule.chat.web.dto.RenameConversationRequest;
import org.leon.commonjwt.pojo.Result;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/chat")
@Validated
public class ChatConversationController {

    private final ConversationService service;
    private final MessageQueryService messageQuery;

    public ChatConversationController(ConversationService service, MessageQueryService messageQuery) {
        this.service = service;
        this.messageQuery = messageQuery;
    }

    @GetMapping("/models")
    public Result<ModelCatalogVo> models() {
        return Result.success(service.catalog());
    }

    @PostMapping("/conversations")
    public Result<ConversationVo> create(@RequestHeader("X-User-Name") String userName,
                                        @RequestBody(required = false) @Valid CreateConversationRequest req) {
        return Result.success(service.create(userName, req));
    }

    @GetMapping("/conversations")
    public Result<ConversationPageVo> list(@RequestHeader("X-User-Name") String userName,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(defaultValue = "20") int limit) {
        return Result.success(service.list(userName, cursor, limit));
    }

    @GetMapping("/conversations/{id}/messages")
    public Result<MessagePageVo> messages(@RequestHeader("X-User-Name") String userName,
                                         @PathVariable Long id,
                                         @RequestParam(required = false) Long beforeId,
                                         @RequestParam(defaultValue = "30") int limit) {
        return Result.success(messageQuery.page(userName, id, beforeId, limit));
    }

    @PatchMapping("/conversations/{id}")
    public Result<Void> rename(@RequestHeader("X-User-Name") String userName,
                              @PathVariable Long id,
                              @RequestBody @Valid RenameConversationRequest req) {
        service.rename(userName, id, req.title());
        return Result.success();
    }

    @DeleteMapping("/conversations/{id}")
    public Result<Void> delete(@RequestHeader("X-User-Name") String userName, @PathVariable Long id) {
        service.softDelete(userName, id);
        return Result.success();
    }
}
