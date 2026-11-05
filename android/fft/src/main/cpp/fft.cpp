#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

#ifndef FFT_STANDALONE
#include <android/log.h>
#include <jni.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "TemporalFft", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) std::printf(__VA_ARGS__)
#endif

namespace {

constexpr double kPi = 3.14159265358979323846;

int next_pow2(int v) {
    int x = v - 1;
    x |= x >> 1;
    x |= x >> 2;
    x |= x >> 4;
    x |= x >> 8;
    x |= x >> 16;
    return x + 1;
}

void fft_radix2(double* re, double* im, int n, bool inverse) {
    int j = 0;
    for (int i = 1; i < n; i++) {
        int bit = n >> 1;
        while (j & bit) {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if (i < j) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }
    for (int length = 2; length <= n; length <<= 1) {
        double ang = 2.0 * kPi / length * (inverse ? 1.0 : -1.0);
        double wlen_re = std::cos(ang);
        double wlen_im = std::sin(ang);
        for (int i = 0; i < n; i += length) {
            double w_re = 1.0;
            double w_im = 0.0;
            int half = length >> 1;
            for (int k = 0; k < half; k++) {
                double u_re = re[i + k];
                double u_im = im[i + k];
                double v_re = re[i + k + half] * w_re - im[i + k + half] * w_im;
                double v_im = re[i + k + half] * w_im + im[i + k + half] * w_re;
                re[i + k] = u_re + v_re;
                im[i + k] = u_im + v_im;
                re[i + k + half] = u_re - v_re;
                im[i + k + half] = u_im - v_im;
                double next_re = w_re * wlen_re - w_im * wlen_im;
                w_im = w_re * wlen_im + w_im * wlen_re;
                w_re = next_re;
            }
        }
    }
    if (inverse) {
        double inv = 1.0 / n;
        for (int i = 0; i < n; i++) {
            re[i] *= inv;
            im[i] *= inv;
        }
    }
}

struct Plan {
    int n = 0;
    int bins = 0;
    int m = 0;
    bool pow2 = false;
    double* cos_c = nullptr;
    double* sin_c = nullptr;
    double* k_re = nullptr;
    double* k_im = nullptr;

    bool init(int length) {
        free();
        n = length;
        bins = length / 2 + 1;
        pow2 = length > 0 && (length & (length - 1)) == 0;
        m = (pow2 || length <= 1) ? length : next_pow2(2 * length - 1);
        cos_c = (double*)std::calloc(length, sizeof(double));
        sin_c = (double*)std::calloc(length, sizeof(double));
        k_re = (double*)std::calloc(m, sizeof(double));
        k_im = (double*)std::calloc(m, sizeof(double));
        if (!cos_c || !sin_c || !k_re || !k_im) return false;
        if (!pow2 && length > 1) {
            for (int i = 0; i < length; i++) {
                double theta = kPi * (double)i * (double)i / (double)length;
                double c = std::cos(theta);
                double s = std::sin(theta);
                cos_c[i] = c;
                sin_c[i] = s;
                k_re[i] = c;
                k_im[i] = s;
                if (i != 0) {
                    k_re[m - i] = c;
                    k_im[m - i] = s;
                }
            }
            fft_radix2(k_re, k_im, m, false);
        }
        return true;
    }

    void free() {
        std::free(cos_c);
        std::free(sin_c);
        std::free(k_re);
        std::free(k_im);
        cos_c = sin_c = k_re = k_im = nullptr;
    }

    ~Plan() { free(); }
};

struct Scratch {
    double* a_re = nullptr;
    double* a_im = nullptr;
    double* full_re = nullptr;
    double* full_im = nullptr;
    double* re_b = nullptr;
    double* im_b = nullptr;
    double* time = nullptr;
    float* ch[3] = {nullptr, nullptr, nullptr};
    int* hist = nullptr;

    bool alloc(const Plan& plan, bool with_hist) {
        a_re = (double*)std::calloc(plan.m, sizeof(double));
        a_im = (double*)std::calloc(plan.m, sizeof(double));
        full_re = (double*)std::calloc(plan.n, sizeof(double));
        full_im = (double*)std::calloc(plan.n, sizeof(double));
        re_b = (double*)std::calloc(plan.bins, sizeof(double));
        im_b = (double*)std::calloc(plan.bins, sizeof(double));
        time = (double*)std::calloc(plan.n, sizeof(double));
        for (int c = 0; c < 3; c++) ch[c] = (float*)std::calloc(plan.n, sizeof(float));
        if (with_hist) hist = (int*)std::calloc(4096, sizeof(int));
        return a_re && a_im && full_re && full_im && re_b && im_b && time && ch[0] && ch[1] && ch[2] &&
               (!with_hist || hist);
    }

    void free() {
        std::free(a_re);
        std::free(a_im);
        std::free(full_re);
        std::free(full_im);
        std::free(re_b);
        std::free(im_b);
        std::free(time);
        for (float*& p : ch) {
            std::free(p);
            p = nullptr;
        }
        std::free(hist);
        a_re = a_im = full_re = full_im = re_b = im_b = time = nullptr;
        hist = nullptr;
    }

    ~Scratch() { free(); }
};

void dft_complex(const Plan& plan, Scratch& s, double* re, double* im) {
    int n = plan.n;
    if (plan.pow2) {
        fft_radix2(re, im, n, false);
        return;
    }
    std::memset(s.a_re, 0, (size_t)plan.m * sizeof(double));
    std::memset(s.a_im, 0, (size_t)plan.m * sizeof(double));
    for (int i = 0; i < n; i++) {
        double c = plan.cos_c[i];
        double sn = plan.sin_c[i];
        double xr = re[i];
        double xi = im[i];
        s.a_re[i] = xr * c + xi * sn;
        s.a_im[i] = xi * c - xr * sn;
    }
    fft_radix2(s.a_re, s.a_im, plan.m, false);
    for (int i = 0; i < plan.m; i++) {
        double r = s.a_re[i] * plan.k_re[i] - s.a_im[i] * plan.k_im[i];
        double iv = s.a_re[i] * plan.k_im[i] + s.a_im[i] * plan.k_re[i];
        s.a_re[i] = r;
        s.a_im[i] = iv;
    }
    fft_radix2(s.a_re, s.a_im, plan.m, true);
    for (int k = 0; k < n; k++) {
        double c = plan.cos_c[k];
        double sn = plan.sin_c[k];
        re[k] = s.a_re[k] * c + s.a_im[k] * sn;
        im[k] = s.a_im[k] * c - s.a_re[k] * sn;
    }
}

void rfft(const Plan& plan, Scratch& s, const float* x, double* out_re, double* out_im) {
    int n = plan.n;
    if (plan.pow2) {
        for (int i = 0; i < n; i++) {
            s.a_re[i] = x[i];
            s.a_im[i] = 0.0;
        }
        fft_radix2(s.a_re, s.a_im, n, false);
        for (int k = 0; k < plan.bins; k++) {
            out_re[k] = s.a_re[k];
            out_im[k] = s.a_im[k];
        }
        return;
    }
    for (int i = 0; i < n; i++) {
        s.full_re[i] = x[i];
        s.full_im[i] = 0.0;
    }
    dft_complex(plan, s, s.full_re, s.full_im);
    for (int k = 0; k < plan.bins; k++) {
        out_re[k] = s.full_re[k];
        out_im[k] = s.full_im[k];
    }
}

void irfft(const Plan& plan, Scratch& s, const double* re_b, const double* im_b, double* time) {
    int n = plan.n;
    int bins = plan.bins;
    for (int k = 0; k < bins; k++) {
        s.full_re[k] = re_b[k];
        s.full_im[k] = -im_b[k];
    }
    for (int k = 1; k < bins; k++) {
        int mirror = n - k;
        if (mirror >= bins && mirror < n) {
            s.full_re[mirror] = re_b[k];
            s.full_im[mirror] = im_b[k];
        }
    }
    dft_complex(plan, s, s.full_re, s.full_im);
    double inv = 1.0 / n;
    for (int t = 0; t < n; t++) time[t] = s.full_re[t] * inv;
}

void filter_series(
    const Plan& plan,
    Scratch& s,
    const float* x,
    int mode,
    double fps,
    double cutoff,
    double low,
    double high,
    double gain,
    float* dest
) {
    rfft(plan, s, x, s.re_b, s.im_b);
    if (mode == 3) {
        for (int k = 0; k < plan.bins; k++) {
            double mag = std::log1p(std::hypot(s.re_b[k], s.im_b[k]));
            dest[k] = (float)mag;
        }
        return;
    }
    for (int k = 0; k < plan.bins; k++) {
        double freq = k * fps / plan.n;
        bool keep = false;
        if (mode == 0) keep = freq >= cutoff;
        else if (mode == 1) keep = freq <= cutoff;
        else keep = freq >= low && freq <= high;
        if (!keep) {
            s.re_b[k] = 0.0;
            s.im_b[k] = 0.0;
        }
    }
    if ((plan.n % 2) == 0) s.im_b[plan.n / 2] = 0.0;
    irfft(plan, s, s.re_b, s.im_b, s.time);
    bool center = mode != 1;
    for (int t = 0; t < plan.n; t++) {
        double v = center ? (128.0 + gain * s.time[t]) : s.time[t];
        if (v < 0.0) v = 0.0;
        if (v > 255.0) v = 255.0;
        dest[t] = (float)v;
    }
}

void direct_dft(const double* re, const double* im, int n, double* out_re, double* out_im) {
    for (int k = 0; k < n; k++) {
        double sr = 0.0;
        double si = 0.0;
        for (int t = 0; t < n; t++) {
            double ang = -2.0 * kPi * k * t / n;
            double c = std::cos(ang);
            double s = std::sin(ang);
            sr += re[t] * c - im[t] * s;
            si += re[t] * s + im[t] * c;
        }
        out_re[k] = sr;
        out_im[k] = si;
    }
}

int check_length(int n) {
    std::vector<double> re(n), im(n, 0.0), exp_re(n), exp_im(n);
    uint32_t state = (uint32_t)n * 9973u + 17u;
    for (int i = 0; i < n; i++) {
        state = state * 1664525u + 1013904223u;
        re[i] = (int)(state % 1000) / 50.0 - 10.0;
    }
    direct_dft(re.data(), im.data(), n, exp_re.data(), exp_im.data());
    Plan plan;
    if (!plan.init(n)) return n;
    Scratch scratch;
    if (!scratch.alloc(plan, false)) return n;
    std::vector<float> x(n);
    for (int i = 0; i < n; i++) x[i] = (float)re[i];
    std::vector<double> got_re(n), got_im(n);
    for (int i = 0; i < n; i++) {
        scratch.full_re[i] = re[i];
        scratch.full_im[i] = 0.0;
    }
    dft_complex(plan, scratch, scratch.full_re, scratch.full_im);
    double err = 0.0;
    for (int k = 0; k < n; k++) {
        err = std::max(err, std::abs(scratch.full_re[k] - exp_re[k]));
        err = std::max(err, std::abs(scratch.full_im[k] - exp_im[k]));
    }
    if (err > 1e-6) return n;
    rfft(plan, scratch, x.data(), scratch.re_b, scratch.im_b);
    irfft(plan, scratch, scratch.re_b, scratch.im_b, scratch.time);
    for (int t = 0; t < n; t++) {
        if (std::abs(scratch.time[t] - re[t]) > 1e-4) return n;
    }
    return 0;
}

int check_highpass() {
    const int n = 32;
    Plan plan;
    if (!plan.init(n)) return 1000;
    Scratch scratch;
    if (!scratch.alloc(plan, false)) return 1000;
    std::vector<float> x(n);
    for (int t = 0; t < n; t++) {
        x[t] = (float)(40.0 + 3.0 * std::sin(2.0 * kPi * 2.0 * t / n));
    }
    filter_series(plan, scratch, x.data(), 0, (double)n, 1.5, 0.0, 0.0, 2.0, scratch.ch[0]);
    for (int t = 0; t < n; t++) {
        double expect = 128.0 + 6.0 * std::sin(2.0 * kPi * 2.0 * t / n);
        if (std::abs(scratch.ch[0][t] - expect) > 1e-3) return 1000;
    }
    return 0;
}

}  // namespace

int temporal_self_test() {
    const int lengths[] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 16, 30, 300};
    for (int n : lengths) {
        int code = check_length(n);
        if (code != 0) return code;
    }
    return check_highpass();
}

