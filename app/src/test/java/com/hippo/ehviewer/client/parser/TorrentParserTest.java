/*
 * Copyright 2019 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.client.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.TorrentInfo;

import java.io.IOException;
import java.io.InputStream;

import okio.BufferedSource;
import okio.Okio;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@Config(manifest = Config.NONE, application = android.app.Application.class)
@RunWith(RobolectricTestRunner.class)
public class TorrentParserTest {

    @Test
    public void testParse() throws IOException {
        InputStream resource = TorrentParserTest.class.getResourceAsStream("torrentList.html");
        BufferedSource source = Okio.buffer(Okio.source(resource));
        String body = source.readUtf8();

        TorrentInfo[] result = TorrentParser.parse(body);
        assertEquals(2, result.length);

        assertEquals("2026-04-26 05:14", result[0].posted);
        assertEquals("2026-04-26 05:14", result[1].posted);
        assertEquals("https://ehtracker.org/get/3905209/96d5199ddac01181000340a59bb84c218faf0b16.torrent", result[0].url);
        assertEquals("https://ehtracker.org/get/3905209/713846c3ddf05826d4443790a5b6619eae734d71.torrent", result[1].url);
        assertTrue(result[0].name.contains("Part 2"));
        assertTrue(result[1].name.contains("1280x"));
        assertEquals("252.0 MiB", result[0].size);
        assertEquals("13", result[0].seeds);
        assertEquals("4", result[0].peers);
        assertEquals("50", result[0].downloads);
        assertEquals("14.21 MiB", result[1].size);
        assertEquals("11", result[1].seeds);
        assertEquals("2", result[1].peers);
        assertEquals("33", result[1].downloads);
        assertTrue(!result[0].name.contains("\n"));
    }

    @Test
    public void missingFieldsDoNotDropTorrentOrBorrowNextTorrentStats() {
        String body = "<form><table><tr><td><span>Seeds:</span> 0</td></tr>"
                + "<tr><td colspan='5'><a href='https://example.org/a.torrent?p=secret'>"
                + "A &amp; B.zip</a></td></tr></table></form>"
                + "<form><table><tr><td><span>Seeds:</span> 12</td>"
                + "<td><span>Peers:</span> 5</td><td><span>Downloads:</span> 1,234</td></tr>"
                + "<tr><td colspan='5'><a href='https://example.org/b.torrent'>B.zip</a>"
                + "</td></tr></table></form>";

        TorrentInfo[] result = TorrentParser.parse(body);

        assertEquals(2, result.length);
        assertEquals("A & B.zip", result[0].name);
        assertEquals("https://example.org/a.torrent", result[0].url);
        assertEquals("0", result[0].seeds);
        assertEquals("", result[0].peers);
        assertEquals("", result[0].downloads);
        assertEquals("", result[0].size);
        assertEquals("", result[0].posted);
        assertEquals("12", result[1].seeds);
        assertEquals("5", result[1].peers);
        assertEquals("1,234", result[1].downloads);
    }
}
