package org.snurkle.badgeturntable.media;

import android.content.Context;
import android.opengl.GLES20;

import androidx.annotation.OptIn;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

/**
 * {@link SideBars} for the spin video: the same fill, worked out per pixel on the GPU. Media3
 * hands effects upright frames (the decoder applies the recording's rotation first), so left
 * and right are the same as in the stills.
 */
@OptIn(markerClass = UnstableApi.class)
public final class SideBarsEffect implements GlEffect {
    private final SideBars bars;

    public SideBarsEffect(SideBars bars) {
        this.bars = bars;
    }

    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) throws VideoFrameProcessingException {
        return new Program(useHdr, bars);
    }

    @Override
    public boolean isNoOp(int inputWidth, int inputHeight) {
        return !bars.active();
    }

    private static final String VERTEX =
            "attribute vec4 aFramePosition;\n"
            + "varying vec2 vTex;\n"
            + "void main() {\n"
            + "  gl_Position = aFramePosition;\n"
            + "  vTex = aFramePosition.xy * 0.5 + 0.5;\n"
            + "}\n";

    // Mirrors SideBars.apply: NX x NY samples in a strip just inside each bar, averaged, fill
    // past the bar, and a feather inside it. x runs left to right; y's direction doesn't matter.
    private static final String FRAGMENT =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n"
            + "uniform sampler2D uTex;\n"
            + "uniform float uLeft;\n"      // first kept x (0 = no left bar)
            + "uniform float uRight;\n"     // first masked x on the right (1 = no right bar)
            + "uniform float uStrip;\n"
            + "uniform float uFeather;\n"
            + "uniform float uSpread;\n"    // half the row spread, as a fraction of the height
            + "varying vec2 vTex;\n"
            + "const int NX = " + SideBars.NX + ";\n"
            + "const int NY = " + SideBars.NY + ";\n"
            + "vec4 fill(float edge, float dir, float y) {\n"
            + "  vec4 sum = vec4(0.0);\n"
            + "  for (int j = 0; j < NY; j++) {\n"
            + "    float sy = clamp(y - uSpread + float(j) * 2.0 * uSpread / float(NY - 1), 0.0, 1.0);\n"
            + "    for (int i = 0; i < NX; i++) {\n"
            + "      float sx = clamp(edge + dir * (float(i) + 0.5) * uStrip / float(NX), 0.0, 1.0);\n"
            + "      sum += texture2D(uTex, vec2(sx, sy));\n"
            + "    }\n"
            + "  }\n"
            + "  return sum / float(NX * NY);\n"
            + "}\n"
            + "void main() {\n"
            + "  vec4 c = texture2D(uTex, vTex);\n"
            + "  float x = vTex.x;\n"
            + "  if (uLeft > 0.0 && x < uLeft + uFeather) {\n"
            + "    vec4 f = fill(uLeft, 1.0, vTex.y);\n"
            + "    c = x < uLeft ? f : mix(f, c, (x - uLeft) / uFeather);\n"
            + "  }\n"
            + "  if (uRight < 1.0 && x > uRight - uFeather) {\n"
            + "    vec4 f = fill(uRight, -1.0, vTex.y);\n"
            + "    c = x >= uRight ? f : mix(f, c, (uRight - x) / uFeather);\n"
            + "  }\n"
            + "  gl_FragColor = c;\n"
            + "}\n";

    private static final class Program extends BaseGlShaderProgram {
        private final GlProgram gl;
        private final SideBars bars;

        Program(boolean useHdr, SideBars bars) throws VideoFrameProcessingException {
            super(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1);
            this.bars = bars;
            try {
                gl = new GlProgram(VERTEX, FRAGMENT);
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
            gl.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
        }

        @Override
        public Size configure(int inputWidth, int inputHeight) {
            float left = bars.enabled ? bars.left : 0, right = bars.enabled ? bars.right : 0;
            gl.setFloatUniform("uLeft", left);
            gl.setFloatUniform("uRight", 1f - right);
            // Same pixel sizes as SideBars.apply, so the stills and the video match.
            gl.setFloatUniform("uStrip", Math.max(SideBars.NX, Math.round(SideBars.STRIP * inputWidth)) / (float) inputWidth);
            gl.setFloatUniform("uFeather", Math.max(2, Math.round(SideBars.FEATHER * inputWidth)) / (float) inputWidth);
            gl.setFloatUniform("uSpread", Math.round(SideBars.ROW_SPREAD * inputHeight / 2) / (float) inputHeight);
            return new Size(inputWidth, inputHeight);
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
            try {
                gl.use();
                gl.setSamplerTexIdUniform("uTex", inputTexId, /* texUnitIndex= */ 0);
                gl.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e, presentationTimeUs);
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            super.release();
            try {
                gl.delete();
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
        }
    }
}