#ifndef FFT_STANDALONE

namespace {

struct Clip {
    int w = 0;
    int h = 0;
    int capacity = 0;
    int count = 0;
    int out_frames = 0;
    float* samples = nullptr;
    uint32_t* out = nullptr;
    float* mags = nullptr;
};

int sample_index(const Clip& clip, int channel, int y, int x) {
    return ((channel * clip.h + y) * clip.w + x) * clip.capacity;
}

void push_frame(Clip& clip, const int32_t* pixels) {
    if (clip.count >= clip.capacity) return;
    int t = clip.count;
    for (int y = 0; y < clip.h; y++) {
        for (int x = 0; x < clip.w; x++) {
            int32_t p = pixels[y * clip.w + x];
            float r = (float)((p >> 16) & 255);
            float g = (float)((p >> 8) & 255);
            float b = (float)(p & 255);
            clip.samples[sample_index(clip, 0, y, x) + t] = r;
            clip.samples[sample_index(clip, 1, y, x) + t] = g;
            clip.samples[sample_index(clip, 2, y, x) + t] = b;
        }
    }
    clip.count++;
}

struct Job {
    Clip* clip = nullptr;
    const Plan* plan = nullptr;
    int mode = 0;
    double fps = 30;
    double cutoff = 2;
    double low = 0.5;
    double high = 3;
    double gain = 3;
    float ref = 1;
    std::atomic<int> next{0};
    std::atomic<int> done{0};
    std::atomic<int> finished{0};
    std::atomic<bool> cancel{false};
    std::atomic<bool> failed{false};
    std::mutex hist_mu;
    int total_hist[4096] = {};
};

int worker_count(int height) {
    unsigned hc = std::thread::hardware_concurrency();
    int workers = hc == 0 ? 4 : (int)hc;
    if (workers < 2) workers = 2;
    if (workers > 8) workers = 8;
    if (workers > height) workers = height;
    return workers;
}

bool call_progress(JNIEnv* env, jobject progress, jmethodID method, int done, int total) {
    if (progress == nullptr || method == nullptr) return false;
    jboolean stop = env->CallBooleanMethod(progress, method, done, total);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return true;
    }
    return stop == JNI_TRUE;
}

