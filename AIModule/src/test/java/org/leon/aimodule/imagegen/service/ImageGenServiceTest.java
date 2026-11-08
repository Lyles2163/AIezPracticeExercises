package org.leon.aimodule.imagegen.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.chat.provider.ProviderRoute;
import org.leon.aimodule.chat.provider.UserModelCatalogService;
import org.leon.aimodule.imagegen.config.ImageGenProperties;
import org.leon.aimodule.imagegen.exception.ImageGenException;
import org.leon.aimodule.imagegen.gateway.ImageGateway;
import org.leon.aimodule.imagegen.minio.ImageObjectStore;
import org.leon.aimodule.imagegen.web.dto.ImageGenRequest;
import org.leon.aimodule.imagegen.web.dto.ImageGenVo;
import org.leon.aimodule.imagegen.web.dto.ImageModelCatalogVo;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageGenServiceTest {

    private UserModelCatalogService userModels;
    private ImageGateway gateway;
    private ImageObjectStore store;
    private ImageGenService service;

    @BeforeEach
    void setUp() {
        userModels = mock(UserModelCatalogService.class);
        gateway = mock(ImageGateway.class);
        store = mock(ImageObjectStore.class);
        service = new ImageGenService(userModels, gateway, store, new ImageGenProperties());
    }

    @Test
    @DisplayName("生图成功：字节落 MinIO，返回 objectKey + 代理路径")
    void generateStoresImages() {
        when(userModels.resolveImage("alice", "u:1:2")).thenReturn(route());
        when(gateway.generate(any())).thenReturn(new ImageGateway.Response(
                List.of(new ImageGateway.GeneratedImage(new byte[]{1, 2, 3}, "image/png")), 321L));
        when(store.put("alice", "image/png", new byte[]{1, 2, 3})).thenReturn("alice/20261106/abc.png");

        ImageGenVo vo = service.generate("alice", new ImageGenRequest("u:1:2", " 一只猫 ", "1024x1024", 1));

        assertEquals(321L, vo.latencyMs());
        assertEquals(1, vo.images().size());
        assertEquals("alice/20261106/abc.png", vo.images().get(0).objectKey());
        assertEquals("/ai/imagegen/files/alice/20261106/abc.png", vo.images().get(0).url());
        assertEquals(3L, vo.images().get(0).bytes());
    }

    @Test
    @DisplayName("提示词为空直接拒绝，不打上游")
    void blankPromptRejected() {
        assertEquals("请填写提示词", assertThrows(ImageGenException.class,
                () -> service.generate("alice", new ImageGenRequest("u:1:2", "  ", null, 1))).getMessage());
        verify(gateway, never()).generate(any());
    }

    @Test
    @DisplayName("数量与尺寸都在服务端卡住，前端传什么都不算数")
    void rejectsUnsupportedCountAndSize() {
        assertEquals("一次最多生成 4 张", assertThrows(ImageGenException.class,
                () -> service.generate("alice", new ImageGenRequest("u:1:2", "猫", null, 5))).getMessage());
        assertEquals("不支持的图片尺寸: 100x100", assertThrows(ImageGenException.class,
                () -> service.generate("alice", new ImageGenRequest("u:1:2", "猫", "100x100", 1))).getMessage());
        verify(gateway, never()).generate(any());
    }

    @Test
    @DisplayName("越权取图按不存在处理，不碰 MinIO")
    void fileRejectsOtherUsersKey() {
        assertEquals("图片不存在", assertThrows(ImageGenException.class,
                () -> service.file("bob", "alice/20261106/abc.png")).getMessage());
        verify(store, never()).open(anyString());
    }

    @Test
    @DisplayName("自己的图能取回")
    void fileReturnsOwnImage() {
        ImageObjectStore.StoredImage stored = new ImageObjectStore.StoredImage(
                new ByteArrayInputStream(new byte[]{1}), 1L, "image/png");
        when(store.open("alice/20261106/abc.png")).thenReturn(stored);

        assertEquals(stored, service.file("alice", "alice/20261106/abc.png"));
    }

    @Test
    @DisplayName("生图目录把选择器 key 与供应商名映射给前端")
    void modelsMapsChoices() {
        when(userModels.imageChoices("alice")).thenReturn(List.of(
                new UserModelCatalogService.Choice(1L, 2L, "我的中转", "dall-e-3", "画图", false)));

        ImageModelCatalogVo vo = service.models("alice");

        assertEquals(1, vo.models().size());
        assertEquals("u:1:2", vo.models().get(0).key());
        assertEquals("画图", vo.models().get(0).name());
        assertEquals("我的中转", vo.models().get(0).tag());
    }

    private static ProviderRoute route() {
        return new ProviderRoute(1L, 2L, "我的中转", "画图",
                "https://gw.example.com/v1", "sk-key", "dall-e-3", false);
    }
}
