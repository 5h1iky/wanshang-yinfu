package com.dywatch.app.chat.api;

import java.util.List;

/**
 * 会话解析（纯逻辑，JVM 可测）。
 *
 * 2026-11-07 审计：原 ApiChatPort 用 name + 双向 startsWith 找会话，
 * “张三”会命中“张三丰”，同名会话会串。这里把规则钉死：
 *   1. 稳定 key（conversation_id）精确命中优先；
 *   2. name 只做**唯一精确匹配**的兜底；
 *   3. 同名多个 → 返回 null（拒绝猜），绝不按前后缀猜。
 */
public final class ConvResolver {

    private ConvResolver() {}

    public static ImModels.Conv resolve(List<ImModels.Conv> convs, String key, String name) {
        if (convs == null || convs.isEmpty()) return null;

        String k = key == null ? "" : key.trim();
        if (!k.isEmpty()) {
            for (ImModels.Conv c : convs) {
                if (c != null && k.equals(c.id)) return c;
            }
        }

        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return null;

        ImModels.Conv only = null;
        for (ImModels.Conv c : convs) {
            if (c == null) continue;
            if (n.equals(c.displayName())) {
                if (only != null) return null;   // 同名会话：不能猜
                only = c;
            }
        }
        return only;
    }
}
