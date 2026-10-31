package com.dywatch.app.feed;

import com.dywatch.app.net.DouyinApi;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

/** 视频流的可序列化来源：换页后仍按原列表翻页，不串回推荐流。 */
public final class VideoSource implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int NONE = 0, USER = 1, COLLECTION = 2, ALL_COLLECTIONS = 3,
            SEARCH = 4, LIKES = 5, RECOMMEND = 6;

    public int type = NONE;
    public String key = "";
    public String keyword = "";
    public String searchId = "";
    public long cursor;
    public boolean hasMore;

    public static VideoSource user(String secUid, long cursor, boolean hasMore) {
        return of(USER, secUid, cursor, hasMore);
    }
    public static VideoSource collection(String id, long cursor, boolean hasMore) {
        return of(COLLECTION, id, cursor, hasMore);
    }
    public static VideoSource allCollections(long cursor, boolean hasMore) {
        return of(ALL_COLLECTIONS, "", cursor, hasMore);
    }
    public static VideoSource search(String keyword, long cursor, boolean hasMore) {
        VideoSource s = of(SEARCH, "", cursor, hasMore);
        s.keyword = keyword == null ? "" : keyword;
        return s;
    }
    public static VideoSource likes(long cursor, boolean hasMore) {
        return of(LIKES, "", cursor, hasMore);
    }
    public static VideoSource recommend(long cursor, boolean hasMore) {
        return of(RECOMMEND, "", cursor, hasMore);
    }
    private static VideoSource of(int type, String key, long cursor, boolean hasMore) {
        VideoSource s = new VideoSource();
        s.type = type;
        s.key = key == null ? "" : key;
        s.cursor = cursor;
        s.hasMore = hasMore;
        return s;
    }

    /** 只由 FeedActivity 单个后台线程串行调用。 */
    public List<FeedVideo> next(DouyinApi api) throws IOException {
        if (!hasMore) return java.util.Collections.emptyList();
        List<FeedVideo> list;
        switch (type) {
            case USER:
                list = api.fetchUserPosts(key, cursor);
                hasMore = api.lastPostsHasMore;
                cursor = api.lastPostsCursor;
                break;
            case COLLECTION:
                list = api.fetchCollectionVideos(key, cursor);
                hasMore = api.lastCollectionHasMore;
                cursor = api.lastCollectionCursor;
                break;
            case ALL_COLLECTIONS:
                list = api.fetchAllCollectionVideos(cursor);
                hasMore = api.lastCollectionHasMore;
                cursor = api.lastCollectionCursor;
                break;
            case SEARCH:
                list = api.searchVideos(keyword, (int) cursor, searchId);
                hasMore = api.lastSearchHasMore;
                cursor = api.lastSearchOffset;
                searchId = api.lastSearchId;
                break;
            case LIKES:
                String secUid = api.fetchSelfSecUid();
                list = api.fetchFavorite(secUid, cursor);
                // 喜欢接口历史版本未暴露游标；请求到空页即停，避免重复请求。
                hasMore = !list.isEmpty();
                cursor += list.size();
                break;
            case RECOMMEND:
                // 首页“刷视频”先给选片列表；进入全屏后继续按推荐流翻页。
                list = api.fetchFeed(10);
                hasMore = !list.isEmpty();
                cursor = api.getRefreshIndex();
                break;
            default:
                hasMore = false;
                return java.util.Collections.emptyList();
        }
        if (cursor < 0 || (type != LIKES && list.isEmpty())) hasMore = false;
        return list;
    }
}
