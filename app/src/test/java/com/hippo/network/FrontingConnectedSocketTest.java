package com.hippo.network;

import static org.junit.Assert.*;
import android.app.Application;
import com.hippo.ehviewer.Settings;
import java.net.ServerSocket;
import java.net.Socket;
import javax.net.ssl.SSLSocketFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class FrontingConnectedSocketTest {
    @Test public void frontingPreservesOkHttpTcpSocketOnBothFactories() throws Exception {
        Settings.initialize(RuntimeEnvironment.getApplication()); Settings.putDF(true);
        for (SSLSocketFactory factory : new SSLSocketFactory[] {
                new EhSSLSocketFactory(), new EhSSLSocketFactoryLowSDK((SSLSocketFactory) SSLSocketFactory.getDefault())}) {
            try (ServerSocket server = new ServerSocket(0);
                 Socket connected = new Socket("127.0.0.1", server.getLocalPort());
                 Socket accepted = server.accept();
                 Socket tls = factory.createSocket(connected, "exhentai.org", server.getLocalPort(), true)) {
                assertFalse("TLS wrapping must preserve the cancellable connected socket", connected.isClosed());
                assertEquals(connected.getLocalPort(), tls.getLocalPort());
                tls.close();
                assertTrue("autoClose must still close the underlying socket", connected.isClosed());
            }
        }
    }
}
