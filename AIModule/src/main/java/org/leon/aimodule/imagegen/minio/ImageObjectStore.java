package org.leon.aimodule.imagegen.minio;

import java.io.InputStream;

public interface ImageObjectStore {

    /** 存一张图，返回对象键（库里/响应里只出现这个键，绝不出现 MinIO 地址）。 */
    String put(String userName, String contentType, byte[] data);

    /** 按对象键取图；不存在或存储不可用时抛 ImageGenException。 */
    StoredImage open(String objectKey);

    record StoredImage(InputStream stream, long size, String contentType) {
    }
}
