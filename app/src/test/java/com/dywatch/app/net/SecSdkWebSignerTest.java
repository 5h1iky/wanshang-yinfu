package com.dywatch.app.net;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SecSdkWebSignerTest {
    @Test public void signsExactReferencePreimage() {
        SecSdkWebSigner.Result result = SecSdkWebSigner.signAt(
                "a=1&a_bogus=x", "uifid=u; s_v_web_id=v", 1700000000L);
        assertEquals("a=1&a_bogus=x&verifyFp=v&fp=v&uifid=u&timestamp=1700000000"
                        + "&x-secsdk-web-signature=27edd16dd471d1258de83fa99f421f6d",
                result.query);
        assertEquals("27edd16dd471d1258de83fa99f421f6d",
                result.headers.get("x-secsdk-web-signature"));
    }

    @Test public void doesNotInventVisitorWhenCookieIsMissing() {
        SecSdkWebSigner.Result result = SecSdkWebSigner.signAt(
                "a=1&a_bogus=x", "sessionid=secret", 1700000000L);
        assertEquals("a=1&a_bogus=x", result.query);
        assertTrue(result.headers.isEmpty());
    }
}
