package com.floss.microscope.infrastructure.gpu

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES31
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardware-agnostic GPU Compute Accelerator using standard Android SDK Headless EGL14 + OpenGL ES 3.1
 * Compute Shaders (`#version 310 es`), with automatic OpenCV CPU fallback.
 */
@Singleton
class VulkanAccelerator @Inject constructor() {

    fun executeEdgePreservingDetailBoost(bgrMat: Mat): Mat {
        return try {
            runGles31ComputeUnsharp(bgrMat)
        } catch (t: Throwable) {
            val blurred = Mat()
            val out = Mat()
            Imgproc.GaussianBlur(bgrMat, blurred, Size(0.0, 0.0), 1.4)
            Core.addWeighted(bgrMat, 1.42, blurred, -0.42, 0.0, out)
            blurred.release()
            out
        }
    }

    private fun runGles31ComputeUnsharp(bgrMat: Mat): Mat {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw IllegalStateException("EGL14 initialization failed")
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        val config = configs[0] ?: throw IllegalStateException("No EGLConfig found")

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
        EGL14.eglMakeCurrent(display, surface, surface, context)

        try {
            val w = bgrMat.cols()
            val h = bgrMat.rows()
            val numPixels = w * h

            val rgbaMat = Mat()
            Imgproc.cvtColor(bgrMat, rgbaMat, Imgproc.COLOR_BGR2RGBA)
            val byteLen = numPixels * 4
            val rawBytes = ByteArray(byteLen)
            rgbaMat.get(0, 0, rawBytes)
            rgbaMat.release()

            val inBuf = ByteBuffer.allocateDirect(byteLen).order(ByteOrder.nativeOrder()).apply {
                put(rawBytes)
                position(0)
            }

            val shaderSrc = """
                #version 310 es
                layout(local_size_x = 64) in;
                layout(std430, binding = 0) readonly buffer InBuf { uint data[]; } inImg;
                layout(std430, binding = 1) writeonly buffer OutBuf { uint data[]; } outImg;
                uniform int uTotalPixels;
                uniform int uWidth;

                vec3 unpackRgb(uint p) {
                    return vec3(float(p & 255u), float((p >> 8) & 255u), float((p >> 16) & 255u));
                }

                void main() {
                    int idx = int(gl_GlobalInvocationID.x);
                    if (idx >= uTotalPixels) return;
                    int x = idx % uWidth;
                    int y = idx / uWidth;
                    int h = uTotalPixels / uWidth;

                    vec3 c = unpackRgb(inImg.data[idx]);
                    if (x > 0 && x < uWidth - 1 && y > 0 && y < h - 1) {
                        vec3 l = unpackRgb(inImg.data[idx - 1]);
                        vec3 r = unpackRgb(inImg.data[idx + 1]);
                        vec3 u = unpackRgb(inImg.data[idx - uWidth]);
                        vec3 d = unpackRgb(inImg.data[idx + uWidth]);
                        vec3 blur = 0.25 * (l + r + u + d);
                        c = clamp(c + 0.42 * (c - blur), 0.0, 255.0);
                    }
                    uint r8 = uint(c.r);
                    uint g8 = uint(c.g) << 8;
                    uint b8 = uint(c.b) << 16;
                    outImg.data[idx] = r8 | g8 | b8 | 0xFF000000u;
                }
            """.trimIndent()

            val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
            GLES31.glShaderSource(shader, shaderSrc)
            GLES31.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) throw IllegalStateException("Compute shader compile failed")

            val prog = GLES31.glCreateProgram()
            GLES31.glAttachShader(prog, shader)
            GLES31.glLinkProgram(prog)
            GLES31.glUseProgram(prog)

            val ssbo = IntArray(2)
            GLES31.glGenBuffers(2, ssbo, 0)
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssbo[0])
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, byteLen, inBuf, GLES31.GL_STATIC_DRAW)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ssbo[0])

            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssbo[1])
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, byteLen, null, GLES31.GL_DYNAMIC_READ)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, ssbo[1])

            GLES31.glUniform1i(GLES31.glGetUniformLocation(prog, "uTotalPixels"), numPixels)
            GLES31.glUniform1i(GLES31.glGetUniformLocation(prog, "uWidth"), w)

            val groups = (numPixels + 63) / 64
            GLES31.glDispatchCompute(groups, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssbo[1])
            val mapped = GLES31.glMapBufferRange(
                GLES31.GL_SHADER_STORAGE_BUFFER, 0, byteLen, GLES31.GL_MAP_READ_BIT
            ) as ByteBuffer
            mapped.get(rawBytes)
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)

            GLES31.glDeleteBuffers(2, ssbo, 0)
            GLES31.glDeleteProgram(prog)
            GLES31.glDeleteShader(shader)

            val outRgba = Mat(h, w, CvType.CV_8UC4)
            outRgba.put(0, 0, rawBytes)
            val outBgr = Mat()
            Imgproc.cvtColor(outRgba, outBgr, Imgproc.COLOR_RGBA2BGR)
            outRgba.release()
            return outBgr
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }
}

@Singleton
class NNAPIWrapper @Inject constructor() {
    fun refineMicrostructure(bgrMat: Mat): Mat {
        val floatMat = Mat()
        bgrMat.convertTo(floatMat, CvType.CV_32FC3)

        val lap = Mat()
        Imgproc.Laplacian(floatMat, lap, CvType.CV_32FC3, 3)

        val refined = Mat()
        Core.addWeighted(floatMat, 1.0, lap, -0.10, 0.0, refined)

        val out8U = Mat()
        refined.convertTo(out8U, CvType.CV_8UC3)

        floatMat.release()
        lap.release()
        refined.release()
        return out8U
    }
}
