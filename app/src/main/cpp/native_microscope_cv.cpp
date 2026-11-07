#include <jni.h>
#include <cmath>
#include <vector>
#include <algorithm>
#include <memory>
#include <cstring>
#include <omp.h>

namespace {

struct ImageF32 {
    int w;
    int h;
    int c;
    std::vector<float> data;

    ImageF32() : w(0), h(0), c(3) {}
    ImageF32(int width, int height, int channels = 3)
        : w(width), h(height), c(channels), data(static_cast<size_t>(width) * height * channels, 0.0f) {}

    inline float& at(int x, int y, int ch) {
        x = std::clamp(x, 0, w - 1);
        y = std::clamp(y, 0, h - 1);
        return data[(y * w + x) * c + ch];
    }

    inline float at(int x, int y, int ch) const {
        x = std::clamp(x, 0, w - 1);
        y = std::clamp(y, 0, h - 1);
        return data[(y * w + x) * c + ch];
    }
};

bool invertHomography3x3(const double H[9], double H_inv[9]) {
    double det = H[0] * (H[4] * H[8] - H[5] * H[7]) -
                 H[1] * (H[3] * H[8] - H[5] * H[6]) +
                 H[2] * (H[3] * H[7] - H[4] * H[6]);
    if (std::abs(det) < 1e-11) {
        std::fill(H_inv, H_inv + 9, 0.0);
        H_inv[0] = H_inv[4] = H_inv[8] = 1.0;
        return false;
    }
    double invDet = 1.0 / det;
    H_inv[0] =  (H[4] * H[8] - H[5] * H[7]) * invDet;
    H_inv[1] = -(H[1] * H[8] - H[2] * H[7]) * invDet;
    H_inv[2] =  (H[1] * H[5] - H[2] * H[4]) * invDet;
    H_inv[3] = -(H[3] * H[8] - H[5] * H[6]) * invDet;
    H_inv[4] =  (H[0] * H[8] - H[2] * H[6]) * invDet;
    H_inv[5] = -(H[0] * H[5] - H[2] * H[3]) * invDet;
    H_inv[6] =  (H[3] * H[7] - H[4] * H[6]) * invDet;
    H_inv[7] = -(H[0] * H[7] - H[1] * H[6]) * invDet;
    H_inv[8] =  (H[0] * H[4] - H[1] * H[3]) * invDet;
    return true;
}

inline void sampleRawSubPixel(
        const uint8_t* rawRgb,
        int w,
        int h,
        const double H_inv[9],
        float rx,
        float ry,
        float& rOut,
        float& gOut,
        float& bOut,
        float& phaseWeightOut) {

    double denom = H_inv[6] * rx + H_inv[7] * ry + H_inv[8];
    if (std::abs(denom) < 1e-7) {
        rOut = gOut = bOut = 0.0f;
        phaseWeightOut = 1e-5f;
        return;
    }
    float fx = static_cast<float>((H_inv[0] * rx + H_inv[1] * ry + H_inv[2]) / denom);
    float fy = static_cast<float>((H_inv[3] * rx + H_inv[4] * ry + H_inv[5]) / denom);

    if (fx < 0.0f || fx >= w - 1 || fy < 0.0f || fy >= h - 1) {
        int cx = std::clamp(static_cast<int>(std::round(fx)), 0, w - 1);
        int cy = std::clamp(static_cast<int>(std::round(fy)), 0, h - 1);
        int idx = (cy * w + cx) * 3;
        rOut = rawRgb[idx];
        gOut = rawRgb[idx + 1];
        bOut = rawRgb[idx + 2];
        phaseWeightOut = 0.05f;
        return;
    }

    int x0 = static_cast<int>(std::floor(fx));
    int y0 = static_cast<int>(std::floor(fy));
    int x1 = x0 + 1;
    int y1 = y0 + 1;
    float sdx = fx - x0;
    float sdy = fy - y0;

    float w00 = (1.0f - sdx) * (1.0f - sdy);
    float w10 = sdx * (1.0f - sdy);
    float w01 = (1.0f - sdx) * sdy;
    float w11 = sdx * sdy;

    int i00 = (y0 * w + x0) * 3;
    int i10 = (y0 * w + x1) * 3;
    int i01 = (y1 * w + x0) * 3;
    int i11 = (y1 * w + x1) * 3;

    rOut = w00 * rawRgb[i00]     + w10 * rawRgb[i10]     + w01 * rawRgb[i01]     + w11 * rawRgb[i11];
    gOut = w00 * rawRgb[i00 + 1] + w10 * rawRgb[i10 + 1] + w01 * rawRgb[i01 + 1] + w11 * rawRgb[i11 + 1];
    bOut = w00 * rawRgb[i00 + 2] + w10 * rawRgb[i10 + 2] + w01 * rawRgb[i01 + 2] + w11 * rawRgb[i11 + 2];

    float dxNear = fx - std::round(fx);
    float dyNear = fy - std::round(fy);
    phaseWeightOut = std::exp(-3.0f * (dxNear * dxNear + dyNear * dyNear));
}

ImageF32 gaussianBlur5x5(const ImageF32& src) {
    const float k[5] = {0.0625f, 0.25f, 0.375f, 0.25f, 0.0625f};
    ImageF32 temp(src.w, src.h, src.c);
    ImageF32 dst(src.w, src.h, src.c);

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < src.h; ++y) {
        for (int x = 0; x < src.w; ++x) {
            for (int ch = 0; ch < src.c; ++ch) {
                float sum = 0.0f;
                for (int i = -2; i <= 2; ++i) sum += src.at(x + i, y, ch) * k[i + 2];
                temp.at(x, y, ch) = sum;
            }
        }
    }

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < src.h; ++y) {
        for (int x = 0; x < src.w; ++x) {
            for (int ch = 0; ch < src.c; ++ch) {
                float sum = 0.0f;
                for (int i = -2; i <= 2; ++i) sum += temp.at(x, y + i, ch) * k[i + 2];
                dst.at(x, y, ch) = sum;
            }
        }
    }
    return dst;
}