void color_row(Job& job, int y, Scratch& scratch) {
    Clip& clip = *job.clip;
    const Plan& plan = *job.plan;
    for (int x = 0; x < clip.w; x++) {
        for (int c = 0; c < 3; c++) {
            const float* series = clip.samples + sample_index(clip, c, y, x);
            filter_series(plan, scratch, series, job.mode, job.fps, job.cutoff, job.low, job.high, job.gain, scratch.ch[c]);
        }
        for (int t = 0; t < plan.n; t++) {
            int r = (int)scratch.ch[0][t];
            int g = (int)scratch.ch[1][t];
            int b = (int)scratch.ch[2][t];
            clip.out[(size_t)t * clip.w * clip.h + y * clip.w + x] =
                0xFF000000u | ((uint32_t)r << 16) | ((uint32_t)g << 8) | (uint32_t)b;
        }
    }
}

void mag_row(Job& job, int y, Scratch& scratch) {
    Clip& clip = *job.clip;
    const Plan& plan = *job.plan;
    for (int x = 0; x < clip.w; x++) {
        for (int c = 0; c < 3; c++) {
            const float* series = clip.samples + sample_index(clip, c, y, x);
            filter_series(plan, scratch, series, 3, job.fps, job.cutoff, job.low, job.high, job.gain, scratch.ch[0]);
            float* dest = clip.mags + (((size_t)c * clip.h + y) * clip.w + x) * plan.bins;
            for (int k = 0; k < plan.bins; k++) {
                float mag = scratch.ch[0][k];
                dest[k] = mag;
                if (k > 0 && scratch.hist) {
                    int bin = (int)((mag / 24.f) * 4095.f);
                    if (bin < 0) bin = 0;
                    if (bin > 4095) bin = 4095;
                    scratch.hist[bin]++;
                }
            }
        }
    }
}

