package com.dywatch.app.sign;

// Signer 的 JVM 单元测试：不启动 Android，直接验证 App 内签名能力（M2 数据层前置）。
// JS 源码从 app/src/main/assets/sign/ 读取（与打进 APK 的完全一致）。

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SignerTest {

    private static String read(String name) throws Exception {
        File f = new File("src/main/assets/sign/" + name);
        assertTrue("缺少 JS 源文件: " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void makeABogus_generatesSignature() throws Exception {
        List<String> js = Arrays.asList(read("utils.js"), read("sm3.js"), read("vm_decode.js"));
        Signer signer = new Signer(js);
        String query = "device_platform=webapp&aid=6383&channel=channel_pc_web&count=10";
        String ab = signer.makeABogus(query);
        assertNotNull("a_bogus 为 null", ab);
        assertTrue("a_bogus 长度异常: " + ab.length(), ab.length() > 20);
    }

    @Test
    public void makeABogus_differentQueryDifferentSig() throws Exception {
        List<String> js = Arrays.asList(read("utils.js"), read("sm3.js"), read("vm_decode.js"));
        Signer signer = new Signer(js);
        String a = signer.makeABogus("device_platform=webapp&aid=6383&count=10");
        String b = signer.makeABogus("device_platform=webapp&aid=6383&count=11");
        assertNotNull(a);
        assertNotNull(b);
        assertTrue("不同 query 不应产出相同签名", !a.equals(b));
    }
}