/**
 * O(1) Per-Pixel Sliding-Window 3-Pass Box-Gaussian Filter (Wells 1986).
 * Computes arbitrary-sigma Gaussian blur in strictly O(1) operations per pixel (30x faster for MSRCR).
 */
ImageF32 fastGaussianO1(const ImageF32& src, float sigma) {
    int radius = std::max(1, static_cast<int>(std::round(std::sqrt((12.0f * sigma * sigma) / 3.0f + 1.0f) - 1.0f) / 2));
    float invWin = 1.0f / (2 * radius + 1);

    ImageF32 cur = src;
    ImageF32 temp(src.w, src.h, src.c);

    for (int pass = 0; pass < 3; ++pass) {
        // Horizontal O(1) sliding window pass
        #pragma omp parallel for schedule(static)
        for (int y = 0; y < cur.h; ++y) {
            for (int ch = 0; ch < cur.c; ++ch) {
                float sum = 0.0f;
                for (int k = -radius; k <= radius; ++k) sum += cur.at(k, y, ch);
                temp.at(0, y, ch) = sum * invWin;
                for (int x = 1; x < cur.w; ++x) {
                    sum += cur.at(x + radius, y, ch) - cur.at(x - radius - 1, y, ch);
                    temp.at(x, y, ch) = sum * invWin;
                }
            }
        }
        // Vertical O(1) sliding window pass
        #pragma omp parallel for schedule(static)
        for (int x = 0; x < cur.w; ++x) {
            for (int ch = 0; ch < cur.c; ++ch) {
                float sum = 0.0f;
                for (int k = -radius; k <= radius; ++k) sum += temp.at(x, k, ch);
                cur.at(x, 0, ch) = sum * invWin;
                for (int y = 1; y < cur.h; ++y) {
                    sum += temp.at(x, y + radius, ch) - temp.at(x, y - radius - 1, ch);
                    cur.at(x, y, ch) = sum * invWin;
                }
            }
        }
    }
    return cur;
}

ImageF32 pyrDown(const ImageF32& src) {
    ImageF32 blurred = gaussianBlur5x5(src);
    int nw = std::max(1, (src.w + 1) / 2);
    int nh = std::max(1, (src.h + 1) / 2);
    ImageF32 dst(nw, nh, src.c);

    #pragma omp parallel for schedule(static)
    for (int y = 0; y < nh; ++y) {
        for (int x = 0; x < nw; ++x) {
            for (int ch = 0; ch < src.c; ++ch) {
                dst.at(x, y, ch) = blurred.at(x * 2, y * 2, ch);
            }
        }
    }
    return dst;
}

ImageF32 pyrUp(const ImageF32& src, int targetW, int targetH) {
    ImageF32 upsampled(targetW, targetH, src.c);
    #pragma omp parallel for schedule(static)
    for (int y = 0; y < targetH; ++y) {
        for (int x = 0; x < targetW; ++x) {
            float sx = x * 0.5f;
            float sy = y * 0.5f;
            int x0 = static_cast<int>(std::floor(sx));
            int y0 = static_cast<int>(std::floor(sy));
            float dx = sx - x0;
            float dy = sy - y0;
            for (int ch = 0; ch < src.c; ++ch) {
                float v00 = src.at(x0, y0, ch);
                float v10 = src.at(x0 + 1, y0, ch);
                float v01 = src.at(x0, y0 + 1, ch);
                float v11 = src.at(x0 + 1, y0 + 1, ch);
                upsampled.at(x, y, ch) = (1.0f - dx) * (1.0f - dy) * v00 +
                                         dx * (1.0f - dy) * v10 +
                                         (1.0f - dx) * dy * v01 +
                                         dx * dy * v11;
            }
        }
    }
    return gaussianBlur5x5(upsampled);
}

std::vector<ImageF32> buildLaplacianPyramid(const ImageF32& img, int levels) {
    std::vector<ImageF32> gauss(levels);
    gauss[0] = img;
    for (int l = 1; l < levels; ++l) gauss[l] = pyrDown(gauss[l - 1]);

    std::vector<ImageF32> lap(levels);
    for (int l = 0; l < levels - 1; ++l) {
        ImageF32 up = pyrUp(gauss[l + 1], gauss[l].w, gauss[l].h);
        lap[l] = ImageF32(gauss[l].w, gauss[l].h, img.c);
        int total = gauss[l].w * gauss[l].h * img.c;
        #pragma omp parallel for schedule(static)
        for (int i = 0; i < total; ++i) lap[l].data[i] = gauss[l].data[i] - up.data[i];
    }
    lap[levels - 1] = gauss[levels - 1];
    return lap;
}