void paint_row(Job& job, int y, Scratch& scratch) {
    Clip& clip = *job.clip;
    const Plan& plan = *job.plan;
    float ref = job.ref < 1e-6f ? 1e-6f : job.ref;
    for (int x = 0; x < clip.w; x++) {
        for (int k = 0; k < plan.bins; k++) {
            int r = 0, g = 0, b = 0;
            for (int c = 0; c < 3; c++) {
                float mag = clip.mags[(((size_t)c * clip.h + y) * clip.w + x) * plan.bins + k];
                float scaled = mag / ref;
                if (scaled < 0.f) scaled = 0.f;
                if (scaled > 1.f) scaled = 1.f;
                int value = (int)(scaled * 255.f);
                if (c == 0) r = value;
                else if (c == 1) g = value;
                else b = value;
            }
            clip.out[(size_t)k * clip.w * clip.h + y * clip.w + x] =
                0xFF000000u | ((uint32_t)r << 16) | ((uint32_t)g << 8) | (uint32_t)b;
        }
    }
    (void)scratch;
}

int run_parallel(JNIEnv* env, jobject progress, jmethodID method, Job& job, bool with_hist, const std::function<void(Job&, int, Scratch&)>& body) {
    int height = job.clip->h;
    int workers = worker_count(height);
    job.next.store(0);
    job.done.store(0);
    job.finished.store(0);
    std::vector<std::thread> threads;
    threads.reserve(workers);
    for (int w = 0; w < workers; w++) {
        threads.emplace_back([&, with_hist] {
            Scratch scratch;
            if (!scratch.alloc(*job.plan, with_hist)) {
                job.failed.store(true);
                job.cancel.store(true);
                job.finished.fetch_add(1);
                return;
            }
            while (!job.cancel.load()) {
                int y = job.next.fetch_add(1);
                if (y >= height) break;
                body(job, y, scratch);
                job.done.fetch_add(1);
            }
            if (with_hist && scratch.hist) {
                std::lock_guard<std::mutex> lock(job.hist_mu);
                for (int i = 0; i < 4096; i++) job.total_hist[i] += scratch.hist[i];
            }
            job.finished.fetch_add(1);
        });
    }
    while (job.finished.load() < workers) {
        bool stop = call_progress(env, progress, method, job.done.load(), height);
        if (stop) job.cancel.store(true);
        if (job.done.load() >= height && !job.cancel.load()) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(40));
    }
    for (auto& thread : threads) {
        if (thread.joinable()) thread.join();
    }
    call_progress(env, progress, method, job.done.load(), height);
    if (job.failed.load()) return -2;
    if (job.cancel.load() && job.done.load() < height) return -5;
    return 0;
}

