package org.leon.practicemodule.mapper;

import java.util.Date;

/** selectFavoritePage 的投影行 */
public record FavoritePageRow(Long questionId, Date createdAt, Integer type, String title) {
}
