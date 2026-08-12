package com.hippo.ehviewer.ui.scene.gallery.detail

import android.content.Context
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.sync.GalleryDetailTagsSyncTask
import com.hippo.ehviewer.ui.scene.EhCallback
import com.hippo.scene.SceneFragment

class GetGalleryDetailListener(
    context: Context?,
    stageId: Int,
    sceneTag: String?,
    @Suppress("UNUSED_PARAMETER") resultMode: Int
) : EhCallback<GalleryDetailScene?, GalleryDetail?>(context, stageId, sceneTag) {
    override fun onSuccess(result: GalleryDetail?) {
        application.removeGlobalStuff(this)
        if (result==null){
            return
        }
        // Put gallery detail to cache
        EhApplication.getGalleryDetailCache(application).put(result.gid, result)

        // Add history
        EhDB.putHistoryInfo(result)

        // Save tags
        val syncTask = GalleryDetailTagsSyncTask(result)
        syncTask.start()

        // Notify success
        scene?.onGetGalleryDetailSuccess(result)
    }

    override fun onFailure(e: Exception) {
        application.removeGlobalStuff(this)
        scene?.onGetGalleryDetailFailure(e)
    }

    override fun onCancel() {
        application.removeGlobalStuff(this)
    }

    override fun isInstance(scene: SceneFragment?): Boolean {
        if (scene == null) {
            return false;
        }
        return scene is GalleryDetailScene
    }

    companion object {
        @JvmField
        var RESULT_DETAIL: Int = 1
    }
}