float percentile_ref(const int* hist) {
    long long total = 0;
    for (int i = 0; i < 4096; i++) total += hist[i];
    if (total <= 0) return 1.f;
    long long target = (long long)(0.995 * (double)total);
    if (target < 1) target = 1;
    long long acc = 0;
    int bin = 4095;
    for (int i = 0; i < 4096; i++) {
        acc += hist[i];
        if (acc >= target) {
            bin = i;
            break;
        }
    }
    float ref = (float)((bin + 0.5) / 4095.0 * 24.0);
    if (ref < 1e-6f) ref = 1e-6f;
    return ref;
}

int process_clip(JNIEnv* env, jobject progress, Clip& clip, float fps, int mode, float cutoff, float low, float high, float gain) {
    if (clip.count < 4) return -3;
    if (mode < 0 || mode > 3) return -1;
    Plan plan;
    if (!plan.init(clip.count)) return -2;
    jmethodID method = nullptr;
    if (progress != nullptr) {
        method = env->GetMethodID(env->GetObjectClass(progress), "onRow", "(II)Z");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            method = nullptr;
        }
    }
    Job job;
    job.clip = &clip;
    job.plan = &plan;
    job.mode = mode;
    job.fps = fps < 1.f ? 30.0 : (double)fps;
    job.cutoff = cutoff;
    job.low = low;
    job.high = high;
    job.gain = gain;
    int code = 0;
    if (mode == 3) {
        size_t mag_n = (size_t)3 * clip.h * clip.w * plan.bins;
        clip.mags = (float*)std::malloc(mag_n * sizeof(float));
        if (!clip.mags) return -2;
        code = run_parallel(env, progress, method, job, true, mag_row);
        if (code != 0) {
            std::free(clip.mags);
            clip.mags = nullptr;
            return code;
        }
        job.ref = percentile_ref(job.total_hist);
        size_t out_n = (size_t)plan.bins * clip.w * clip.h;
        clip.out = (uint32_t*)std::malloc(out_n * sizeof(uint32_t));
        if (!clip.out) {
            std::free(clip.mags);
            clip.mags = nullptr;
            return -2;
        }
        job.cancel.store(false);
        code = run_parallel(env, progress, method, job, false, paint_row);
        std::free(clip.mags);
        clip.mags = nullptr;
        if (code != 0) {
            std::free(clip.out);
            clip.out = nullptr;
            return code;
        }
        clip.out_frames = plan.bins;
        return plan.bins;
    }
    size_t out_n = (size_t)plan.n * clip.w * clip.h;
    clip.out = (uint32_t*)std::malloc(out_n * sizeof(uint32_t));
    if (!clip.out) return -2;
    code = run_parallel(env, progress, method, job, false, color_row);
    if (code != 0) {
        std::free(clip.out);
        clip.out = nullptr;
        return code;
    }
    clip.out_frames = plan.n;
    return plan.n;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_temporalfpt_app_NativeFilter_selfTest(JNIEnv*, jclass) {
    return temporal_self_test();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_temporalfpt_app_NativeFilter_create(JNIEnv*, jclass, jint width, jint height, jint capacity) {
    if (width < 2 || height < 2 || capacity < 4 || width > 2000 || height > 2000 || capacity > 2000) return 0;
    size_t count = (size_t)3 * (size_t)width * (size_t)height * (size_t)capacity;
    if (count > 500000000ull) return 0;
    float* samples = (float*)std::malloc(count * sizeof(float));
    if (!samples) return 0;
    auto* clip = new Clip();
    clip->w = width;
    clip->h = height;
    clip->capacity = capacity;
    clip->samples = samples;
    return (jlong)clip;
}

extern "C" JNIEXPORT void JNICALL
Java_com_temporalfpt_app_NativeFilter_push(JNIEnv* env, jclass, jlong handle, jintArray pixels) {
    auto* clip = (Clip*)handle;
    if (clip == nullptr || pixels == nullptr) return;
    if (env->GetArrayLength(pixels) < clip->w * clip->h) return;
    jint* data = env->GetIntArrayElements(pixels, nullptr);
    if (data == nullptr) return;
    push_frame(*clip, data);
    env->ReleaseIntArrayElements(pixels, data, JNI_ABORT);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_temporalfpt_app_NativeFilter_process(
    JNIEnv* env,
    jclass,
    jlong handle,
    jfloat fps,
    jint mode,
    jfloat cutoff,
    jfloat low,
    jfloat high,
    jfloat gain,
    jobject progress
) {
    auto* clip = (Clip*)handle;
    if (clip == nullptr) return -1;
    return process_clip(env, progress, *clip, fps, mode, cutoff, low, high, gain);
}

extern "C" JNIEXPORT void JNICALL
Java_com_temporalfpt_app_NativeFilter_copyFrame(JNIEnv* env, jclass, jlong handle, jint index, jintArray dest) {
    auto* clip = (Clip*)handle;
    if (clip == nullptr || clip->out == nullptr || dest == nullptr) return;
    if (index < 0 || index >= clip->out_frames) return;
    if (env->GetArrayLength(dest) < clip->w * clip->h) return;
    jint* data = env->GetIntArrayElements(dest, nullptr);
    if (data == nullptr) return;
    std::memcpy(data, clip->out + (size_t)index * clip->w * clip->h, (size_t)clip->w * clip->h * sizeof(uint32_t));
    env->ReleaseIntArrayElements(dest, data, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_temporalfpt_app_NativeFilter_destroy(JNIEnv*, jclass, jlong handle) {
    auto* clip = (Clip*)handle;
    if (clip == nullptr) return;
    std::free(clip->samples);
    std::free(clip->out);
    std::free(clip->mags);
    delete clip;
}

#else

int main() {
    int code = temporal_self_test();
    if (code != 0) {
        std::fprintf(stderr, "fail %d\n", code);
        return 1;
    }
    std::printf("ok\n");
    return 0;
}

#endif