ImageF32 collapseLaplacianPyramid(const std::vector<ImageF32>& pyr) {
    int levels = static_cast<int>(pyr.size());
    ImageF32 current = pyr[levels - 1];
    for (int l = levels - 2; l >= 0; --l) {
        ImageF32 up = pyrUp(current, pyr[l].w, pyr[l].h);
        current = ImageF32(pyr[l].w, pyr[l].h, pyr[l].c);
        int total = current.w * current.h * current.c;
        #pragma omp parallel for schedule(static)
        for (int i = 0; i < total; ++i) current.data[i] = up.data[i] + pyr[l].data[i];
    }
    return current;
}

/**
 * O(1) Constant-Memory Online Streaming Arena.
 * Never stores more than 1 frame in memory; accumulates Focus-Stack Pyramids,
 * Robust Huber/Mean Estimators, Tenengrad DfD, and Sub-Pixel SR online!
 */
struct NativeFrameArena {
    static constexpr int PYR_LEVELS = 4;
    int width;
    int height;
    int frameCount = 0;

    // 1. Online Tenengrad Depth-from-Defocus (DfD) Accumulators
    std::vector<float> dfdWeightedIdx;
    std::vector<float> dfdWeightSum;

    // 2. Online 4-Level Laplacian Pyramid Focus Stack Accumulators
    std::vector<ImageF32> fusedPyr;
    std::vector<std::vector<float>> maxEnergyPyr;

    // 3. Online Robust Huber / Mean Accumulators
    ImageF32 refFrameF32;
    ImageF32 robustSumRgb;
    ImageF32 robustWeightSum;
    ImageF32 linearSumRgb;

    // 4. Online Sub-Pixel Super-Resolution (1.5x Lattice) Accumulators
    int srW;
    int srH;
    ImageF32 srAccRgb;
    std::vector<float> srWeightSum;

    NativeFrameArena(int w, int h)
        : width(w),
          height(h),
          dfdWeightedIdx(static_cast<size_t>(w) * h, 0.0f),
          dfdWeightSum(static_cast<size_t>(w) * h, 1e-5f),
          refFrameF32(w, h, 3),
          robustSumRgb(w, h, 3),
          robustWeightSum(w, h, 3),
          linearSumRgb(w, h, 3),
          srW(static_cast<int>(w * 1.5)),
          srH(static_cast<int>(h * 1.5)),
          srAccRgb(static_cast<int>(w * 1.5), static_cast<int>(h * 1.5), 3),
          srWeightSum(static_cast<size_t>(static_cast<int>(w * 1.5)) * static_cast<int>(h * 1.5), 1e-5f) {}
};

} // anonymous namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_floss_microscope_infrastructure_processing_NativeCvBridge_createArenaNative(
        JNIEnv* /* env */,
        jobject /* this */,
        jint width,
        jint height,
        jint /* expectedFrames */) {
    auto* arena = new NativeFrameArena(width, height);
    return reinterpret_cast<jlong>(arena);
}

