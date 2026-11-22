package filmlab.app

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * カメラのプレビュー（外部テクスチャ）に、フィルムの 3D LUT と粒子を GPU でかけて表示する。
 */
class FilmGlRenderer(
    private val onSurfaceTexture: (SurfaceTexture) -> Unit,
    private val requestRender: () -> Unit,
) : GLSurfaceView.Renderer {
    private var oesTex = 0
    private var lutTex = 0
    private var prog = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private var viewW = 1
    private var viewH = 1
    private var frame = 0

    /** 表示する映像の縦横比（幅/高さ）。縦持ちで 4:3 センサーなら 3/4 */
    @Volatile var contentAspect = 3f / 4f

    @Volatile var grain = 0f

    @Volatile var grainChroma = 0f

    @Volatile var filmEnabled = true

    @Volatile private var pendingLut: ByteArray? = null
    @Volatile private var pendingLutSize = 33
    private var lutSize = 33

    @Volatile private var snapshotCallback: ((IntArray) -> Unit)? = null

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }

    fun setLut(data: ByteArray, n: Int) {
        pendingLutSize = n
        pendingLut = data
        requestRender()
    }

    /** 次のフレームで LUT なしの縮小画像（ARGB, SNAP_W x SNAP_H）を取得する（自動露出の基準用） */
    fun requestSnapshot(cb: (IntArray) -> Unit) {
        snapshotCallback = cb
        requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val t = IntArray(2)
        GLES30.glGenTextures(2, t, 0)
        oesTex = t[0]
        lutTex = t[1]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        // LUT が届くまでは恒等変換
        uploadLut(identityLut(2), 2)

        prog = buildProgram(VS, FS)
        val st = SurfaceTexture(oesTex)
        st.setOnFrameAvailableListener { requestRender() }
        surfaceTexture = st
        onSurfaceTexture(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            return
        }
        st.getTransformMatrix(texMatrix)
        pendingLut?.let {
            uploadLut(it, pendingLutSize)
            pendingLut = null
        }
        frame++

        snapshotCallback?.let { cb ->
            snapshotCallback = null
            cb(snapshot())
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        // 縦横比を保って画面上部寄りに配置
        val a = contentAspect
        var w = viewW
        var h = (viewW / a).toInt()
        if (h > viewH) {
            h = viewH
            w = (viewH * a).toInt()
        }
        val x = (viewW - w) / 2
        val y = viewH - h - (viewH - h) / 4
        GLES30.glViewport(x, y, w, h)
        draw(if (filmEnabled) 0 else 1)
    }

    private fun draw(mode: Int) {
        GLES30.glUseProgram(prog)
        val aPos = GLES30.glGetAttribLocation(prog, "aPos")
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uTex"), 1, false, texMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uCam"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uLut"), 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uLutScale"), (lutSize - 1f) / lutSize)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uLutOffset"), 0.5f / lutSize)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uGrain"), if (mode == 0) grain else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uChroma"), grainChroma)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uFrame"), (frame % 997).toFloat())
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uMode"), mode)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPos)
    }

    private fun snapshot(): IntArray {
        val fb = IntArray(1)
        val tex = IntArray(1)
        GLES30.glGenFramebuffers(1, fb, 0)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, SNAP_W, SNAP_H, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0)
        GLES30.glViewport(0, 0, SNAP_W, SNAP_H)
        draw(1)
        val buf = ByteBuffer.allocateDirect(SNAP_W * SNAP_H * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, SNAP_W, SNAP_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, fb, 0)
        GLES30.glDeleteTextures(1, tex, 0)
        val out = IntArray(SNAP_W * SNAP_H)
        for (i in out.indices) {
            val r = buf.get(i * 4).toInt() and 0xFF
            val g = buf.get(i * 4 + 1).toInt() and 0xFF
            val b = buf.get(i * 4 + 2).toInt() and 0xFF
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    private fun uploadLut(data: ByteArray, n: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val bb = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder())
        bb.put(data).position(0)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, n, n, n, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, bb)
        lutSize = n
    }

    private fun identityLut(n: Int): ByteArray {
        val d = ByteArray(n * n * n * 3)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val i = ((b * n + g) * n + r) * 3
            d[i] = (r * 255 / (n - 1)).toByte()
            d[i + 1] = (g * 255 / (n - 1)).toByte()
            d[i + 2] = (b * 255 / (n - 1)).toByte()
        }
        return d
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e("FilmLab", "shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("FilmLab", "link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        const val SNAP_W = 192
        const val SNAP_H = 256

        private const val VS = """#version 300 es
in vec2 aPos;
uniform mat4 uTex;
out vec2 vUv;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vec2 uv = aPos * 0.5 + 0.5;
    vUv = (uTex * vec4(uv, 0.0, 1.0)).xy;
}
"""

        private const val FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp sampler3D;
uniform samplerExternalOES uCam;
uniform sampler3D uLut;
uniform float uLutScale;
uniform float uLutOffset;
uniform float uGrain;
uniform float uChroma;
uniform float uFrame;
uniform int uMode;
in vec2 vUv;
out vec4 fragColor;

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec3 c = texture(uCam, vUv).rgb;
    if (uMode == 1) { fragColor = vec4(c, 1.0); return; }
    vec3 f = texture(uLut, c * uLutScale + uLutOffset).rgb;
    if (uGrain > 0.0) {
        vec2 q = gl_FragCoord.xy + vec2(uFrame * 17.0, uFrame * 31.0);
        float n = (hash(q) + hash(q + 3.7) + hash(q + 7.1) + hash(q + 11.3) - 2.0) * 1.73;
        vec3 nc = (vec3(hash(q + 1.1), hash(q + 2.2), hash(q + 5.5)) - 0.5) * 3.46;
        float L = dot(f, vec3(0.2126, 0.7152, 0.0722));
        float a = uGrain * (4.0 * L * (1.0 - L) + 0.15);
        f += a * (n * (1.0 - uChroma) + nc * uChroma);
    }
    fragColor = vec4(clamp(f, 0.0, 1.0), 1.0);
}
"""
    }
}
