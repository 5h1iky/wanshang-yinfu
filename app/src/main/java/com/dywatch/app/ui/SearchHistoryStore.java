package com.dywatch.app.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** 只存本机最近十条搜索词；不将搜索历史上传到自己的服务。 */
public final class SearchHistoryStore {
    private SearchHistoryStore() { }
    private static final String KEY = "keywords";

    public static List<String> read(Context context) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(context).getString(KEY, "[]"));
            for (int i = 0; i < arr.length() && i < 10; i++) out.add(arr.optString(i));
        } catch (JSONException ignored) { }
        return out;
    }

    public static void add(Context context, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        List<String> out = read(context);
        out.remove(keyword.trim());
        out.add(0, keyword.trim());
        while (out.size() > 10) out.remove(out.size() - 1);
        write(context, out);
    }

    public static void clear(Context context) { prefs(context).edit().remove(KEY).apply(); }

    private static void write(Context context, List<String> items) {
        JSONArray arr = new JSONArray();
        for (String item : items) arr.put(item);
        prefs(context).edit().putString(KEY, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("search_history", Context.MODE_PRIVATE);
    }
}
