package com.dywatch.app.feed;

import com.dywatch.app.net.DouyinApi;
import com.dywatch.app.net.PageResult;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
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

    /** 喜欢列表的跨页去重/游标状态；只在 LIKES 模式下使用。 */
    private LikesPager mLikesPager;

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

    /** 把「我的」页已经展示的喜欢条目放进跨页去重集合，避免播放页续拉时重复。 */
    public void seedLikes(Collection<FeedVideo> alreadyShown) {
        if (type != LIKES) return;
        if (mLikesPager == null) mLikesPager = new LikesPager(cursor, hasMore);
        mLikesPager.seed(alreadyShown);
    }

    /** 只由 FeedActivity 单个后台线程串行调用。 */
    public List<FeedVideo> next(DouyinApi api) throws IOException {
        if (!hasMore) return java.util.Collections.emptyList();
        List<FeedVideo> list;
        PageResult<FeedVideo> page;
        switch (type) {
            case USER:
                list = api.fetchUserPosts(key, cursor);
                hasMore = api.lastPostsHasMore;
                cursor = api.lastPostsCursor;
                break;
            case COLLECTION:
                page = api.fetchCollectionVideos(key, cursor);
                list = page.items();
                hasMore = page.hasMore();
                cursor = page.cursor();
                break;
            case ALL_COLLECTIONS:
                page = api.fetchAllCollectionVideos(cursor);
                list = page.items();
                hasMore = page.hasMore();
                cursor = page.cursor();
                break;
            case SEARCH:
                page = api.searchVideos(keyword, (int) cursor, searchId);
                list = page.items();
                hasMore = page.hasMore();
                cursor = page.cursor();
                searchId = page.searchId();
                break;
            case LIKES:
                // 2026-11-07 审计 P1-4：游标只认服务端 max_cursor，不再 cursor += list.size()；
                // 去重与“游标不前进就停”由 LikesPager 统一处理。
                if (mLikesPager == null) mLikesPager = new LikesPager(cursor, hasMore);
                String secUid = api.fetchSelfSecUid();
                page = api.fetchFavorite(secUid, mLikesPager.cursor());
                list = mLikesPager.accept(page);
                cursor = mLikesPager.cursor();
                hasMore = mLikesPager.hasMore();
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
