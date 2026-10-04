package com.hippo.ehviewer.translation

import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.hippo.ehviewer.translation.engine.LlmTranslator
import com.hippo.ehviewer.translation.engine.NativeLlm
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real uncached JNI under a CPU restriction, including changes inside a request.
 * Instrumentation itself can exempt an app from OEM background policy, so constrain
 * the calling thread explicitly as well as testing the actual HOME lifecycle. */
@RunWith(AndroidJUnit4::class)
class NativeCpuBackgroundDeviceTest {
    private external fun setAffinity(cpus: IntArray)
    private external fun policyThreads(requested: Int, allowed: Int): Int

    @Test fun cpuPolicyBoundsParallelismAndFailsSafeWhenAffinityIsUnknown() {
        assumeTrue(InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.flags and
            ApplicationInfo.FLAG_DEBUGGABLE != 0)
        System.loadLibrary("ehnz_translation_test")
        for (allowed in 0..16) {
            assertEquals(allowed.coerceIn(1, 4), policyThreads(4, allowed))
            assertEquals(1, policyThreads(1, allowed))
            assertEquals(allowed.coerceIn(1, 2), policyThreads(2, allowed))
            assertEquals(allowed.coerceIn(1, 4), policyThreads(Int.MAX_VALUE, allowed))
        }
        assertEquals(1, policyThreads(4, -1))
        assertEquals(1, policyThreads(0, 8))
    }

    @Test(timeout = 240000) fun cpuRestrictionsDuringPrefillAndGenerationDoNotStallOrLoseTheContext() =
        runBlocking<Unit>(Dispatchers.IO) {
            assumeTrue(InstrumentationRegistry.getArguments().getString("testNativeCpuBackground") == "true")
            assumeTrue(InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.flags and
                ApplicationInfo.FLAG_DEBUGGABLE != 0)
            System.loadLibrary("ehnz_translation_test")
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val options = TranslationSettings(context).read().copy(backend = TranslationBackend.NATIVE_LLM)
            assumeTrue(NativeModelStore(context).ready(options))
            val tid = Process.myTid()
            val status = File("/proc/self/task/$tid/status")
            fun mask() = status.useLines { lines ->
                BigInteger(lines.first { it.startsWith("Cpus_allowed:") }.substringAfter(':').trim().replace(",", ""), 16)
            }
            val original = mask()
            assumeTrue("Parallelism restoration requires four available CPUs", original.bitCount() >= 4)
            val restricted = BigInteger.ONE.shiftLeft(original.lowestSetBit)
            fun affinity(value: BigInteger) {
                setAffinity((0 until value.bitLength()).filter { value.testBit(it) }.toIntArray())
                assumeTrue("OEM ignores sched_setaffinity; use the non-instrumented service probe", value == mask())
            }
            val report = File(context.getExternalFilesDir(null), "translation-smoke/native-background-cpu.txt")
            report.parentFile!!.mkdirs()
            report.writeText("Uncached requests; CPU mask changes within one live GGUF context\n")
            val source = listOf("明日は学校へ行きます。", "この本を読んでください。")
            val messages = NativeTranslator.buildNumberedMessages(options, source)
            try {
                NativeLlm(NativeModelStore(context).file(options.nativeModelId).absolutePath).use { model ->
                    for (phase in listOf("restrictedPrefill", "restrictedGeneration", "restored")) {
                        val began = SystemClock.elapsedRealtime()
                        val prompt = model.begin(messages, cacheEnabled = false)
                        assertTrue(prompt > 0)
                        assertEquals(0, model.cachedPromptTokens())
                        if (phase == "restrictedPrefill") affinity(restricted)
                        val output = ByteArrayOutputStream()
                        var seenText = false
                        var sawRestricted = false
                        while (true) {
                            val piece = model.next() ?: break
                            val cpu = model.cpuState()
                            if (mask().bitCount() == 1) {
                                assertArrayEquals(intArrayOf(1, 1), cpu)
                                sawRestricted = true
                            } else assertEquals(4, cpu[0])
                            output.write(piece)
                            if (piece.isNotEmpty() && !seenText) {
                                seenText = true
                                report.appendText("$phase firstTextMs=${SystemClock.elapsedRealtime() - began} cpu=${cpu.toList()}\n")
                                if (phase == "restrictedPrefill") affinity(original)
                                if (phase == "restrictedGeneration") affinity(restricted)
                            }
                        }
                        if (phase != "restored") assertTrue(sawRestricted)
                        affinity(original)
                        val result = LlmTranslator(options.engineConfig().translator)
                            .parseResponse(source, output.toString("UTF-8"))
                        assertNull(result.error)
                        assertTrue(result.missingIndices.isEmpty())
                        result.translations.forEachIndexed { index, text -> assertNotEquals(source[index], text) }
                        report.appendText("$phase totalMs=${SystemClock.elapsedRealtime() - began} " +
                            "prompt=$prompt completion=${model.completionTokens()} cpu=${model.cpuState().toList()}\n")
                    }
                }
            } finally { affinity(original) }
        }
}
