package com.hippo.widget;

import android.app.Application;
import android.graphics.Bitmap;
import com.hippo.conaco.Conaco;
import com.hippo.ehviewer.ImageBitmapHelper;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.lib.image.Image;
import okhttp3.OkHttpClient;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PreviewImageLifetimeTest {
    @Test public void sheetSurvivesCacheEvictionUntilBothClippedViewsReleaseIt() {
        ImageBitmapHelper helper = new ImageBitmapHelper();
        String key = EhCacheKeyFactory.getNormalPreviewKey("https://a.hath.network/c2/token/123-0.webp");
        Bitmap bitmap = Bitmap.createBitmap(2000, 300, Bitmap.Config.ARGB_8888);
        Image image = Image.create(bitmap);
        assertNotNull(image);
        assertTrue(helper.useMemoryCache(key, image));
        assertFalse(helper.useMemoryCache("cover", image));
        Conaco.Builder<Image> builder = new Conaco.Builder<>();
        builder.hasMemoryCache = true;
        builder.memoryCacheMaxSize = 1024 * 1024;
        builder.sharedMemoryCacheMaxSize = ImageBitmapHelper.PREVIEW_CACHE_BYTES;
        builder.sharedMemoryCacheKey = EhCacheKeyFactory::isNormalPreviewKey;
        builder.objectHelper = helper;
        builder.okHttpClient = new OkHttpClient();
        Conaco<Image> loader = builder.build();
        loader.getBeerBelly().putToMemory(key, image);

        LoadImageView first = new LoadImageView(RuntimeEnvironment.getApplication(), false);
        LoadImageView second = new LoadImageView(RuntimeEnvironment.getApplication(), false);
        first.setClip(0, 0, 200, 300);
        second.setClip(200, 0, 200, 300);
        assertTrue(first.onGetValue(image, Conaco.SOURCE_MEMORY));
        assertTrue(second.onGetValue(image, Conaco.SOURCE_MEMORY));
        loader.getBeerBelly().clearMemory();
        assertFalse(image.isRecycled());
        first.unload();
        assertFalse(image.isRecycled());
        assertNotNull(second.getDrawable());
        // Rebinding the same value must not add an unbalanced reference.
        assertTrue(second.onGetValue(image, Conaco.SOURCE_MEMORY));
        second.unload();
        assertTrue(image.isRecycled());
        assertTrue(bitmap.isRecycled());
    }
}
