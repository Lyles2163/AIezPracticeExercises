package org.leon.aimodule.chat.provider;

import jakarta.validation.Valid;
import org.leon.commonjwt.pojo.Result;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设置页的供应商接口。归属一律取网关注入的 X-User-Name，请求体不接受任何身份字段。
 * 异常由 chat 包的 ChatExceptionHandler 统一转成 Result(code=0)。
 */
@RestController
@RequestMapping("/ai/chat/providers")
@Validated
public class ProviderController {

    private final ProviderService service;

    public ProviderController(ProviderService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<ProviderViews.Provider>> list(@RequestHeader("X-User-Name") String userName) {
        return Result.success(service.list(userName));
    }

    @PostMapping
    public Result<ProviderViews.Provider> create(@RequestHeader("X-User-Name") String userName,
                                                 @RequestBody @Valid ProviderRequests.CreateProvider req) {
        return Result.success(service.create(userName, req));
    }

    @PutMapping("/{providerId}")
    public Result<ProviderViews.Provider> update(@RequestHeader("X-User-Name") String userName,
                                                 @PathVariable Long providerId,
                                                 @RequestBody @Valid ProviderRequests.UpdateProvider req) {
        return Result.success(service.update(userName, providerId, req));
    }

    @DeleteMapping("/{providerId}")
    public Result<Void> delete(@RequestHeader("X-User-Name") String userName, @PathVariable Long providerId) {
        service.delete(userName, providerId);
        return Result.success();
    }

    @PostMapping("/{providerId}/models")
    public Result<ProviderViews.Model> addModel(@RequestHeader("X-User-Name") String userName,
                                                @PathVariable Long providerId,
                                                @RequestBody @Valid ProviderRequests.ModelInput req) {
        return Result.success(service.addModel(userName, providerId, req));
    }

    @PutMapping("/{providerId}/models/{modelId}")
    public Result<ProviderViews.Model> updateModel(@RequestHeader("X-User-Name") String userName,
                                                   @PathVariable Long providerId, @PathVariable Long modelId,
                                                   @RequestBody @Valid ProviderRequests.UpdateModel req) {
        return Result.success(service.updateModel(userName, providerId, modelId, req));
    }

    @DeleteMapping("/{providerId}/models/{modelId}")
    public Result<Void> deleteModel(@RequestHeader("X-User-Name") String userName,
                                    @PathVariable Long providerId, @PathVariable Long modelId) {
        service.deleteModel(userName, providerId, modelId);
        return Result.success();
    }

    @PostMapping("/{providerId}/test")
    public Result<ProviderProbe.Result> test(@RequestHeader("X-User-Name") String userName,
                                             @PathVariable Long providerId) {
        return Result.success(service.testConnection(userName, providerId));
    }
}