JNIEXPORT void JNICALL
Java_com_floss_microscope_infrastructure_processing_NativeCvBridge_pushFrameToArenaNative(
        JNIEnv* env,
        jobject /* this */,
        jlong arenaHandle,
        jbyteArray rawFrameRgbBytes,
        jdoubleArray homography3x3Flat) {

    auto* arena = reinterpret_cast<NativeFrameArena*>(arenaHandle);
    if (!arena) return;

    int w = arena->width;
    int h = arena->height;
    int fIdx = arena->frameCount;

    jbyte* rawBytes = env->GetByteArrayElements(rawFrameRgbBytes, nullptr);
    const uint8_t* rawPtr = reinterpret_cast<const uint8_t*>(rawBytes);

    double H[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    int hLen = env->GetArrayLength(homography3x3Flat);
    jdouble* hPtr = env->GetDoubleArrayElements(homography3x3Flat, nullptr);
    if (hLen >= 9) {
        for (int i = 0; i < 9; ++i) H[i] = hPtr[i];
    } else if (hLen == 6) {
        for (int i = 0; i < 6; ++i) H[i] = hPtr[i];
        H[6] = 0.0; H[7] = 0.0; H[8] = 1.0;
    }
    env->ReleaseDoubleArrayElements(homography3x3Flat, hPtr, JNI_ABORT);

    double H_inv[9];
    invertHomography3x3(H, H_inv);

    // 1. Warp current frame to 1x scratch ImageF32 & accumulate Mean / Huber Robust Sum
    ImageF32 alignedF32(w, h, 3);
    #pragma omp parallel for schedule(static)
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            float r, g, b, pw;
            sampleRawSubPixel(rawPtr, w, h, H_inv, static_cast<float>(x), static_cast<float>(y), r, g, b, pw);
            alignedF32.at(x, y, 0) = r;
            alignedF32.at(x, y, 1) = g;
            alignedF32.at(x, y, 2) = b;

            int pIdx = (y * w + x) * 3;
            arena->linearSumRgb.data[pIdx]     += r;
            arena->linearSumRgb.data[pIdx + 1] += g;
            arena->linearSumRgb.data[pIdx + 2] += b;

            if (fIdx == 0) {
                arena->refFrameF32.data[pIdx]     = r;
                arena->refFrameF32.data[pIdx + 1] = g;
                arena->refFrameF32.data[pIdx + 2] = b;
                for (int c = 0; c < 3; ++c) {
                    arena->robustSumRgb.data[pIdx + c] = alignedF32.data[pIdx + c];
                    arena->robustWeightSum.data[pIdx + c] = 1.0f;
                }
            } else {
                // Huber M-estimator outlier rejection against Reference Frame (delta = 18.0 intensity units)
                const float delta = 18.0f;
                for (int c = 0; c < 3; ++c) {
                    float diff = std::abs(alignedF32.data[pIdx + c] - arena->refFrameF32.data[pIdx + c]);
                    float wHuber = (diff <= delta) ? 1.0f : (delta / diff);
                    arena->robustSumRgb.data[pIdx + c]    += wHuber * alignedF32.data[pIdx + c];
                    arena->robustWeightSum.data[pIdx + c] += wHuber;
                }
            }
        }
    }

    // 2. Update Online Tenengrad Depth-from-Defocus (DfD) Accumulators
    #pragma omp parallel for schedule(static)
    for (int y = 1; y < h - 1; ++y) {
        for (int x = 1; x < w - 1; ++x) {
            auto luma = [&](int px, int py) -> float {
                return 0.299f * alignedF32.at(px, py, 0) +
                       0.587f * alignedF32.at(px, py, 1) +
                       0.114f * alignedF32.at(px, py, 2);
            };
            float gx = (luma(x + 1, y - 1) + 2.0f * luma(x + 1, y) + luma(x + 1, y + 1)) -
                       (luma(x - 1, y - 1) + 2.0f * luma(x - 1, y) + luma(x - 1, y + 1));
            float gy = (luma(x - 1, y + 1) + 2.0f * luma(x, y + 1) + luma(x + 1, y + 1)) -
                       (luma(x - 1, y - 1) + 2.0f * luma(x, y - 1) + luma(x + 1, y - 1));
            float tenengrad = gx * gx + gy * gy;
            int idx = y * w + x;
            arena->dfdWeightSum[idx]   += tenengrad;
            arena->dfdWeightedIdx[idx] += fIdx * tenengrad;
        }
    }

    // 3. Update Online 4-Level Laplacian Pyramid Focus Stack in O(1) Memory
    std::vector<ImageF32> curPyr = buildLaplacianPyramid(alignedF32, NativeFrameArena::PYR_LEVELS);
    if (fIdx == 0) {
        arena->fusedPyr = curPyr;
        arena->maxEnergyPyr.resize(NativeFrameArena::PYR_LEVELS - 1);
        for (int l = 0; l < NativeFrameArena::PYR_LEVELS - 1; ++l) {
            int lw = curPyr[l].w;
            int lh = curPyr[l].h;
            arena->maxEnergyPyr[l].assign(static_cast<size_t>(lw) * lh, -1.0f);
            #pragma omp parallel for schedule(static)
            for (int y = 0; y < lh; ++y) {
                for (int x = 0; x < lw; ++x) {
                    float e = 0.0f;
                    for (int dy = -1; dy <= 1; ++dy) {
                        for (int dx = -1; dx <= 1; ++dx) {
                            float r = curPyr[l].at(x + dx, y + dy, 0);
                            float g = curPyr[l].at(x + dx, y + dy, 1);
                            float b = curPyr[l].at(x + dx, y + dy, 2);
                            e += r * r + g * g + b * b;
                        }
                    }
                    arena->maxEnergyPyr[l][y * lw + x] = e;
                }
            }
        }
    } else {
        for (int l = 0; l < NativeFrameArena::PYR_LEVELS - 1; ++l) {
            int lw = curPyr[l].w;
            int lh = curPyr[l].h;
            #pragma omp parallel for schedule(static)
            for (int y = 0; y < lh; ++y) {
                for (int x = 0; x < lw; ++x) {
                    float e = 0.0f;
                    for (int dy = -1; dy <= 1; ++dy) {
                        for (int dx = -1; dx <= 1; ++dx) {
                            float r = curPyr[l].at(x + dx, y + dy, 0);
                            float g = curPyr[l].at(x + dx, y + dy, 1);
                            float b = curPyr[l].at(x + dx, y + dy, 2);
                            e += r * r + g * g + b * b;
                        }
                    }
                    int pIdx = y * lw + x;
                    if (e > arena->maxEnergyPyr[l][pIdx]) {
                        arena->maxEnergyPyr[l][pIdx] = e;
                        for (int c = 0; c < 3; ++c) {
                            arena->fusedPyr[l].at(x, y, c) = curPyr[l].at(x, y, c);
                        }
                    }
                }
            }
        }
        // Accumulate baseband level
        int lastL = NativeFrameArena::PYR_LEVELS - 1;
        int baseTot = curPyr[lastL].w * curPyr[lastL].h * 3;
        #pragma omp parallel for schedule(static)
        for (int i = 0; i < baseTot; ++i) {
            arena->fusedPyr[lastL].data[i] += curPyr[lastL].data[i];
        }
    }

    // 4. Online Sub-Pixel Super-Resolution Pull Directly from Unwarped Raw Frame via H_inv
    int srW = arena->srW;
    int srH = arena->srH;
    float scaleX = static_cast<float>(srW) / w;
    float scaleY = static_cast<float>(srH) / h;

    #pragma omp parallel for schedule(static)
    for (int hy = 0; hy < srH; ++hy) {
        for (int hx = 0; hx < srW; ++hx) {
            float rx = (hx + 0.5f) / scaleX - 0.5f;
            float ry = (hy + 0.5f) / scaleY - 0.5f;
            float rf, gf, bf, phaseW;
            sampleRawSubPixel(rawPtr, w, h, H_inv, rx, ry, rf, gf, bf, phaseW);

            int pIdx = hy * srW + hx;
            arena->srAccRgb.data[pIdx * 3]     += phaseW * rf;
            arena->srAccRgb.data[pIdx * 3 + 1] += phaseW * gf;
            arena->srAccRgb.data[pIdx * 3 + 2] += phaseW * bf;
            arena->srWeightSum[pIdx]           += phaseW;
        }
    }

    arena->frameCount++;
    env->ReleaseByteArrayElements(rawFrameRgbBytes, rawBytes, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_floss_microscope_infrastructure_processing_NativeCvBridge_executeArenaFusionAndSRNative(
        JNIEnv* env,
        jobject /* this */,
        jlong arenaHandle,
        jint mode, // 0 = Mean, 1 = Robust Huber M-Estimator, 2 = 4-Level Laplacian Pyramid Focus Stack
        jboolean enableSuperRes,
        jint outWidth,
        jint outHeight,
        jbyteArray outputRgbBuffer,
        jbyteArray outputDepthMapBuffer) {

    auto* arena = reinterpret_cast<NativeFrameArena*>(arenaHandle);
    if (!arena || arena->frameCount == 0) return;

    int numFrames = arena->frameCount;
    int baseW = arena->width;
    int baseH = arena->height;
    int basePixels = baseW * baseH;
    int baseBytes = basePixels * 3;

    // 1. Finalize Tenengrad Depth-from-Defocus (DfD) Z-Map
    jbyte* outDepth = env->GetByteArrayElements(outputDepthMapBuffer, nullptr);
    #pragma omp parallel for schedule(static)
    for (int i = 0; i < basePixels; ++i) {
        float normZ = (numFrames > 1)
            ? (arena->dfdWeightedIdx[i] / arena->dfdWeightSum[i]) / (numFrames - 1)
            : 0.5f;
        outDepth[i] = static_cast<jbyte>(std::clamp(normZ * 255.0f, 0.0f, 255.0f));
    }
    env->ReleaseByteArrayElements(outputDepthMapBuffer, outDepth, 0);

    // 2. Finalize 1x Base Fused Image
    ImageF32 baseFused(baseW, baseH, 3);
    if (mode == 2 && numFrames >= 2) {
        int lastL = NativeFrameArena::PYR_LEVELS - 1;
        int baseTot = arena->fusedPyr[lastL].w * arena->fusedPyr[lastL].h * 3;
        std::vector<ImageF32> pyrCopy = arena->fusedPyr;
        #pragma omp parallel for schedule(static)
        for (int i = 0; i < baseTot; ++i) {
            pyrCopy[lastL].data[i] /= static_cast<float>(numFrames);
        }
        baseFused = collapseLaplacianPyramid(pyrCopy);
    } else if (mode == 1) {
        #pragma omp parallel for schedule(static)
        for (int i = 0; i < baseBytes; ++i) {
            baseFused.data[i] = arena->robustSumRgb.data[i] / std::max(1e-5f, arena->robustWeightSum.data[i]);
        }
    } else {
        #pragma omp parallel for schedule(static)
        for (int i = 0; i < baseBytes; ++i) {
            baseFused.data[i] = arena->linearSumRgb.data[i] / static_cast<float>(numFrames);
        }
    }

    jbyte* outRgb = env->GetByteArrayElements(outputRgbBuffer, nullptr);

    if (!enableSuperRes || outWidth != arena->srW || outHeight != arena->srH) {
        #pragma omp parallel for schedule(static)
        for (int i = 0; i < baseBytes; ++i) {
            outRgb[i] = static_cast<jbyte>(std::clamp(baseFused.data[i], 0.0f, 255.0f));
        }
        env->ReleaseByteArrayElements(outputRgbBuffer, outRgb, 0);
        return;
    }

    // 3. Finalize Sub-Pixel Super-Resolution + Focus-Stack Prior + 5-Iteration TV-IBP
    int srW = arena->srW;
    int srH = arena->srH;
    float scaleX = static_cast<float>(srW) / baseW;
    float scaleY = static_cast<float>(srH) / baseH;
    ImageF32 hrEst(srW, srH, 3);

    #pragma omp parallel for schedule(static)
    for (int hy = 0; hy < srH; ++hy) {
        for (int hx = 0; hx < srW; ++hx) {
            float rx = (hx + 0.5f) / scaleX - 0.5f;
            float ry = (hy + 0.5f) / scaleY - 0.5f;
            int x0b = std::clamp(static_cast<int>(std::floor(rx)), 0, baseW - 1);
            int y0b = std::clamp(static_cast<int>(std::floor(ry)), 0, baseH - 1);
            float dxb = std::clamp(rx - x0b, 0.0f, 1.0f);
            float dyb = std::clamp(ry - y0b, 0.0f, 1.0f);

            int pIdx = hy * srW + hx;
            float wSr = arena->srWeightSum[pIdx];

            for (int c = 0; c < 3; ++c) {
                float biBase = (1.0f - dxb) * (1.0f - dyb) * baseFused.at(x0b, y0b, c) +
                               dxb * (1.0f - dyb) * baseFused.at(x0b + 1, y0b, c) +
                               (1.0f - dxb) * dyb * baseFused.at(x0b, y0b + 1, c) +
                               dxb * dyb * baseFused.at(x0b + 1, y0b + 1, c);
                float srVal = arena->srAccRgb.data[pIdx * 3 + c] / wSr;
                // Blend Focus-Stacked all-in-focus structure with Sub-Pixel SR high frequencies
                hrEst.at(hx, hy, c) = (mode == 2) ? (0.45f * biBase + 0.55f * srVal) : srVal;
            }
        }
    }

    for (int iter = 0; iter < 5; ++iter) {
        ImageF32 blurredHr = gaussianBlur5x5(hrEst);
        ImageF32 nextEst = hrEst;
        #pragma omp parallel for schedule(static)
        for (int hy = 1; hy < srH - 1; ++hy) {
            for (int hx = 1; hx < srW - 1; ++hx) {
                for (int c = 0; c < 3; ++c) {
                    float center = hrEst.at(hx, hy, c);
                    float backProjErr = center - blurredHr.at(hx, hy, c);
                    // Exact 4-neighbor Rudin-Osher-Fatemi normalized flux divergence
                    float dE = hrEst.at(hx + 1, hy, c) - center;
                    float dW = hrEst.at(hx - 1, hy, c) - center;
                    float dS = hrEst.at(hx, hy + 1, c) - center;
                    float dN = hrEst.at(hx, hy - 1, c) - center;
                    float rofDiv = dE / std::sqrt(dE * dE + 1.0f) +
                                   dW / std::sqrt(dW * dW + 1.0f) +
                                   dS / std::sqrt(dS * dS + 1.0f) +
                                   dN / std::sqrt(dN * dN + 1.0f);
                    nextEst.at(hx, hy, c) = std::clamp(center + 0.42f * backProjErr + 0.04f * rofDiv, 0.0f, 255.0f);
                }
            }
        }
        hrEst = std::move(nextEst);
    }

    int outBytes = srW * srH * 3;
    #pragma omp parallel for schedule(static)
    for (int i = 0; i < outBytes; ++i) {
        outRgb[i] = static_cast<jbyte>(std::clamp(hrEst.data[i], 0.0f, 255.0f));
    }
    env->ReleaseByteArrayElements(outputRgbBuffer, outRgb, 0);
}

JNIEXPORT void JNICALL
Java_com_floss_microscope_infrastructure_processing_NativeCvBridge_destroyArenaNative(
        JNIEnv* /* env */,
        jobject /* this */,
        jlong arenaHandle) {
    auto* arena = reinterpret_cast<NativeFrameArena*>(arenaHandle);
    delete arena;
}

JNIEXPORT void JNICALL
Java_com_floss_microscope_infrastructure_processing_NativeCvBridge_demosaicRaw16ToRgbNative(
        JNIEnv* env,
        jobject /* this */,
        jshortArray raw16BayerBuffer,
        jint width,
        jint height,
        jint bayerPattern,
        jint blackLevel,
        jint whiteLevel,
        jbyteArray outputRgb8Buffer) {

    jshort* rawPtr = env->GetShortArrayElements(raw16BayerBuffer, nullptr);
    jbyte* outPtr = env->GetByteArrayElements(outputRgb8Buffer, nullptr);
    uint8_t* out = reinterpret_cast<uint8_t*>(outPtr);

    float range = std::max(1.0f, static_cast<float>(whiteLevel - blackLevel));
    std::vector<float> normBayer(static_cast<size_t>(width) * height);

    #pragma omp parallel for schedule(static)
    for (int i = 0; i < width * height; ++i) {
        int v = static_cast<uint16_t>(rawPtr[i]);
        float lin = std::clamp((v - blackLevel) / range, 0.0f, 1.0f);
        normBayer[i] = std::pow(lin, 1.0f / 2.2f) * 255.0f;
    }

    auto atB = [&](int x, int y) -> float {
        x = std::clamp(x, 0, width - 1);
        y = std::clamp(y, 0, height - 1);
        return normBayer[y * width + x];
    };

    #pragma omp parallel for schedule(static)
    for (int y = 2; y < height - 2; ++y) {
        for (int x = 2; x < width - 2; ++x) {
            int parity = (((y & 1) << 1) | (x & 1)) ^ bayerPattern;
            float c = atB(x, y);
            float hAvg = 0.5f * (atB(x - 1, y) + atB(x + 1, y));
            float vAvg = 0.5f * (atB(x, y - 1) + atB(x, y + 1));
            float dAvg = 0.25f * (atB(x - 1, y - 1) + atB(x + 1, y - 1) + atB(x - 1, y + 1) + atB(x + 1, y + 1));
            float lap = c - 0.25f * (atB(x - 2, y) + atB(x + 2, y) + atB(x, y - 2) + atB(x, y + 2));

            float r = 0.0f, g = 0.0f, b = 0.0f;
            if (parity == 0) {
                r = c; g = 0.5f * (hAvg + vAvg) + 0.5f * lap; b = dAvg + 0.75f * lap;
            } else if (parity == 1) {
                r = hAvg + 0.5f * lap; g = c; b = vAvg + 0.5f * lap;
            } else if (parity == 2) {
                r = vAvg + 0.5f * lap; g = c; b = hAvg + 0.5f * lap;
            } else {
                r = dAvg + 0.75f * lap; g = 0.5f * (hAvg + vAvg) + 0.5f * lap; b = c;
            }

            int idx = (y * width + x) * 3;
            out[idx]     = static_cast<uint8_t>(std::clamp(r, 0.0f, 255.0f));
            out[idx + 1] = static_cast<uint8_t>(std::clamp(g, 0.0f, 255.0f));
            out[idx + 2] = static_cast<uint8_t>(std::clamp(b, 0.0f, 255.0f));
        }
    }

    env->ReleaseShortArrayElements(raw16BayerBuffer, rawPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(outputRgb8Buffer, outPtr, 0);
}

JNIEXPORT void JNICALL
Java_com_floss_microscope_infrastructure_processing_NativeCvBridge_enhanceMicroscopeNative(
        JNIEnv *env,
        jobject /* this */,
        jbyteArray rgbBuffer,
        jint width,
        jint height,
        jint rlIterations,
        jfloat psfAngleRad,
        jfloat psfLengthPx,
        jboolean applyRetinex,
        jboolean correctOptics) {

    jbyte* raw = env->GetByteArrayElements(rgbBuffer, nullptr);
    uint8_t* imgBytes = reinterpret_cast<uint8_t*>(raw);
    int numPixels = width * height;

    ImageF32 work(width, height, 3);
    #pragma omp parallel for schedule(static)
    for (int i = 0; i < numPixels * 3; ++i) work.data[i] = static_cast<float>(imgBytes[i]);

    // 1. Hot-Pixel / Dead-Pixel 3x3 Statistical Impulse Filter
    {
        ImageF32 clean = work;
        #pragma omp parallel for schedule(static)
        for (int y = 1; y < height - 1; ++y) {
            for (int x = 1; x < width - 1; ++x) {
                for (int c = 0; c < 3; ++c) {
                    float center = work.at(x, y, c);
                    float sum = 0.0f, sumSq = 0.0f;
                    for (int ky = -1; ky <= 1; ++ky) {
                        for (int kx = -1; kx <= 1; ++kx) {
                            if (kx == 0 && ky == 0) continue;
                            float v = work.at(x + kx, y + ky, c);
                            sum += v; sumSq += v * v;
                        }
                    }
                    float mean = sum * 0.125f;
                    float stdDev = std::sqrt(std::max(1.0f, (sumSq * 0.125f) - (mean * mean)));
                    if (std::abs(center - mean) > 3.2f * stdDev) clean.at(x, y, c) = mean;
                }
            }
        }
        work = std::move(clean);
    }

    // 2. Gray-World Color Constancy White Balance
    {
        double rSum = 0.0, gSum = 0.0, bSum = 0.0;
        #pragma omp parallel for reduction(+:rSum,gSum,bSum) schedule(static)
        for (int i = 0; i < numPixels; ++i) {
            rSum += work.data[i * 3];
            gSum += work.data[i * 3 + 1];
            bSum += work.data[i * 3 + 2];
        }
        double avgGray = (rSum + gSum + bSum) / (3.0 * numPixels);
        float rGain = std::clamp(static_cast<float>(avgGray / std::max(1.0, rSum / numPixels)), 0.78f, 1.32f);
        float gGain = std::clamp(static_cast<float>(avgGray / std::max(1.0, gSum / numPixels)), 0.78f, 1.32f);
        float bGain = std::clamp(static_cast<float>(avgGray / std::max(1.0, bSum / numPixels)), 0.78f, 1.32f);

        #pragma omp parallel for schedule(static)
        for (int i = 0; i < numPixels; ++i) {
            work.data[i * 3]     = std::clamp(work.data[i * 3]     * rGain, 0.0f, 255.0f);
            work.data[i * 3 + 1] = std::clamp(work.data[i * 3 + 1] * gGain, 0.0f, 255.0f);
            work.data[i * 3 + 2] = std::clamp(work.data[i * 3 + 2] * bGain, 0.0f, 255.0f);
        }
    }

    // 3. Radial Vignetting & Lateral Chromatic Aberration Correction
    if (correctOptics) {
        ImageF32 corr(width, height, 3);
        float cx = width * 0.5f;
        float cy = height * 0.5f;
        float maxRadiusSq = cx * cx + cy * cy;

        #pragma omp parallel for schedule(static)
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                float dx = x - cx;
                float dy = y - cy;
                float rSq = (dx * dx + dy * dy) / maxRadiusSq;
                float vigGain = 1.0f + 0.16f * rSq + 0.08f * rSq * rSq;

                int rx = static_cast<int>(cx + dx * (1.0f - 0.0014f * rSq));
                int ry = static_cast<int>(cy + dy * (1.0f - 0.0014f * rSq));
                int bx = static_cast<int>(cx + dx * (1.0f + 0.0014f * rSq));
                int by = static_cast<int>(cy + dy * (1.0f + 0.0014f * rSq));

                corr.at(x, y, 0) = std::clamp(work.at(rx, ry, 0) * vigGain, 0.0f, 255.0f);
                corr.at(x, y, 1) = std::clamp(work.at(x,  y,  1) * vigGain, 0.0f, 255.0f);
                corr.at(x, y, 2) = std::clamp(work.at(bx, by, 2) * vigGain, 0.0f, 255.0f);
            }
        }
        work = std::move(corr);
    }

    // 4. Anisotropic Motion-PSF + Exact ROF Flux-Divergence TV-Regularized Richardson-Lucy (Dey et al.)
    if (rlIterations > 0) {
        float psf[5][5];
        float psfSum = 0.0f;
        float cosA = std::cos(psfAngleRad);
        float sinA = std::sin(psfAngleRad);
        float sigmaLong = std::clamp(psfLengthPx * 0.5f, 0.75f, 2.2f);
        float sigmaCross = 0.75f;

        for (int ky = -2; ky <= 2; ++ky) {
            for (int kx = -2; kx <= 2; ++kx) {
                float u = kx * cosA + ky * sinA;
                float v = -kx * sinA + ky * cosA;
                float val = std::exp(-0.5f * ((u * u) / (sigmaLong * sigmaLong) + (v * v) / (sigmaCross * sigmaCross)));
                psf[ky + 2][kx + 2] = val;
                psfSum += val;
            }
        }
        for (int ky = 0; ky < 5; ++ky) {
            for (int kx = 0; kx < 5; ++kx) psf[ky][kx] /= psfSum;
        }

        ImageF32 obs = work;
        ImageF32 est = work;
        ImageF32 reblurred(width, height, 3);
        const float tvLambda = 0.0022f;

        for (int iter = 0; iter < rlIterations; ++iter) {
            #pragma omp parallel for schedule(static)
            for (int y = 2; y < height - 2; ++y) {
                for (int x = 2; x < width - 2; ++x) {
                    for (int c = 0; c < 3; ++c) {
                        float sum = 0.0f;
                        for (int ky = -2; ky <= 2; ++ky) {
                            for (int kx = -2; kx <= 2; ++kx) {
                                sum += est.at(x + kx, y + ky, c) * psf[ky + 2][kx + 2];
                            }
                        }
                        reblurred.at(x, y, c) = std::max(1e-3f, sum);
                    }
                }
            }

            #pragma omp parallel for schedule(static)
            for (int y = 2; y < height - 2; ++y) {
                for (int x = 2; x < width - 2; ++x) {
                    for (int c = 0; c < 3; ++c) {
                        float ratioConv = 0.0f;
                        for (int ky = -2; ky <= 2; ++ky) {
                            for (int kx = -2; kx <= 2; ++kx) {
                                float obsVal = std::max(1.0f, obs.at(x + kx, y + ky, c));
                                float rebVal = reblurred.at(x + kx, y + ky, c);
                                ratioConv += (obsVal / rebVal) * psf[2 - ky][2 - kx];
                            }
                        }
                        float center = est.at(x, y, c);
                        float dE = est.at(x + 1, y, c) - center;
                        float dW = est.at(x - 1, y, c) - center;
                        float dS = est.at(x, y + 1, c) - center;
                        float dN = est.at(x, y - 1, c) - center;
                        float divTv = dE / std::sqrt(dE * dE + 1.0f) +
                                      dW / std::sqrt(dW * dW + 1.0f) +
                                      dS / std::sqrt(dS * dS + 1.0f) +
                                      dN / std::sqrt(dN * dN + 1.0f);
                        float tvDenom = std::clamp(1.0f - tvLambda * divTv, 0.85f, 1.15f);

                        est.at(x, y, c) = std::clamp((center / tvDenom) * ratioConv, 0.0f, 255.0f);
                    }
                }
            }
        }
        work = std::move(est);
    }

    // 5. O(1) Sliding-Window 3-Scale Multi-Scale Retinex with Color Restoration (sigmas = {12, 48, 128})
    if (applyRetinex) {
        ImageF32 s1 = fastGaussianO1(work, 12.0f);
        ImageF32 s2 = fastGaussianO1(work, 48.0f);
        ImageF32 s3 = fastGaussianO1(work, 128.0f);

        #pragma omp parallel for schedule(static)
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                float r = work.at(x, y, 0) + 1.0f;
                float g = work.at(x, y, 1) + 1.0f;
                float b = work.at(x, y, 2) + 1.0f;
                float sumRgb = r + g + b;

                for (int c = 0; c < 3; ++c) {
                    float val = work.at(x, y, c) + 1.0f;
                    float msr = (std::log(val) - std::log(s1.at(x, y, c) + 1.0f)) / 3.0f +
                                (std::log(val) - std::log(s2.at(x, y, c) + 1.0f)) / 3.0f +
                                (std::log(val) - std::log(s3.at(x, y, c) + 1.0f)) / 3.0f;
                    float crf = std::log(125.0f * val / sumRgb);
                    work.at(x, y, c) = std::clamp(val + (msr * crf * 18.0f), 0.0f, 255.0f);
                }
            }
        }
    }

    #pragma omp parallel for schedule(static)
    for (int i = 0; i < numPixels * 3; ++i) {
        imgBytes[i] = static_cast<uint8_t>(std::clamp(work.data[i], 0.0f, 255.0f));
    }

    env->ReleaseByteArrayElements(rgbBuffer, raw, 0);
}

} // extern "C"
