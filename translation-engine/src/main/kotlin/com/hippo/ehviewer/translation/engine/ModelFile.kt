package com.hippo.ehviewer.translation.engine

import android.os.ParcelFileDescriptor
import java.io.File

/** Opens a fresh read lease for each load; external descriptors never become guessed paths. */
class ModelFile private constructor(val path: String?, private val openDescriptor: (() -> ParcelFileDescriptor)?) {
    fun <T> read(block: (String?, ParcelFileDescriptor?) -> T): T =
        if (openDescriptor == null) block(checkNotNull(path), null)
        else openDescriptor.invoke().use { block(null, it) }

    companion object {
        fun local(path: String) = ModelFile(path, null)
        fun external(open: () -> ParcelFileDescriptor) = ModelFile(null, open)
    }
}

data class NcnnModelFiles(val param: ModelFile, val weights: ModelFile, val mixedParam: ModelFile? = null) {
    companion object {
        fun local(param: String, weights: String = param.removeSuffix(".param") + ".bin"): NcnnModelFiles {
            val base = param.replace("_mixed.ncnn.param", ".ncnn.param")
            val mixed = base.removeSuffix(".ncnn.param") + "_mixed.ncnn.param"
            return NcnnModelFiles(ModelFile.local(base), ModelFile.local(weights),
                mixed.takeIf { it != base && File(it).isFile }?.let(ModelFile::local))
        }
    }
}
