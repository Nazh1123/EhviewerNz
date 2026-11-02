/*
 * Copyright 2016 Hippo Seven
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

import com.hippo.ehviewer.client.data.TorrentInfo;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

public class TorrentParser {

    public static TorrentInfo[] parse(String body) {
        List<TorrentInfo> torrentList = new ArrayList<>();
        for (Element block : Jsoup.parse(body).select("form")) {
            Element link = block.selectFirst("td[colspan=5] a[href]");
            if (link == null) {
                continue;
            }
            // Remove ?p= to make torrent redistributable
            String url = link.attr("href").trim();
            int index = url.indexOf("?p=");
            if (index != -1) {
                url = url.substring(0, index);
            }
            TorrentInfo item = new TorrentInfo(url, link.text(), field(block, "Posted:"),
                    field(block, "Size:"), field(block, "Seeds:"), field(block, "Peers:"),
                    field(block, "Downloads:"));
            torrentList.add(item);
        }
        return torrentList.toArray(new TorrentInfo[0]);
    }

    private static String field(Element block, String label) {
        for (Element cell : block.select("td")) {
            String text = cell.text();
            if (text.startsWith(label)) {
                return text.substring(label.length()).trim();
            }
        }
        return "";
    }
}
