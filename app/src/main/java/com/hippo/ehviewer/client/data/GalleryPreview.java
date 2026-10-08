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

package com.hippo.ehviewer.client.data;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.widget.LocalImageLoader;
import com.hippo.unifile.UniFile;
import com.hippo.widget.LoadImageView;

import java.io.File;

public class GalleryPreview implements Parcelable {

    String imageKey;
    String imageUrl;
    String pageUrl;
    int position;
    int offsetX = Integer.MIN_VALUE;
    int offsetY = Integer.MIN_VALUE;
    int clipWidth = Integer.MIN_VALUE;
    int clipHeight = Integer.MIN_VALUE;
    private UniFile localFile;

    public int getPosition() {
        return position;
    }

    public void load(LoadImageView view) {
        UniFile local = getLocalFile();
        if (local != null) {
            LocalImageLoader.load(view, local);
            return;
        }
        view.setClip(offsetX, offsetY, clipWidth, clipHeight);
        view.load(imageKey, imageUrl);
    }

    public static GalleryPreview fromLocalFile(int position, UniFile file) {
        GalleryPreview preview = new GalleryPreview();
        preview.position = position;
        preview.localFile = file;
        preview.imageUrl = file.getUri().toString();
        return preview;
    }

    public UniFile getLocalFile() {
        if (localFile != null) return localFile;
        if (imageUrl == null) return null;
        Uri uri = Uri.parse(imageUrl);
        if (!"file".equals(uri.getScheme()) && !"content".equals(uri.getScheme())) return null;
        localFile = "file".equals(uri.getScheme()) ? UniFile.fromFile(new File(uri.getPath()))
                : UniFile.fromUri(EhApplication.getInstance(), uri);
        return localFile;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.imageUrl);
        dest.writeString(this.pageUrl);
        dest.writeInt(this.position);
        dest.writeInt(this.offsetX);
        dest.writeInt(this.offsetY);
        dest.writeInt(this.clipWidth);
        dest.writeInt(this.clipHeight);
    }

    public GalleryPreview() {
    }

    protected GalleryPreview(Parcel in) {
        this.imageUrl = in.readString();
        this.pageUrl = in.readString();
        this.position = in.readInt();
        this.offsetX = in.readInt();
        this.offsetY = in.readInt();
        this.clipWidth = in.readInt();
        this.clipHeight = in.readInt();
    }

    public static final Creator<GalleryPreview> CREATOR = new Creator<GalleryPreview>() {
        @Override
        public GalleryPreview createFromParcel(Parcel source) {
            return new GalleryPreview(source);
        }

        @Override
        public GalleryPreview[] newArray(int size) {
            return new GalleryPreview[size];
        }
    };
}
