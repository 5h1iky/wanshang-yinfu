# 混淆/裁剪规则
#
# ⚠️ 2026-10-02 决定：**仍然保持 minifyEnabled = false**（代码审计把"混淆未开"列为低危项）。
#
# 为什么这次不开（这是有意识的取舍，不是忘了）：
#   R8 一旦开启，受影响的是三个"只在真机、且必须登录抖音才能走到"的反射面：
#     ① Rhino —— 签名的 JS 全靠它跑，内部大量反射；
#     ② WebView 的 @JavascriptInterface —— 私信/评论/点赞全走它，名字被改就静默失效；
#     ③ dkplayer 的播放器/渲染工厂 —— 出问题只表现成"黑屏"。
#   我这次只有一台**手机**、且没有抖音账号可扫码登录（无法验证私信与互动链路）。
#   在这种情况下打开混淆 = 把"可能只在手表+登录态下才炸"的风险直接发出去。
#   没有验证手段就不做不可回退的优化 —— 这是本项目一贯的口径。
#
# 所以这里先把**规则补全**：等哪天真手表 + 真账号把登录/私信/评论/点赞全过一遍，
# 把 build.gradle 里那行改成 true 即可，不必再回来补规则（原来这个文件是空的，
# 注释还写着"上线前再补"——那份债现在还上）。
#
# 已验证事实（2026-10-02 静态核查）：
#   · App 代码里**没有** Gson 反射反序列化（无 fromJson(..., X.class)），JSON 全走
#     JsonParser/JsonObject 手工取值 → 不需要为数据模型加 keep 规则；
#   · 反射只剩三处框架调用（VerticalViewPager 取 ViewGroup.setChildrenDrawingOrderEnabled、
#     PlayerUtils 取 ActivityThread.currentApplication、CutoutUtil 取厂商刘海接口），
#     目标都是**系统类**，R8 不会改名，无需 keep。

# ---- Rhino（签名 JS 引擎）----
# 内部用反射调用宿主对象与解释器，整包保留最稳；顺带压掉它的可选依赖告警。
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**

# ---- WebView JS 接口 ----
# Android 默认规则里已包含 @JavascriptInterface 的 keep，这里显式再写一遍：
# 这两处一旦被裁/改名，症状是"私信发不出去、点赞没反应"，且不会有任何异常。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.dywatch.app.chat.ChatEngine {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.dywatch.app.login.LoginActivity$FitBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- 调试可读性 ----
# 聊天/登录/引擎相关类的名字保留：这三块出问题时要靠堆栈定位（手表上没法调试）。
-keepnames class com.dywatch.app.chat.** { *; }
-keepnames class com.dywatch.app.login.** { *; }
-keepnames class com.dywatch.app.sign.** { *; }
