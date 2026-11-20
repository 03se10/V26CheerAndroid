package com.example.v26cheer;

import java.util.Arrays;
import java.util.Random;

/**
 * 구장 울림(컨볼루션 리버브). 기기/안드로이드 효과 지원 여부와 상관없이 소리를 직접 가공합니다.
 *
 * 컴프야용 PC 앱(V26Cheer.exe)에 들어 있는 stadium_ir.wav를 분석해서 얻은
 * 구장의 울림 특성(초기 반사 시점·방향, 잔향 감쇠 곡선, 음색)을 바탕으로
 * 같은 느낌의 임펄스 응답을 코드로 새로 만들어 사용합니다.
 *
 * 구조: 균등 분할 FFT 컨볼루션(overlap-save), 블록 1024 프레임, 고정 지연 1블록(약 21ms).
 */
public final class StadiumDsp {

    static final int B = 1024;
    static final int N2 = 2 * B;
    static final int BINS = B + 1;
    private static final int RING = 4 * B;
    private static final int RMASK = RING - 1;

    private static final float[] COS = new float[N2 / 2];
    private static final float[] SIN = new float[N2 / 2];
    private static final int[] REV = new int[N2];

    static {
        for (int i = 0; i < N2 / 2; i++) {
            COS[i] = (float) Math.cos(2.0 * Math.PI * i / N2);
            SIN[i] = (float) Math.sin(2.0 * Math.PI * i / N2);
        }
        int bits = Integer.numberOfTrailingZeros(N2);
        for (int i = 0; i < N2; i++) REV[i] = Integer.reverse(i) >>> (32 - bits);
    }

    private final int parts;
    private final float[][] hRe = new float[2][];
    private final float[][] hIm = new float[2][];
    private final float[][] fRe = new float[2][];
    private final float[][] fIm = new float[2][];
    private int fPos = 0;
    private final float[][] win = new float[2][N2];
    private final float[] re = new float[N2];
    private final float[] im = new float[N2];
    private final float[] accRe = new float[BINS];
    private final float[] accIm = new float[BINS];

    private final float[][] inFifo = new float[2][B];
    private int inCount = 0;
    private final float[][] ring = new float[2][RING];
    private int rd = 0;
    private int wr = B;   // 처음 B개는 0으로 채워 고정 지연을 만든다

    private final float wet;
    private final float dry;

    /** wetPct: 0~1 (울림 세기). */
    public StadiumDsp(int sampleRate, float wetPct) {
        this(makeIr(sampleRate), 0.9f * wetPct, 1.0f - 0.25f * wetPct);
    }

    StadiumDsp(float[][] ir, float wet, float dry) {
        this.wet = wet;
        this.dry = dry;
        int len = ir[0].length;
        parts = (len + B - 1) / B;
        for (int c = 0; c < 2; c++) {
            hRe[c] = new float[parts * BINS];
            hIm[c] = new float[parts * BINS];
            fRe[c] = new float[parts * BINS];
            fIm[c] = new float[parts * BINS];
            for (int p = 0; p < parts; p++) {
                Arrays.fill(re, 0f);
                Arrays.fill(im, 0f);
                for (int i = 0; i < B; i++) {
                    int idx = p * B + i;
                    if (idx < len) re[i] = ir[c][idx];
                }
                fft(re, im, false);
                System.arraycopy(re, 0, hRe[c], p * BINS, BINS);
                System.arraycopy(im, 0, hIm[c], p * BINS, BINS);
            }
        }
    }

    /** 입력 n프레임을 제자리에서 처리한다 (입력 n개 → 출력 n개, 약 1블록 지연). */
    public void process(float[] l, float[] r, int n) {
        for (int i = 0; i < n; i++) {
            inFifo[0][inCount] = l[i];
            inFifo[1][inCount] = r[i];
            if (++inCount == B) {
                processBlock();
                inCount = 0;
            }
            l[i] = ring[0][rd & RMASK];
            r[i] = ring[1][rd & RMASK];
            rd++;
        }
    }

    public static int latencyFrames() { return B; }

    private void processBlock() {
        fPos = (fPos - 1 + parts) % parts;
        for (int c = 0; c < 2; c++) {
            float[] w = win[c];
            System.arraycopy(w, B, w, 0, B);
            System.arraycopy(inFifo[c], 0, w, B, B);
            System.arraycopy(w, 0, re, 0, N2);
            Arrays.fill(im, 0f);
            fft(re, im, false);
            System.arraycopy(re, 0, fRe[c], fPos * BINS, BINS);
            System.arraycopy(im, 0, fIm[c], fPos * BINS, BINS);

            Arrays.fill(accRe, 0f);
            Arrays.fill(accIm, 0f);
            float[] fr = fRe[c], fi = fIm[c], hr = hRe[c], hi = hIm[c];
            for (int p = 0; p < parts; p++) {
                int fb = ((fPos + p) % parts) * BINS;
                int hb = p * BINS;
                for (int k = 0; k < BINS; k++) {
                    float xr = fr[fb + k], xi = fi[fb + k];
                    float yr = hr[hb + k], yi = hi[hb + k];
                    accRe[k] += xr * yr - xi * yi;
                    accIm[k] += xr * yi + xi * yr;
                }
            }
            re[0] = accRe[0]; im[0] = 0f;
            re[B] = accRe[B]; im[B] = 0f;
            for (int k = 1; k < B; k++) {
                re[k] = accRe[k]; im[k] = accIm[k];
                re[N2 - k] = accRe[k]; im[N2 - k] = -accIm[k];
            }
            fft(re, im, true);
            for (int i = 0; i < B; i++) {
                ring[c][(wr + i) & RMASK] = dry * inFifo[c][i] + wet * re[B + i];
            }
        }
        wr += B;
    }

    static void fft(float[] a, float[] b, boolean inv) {
        for (int i = 0; i < N2; i++) {
            int j = REV[i];
            if (j > i) {
                float t = a[i]; a[i] = a[j]; a[j] = t;
                t = b[i]; b[i] = b[j]; b[j] = t;
            }
        }
        for (int len = 2; len <= N2; len <<= 1) {
            int half = len >> 1;
            int step = N2 / len;
            for (int i = 0; i < N2; i += len) {
                for (int j = 0; j < half; j++) {
                    float wr = COS[j * step];
                    float wi = inv ? SIN[j * step] : -SIN[j * step];
                    int u = i + j, v = u + half;
                    float tr = a[v] * wr - b[v] * wi;
                    float ti = a[v] * wi + b[v] * wr;
                    a[v] = a[u] - tr; b[v] = b[u] - ti;
                    a[u] += tr; b[u] += ti;
                }
            }
        }
        if (inv) {
            float s = 1f / N2;
            for (int i = 0; i < N2; i++) { a[i] *= s; b[i] *= s; }
        }
    }

    // ───────────────── 임펄스 응답 합성 ─────────────────

    // 분석으로 얻은 구장 울림 특성
    private static final double[] ENV_MS = {0, 35, 50, 70, 125, 175, 325, 375, 425, 475, 575, 675, 775, 875, 975, 1025, 1075, 1125, 1175, 1200};
    private static final double[] ENV_DB = {-80, -80, -24, -13, -11, -9, -8.5, -9.1, -9.9, -12.3, -12.5, -14.0, -17.2, -19.1, -20.4, -22.0, -26.4, -33.4, -47.9, -70};
    private static final double DIFFUSE_OFFSET_DB = -25.5;
    private static final double ER_SCALE = 1.0;

    // 초기 반사: 시각(ms), 왼쪽/오른쪽 세기 (왼쪽 벽 → 오른쪽 벽으로 옮겨 가는 패턴)
    private static final double[] ER_MS = {45.1, 53.8, 65.5, 74.3, 79.4, 86.1, 96.4, 103.1, 113.5, 120.2};
    private static final double[] ER_L = {0.497, 0.661, 0.661, 0.830, 0.098, 0.340, 0.112, 0.229, 0.102, 0.086};
    private static final double[] ER_R = {0.106, 0.140, 0.144, 0.181, 0.393, 0.806, 0.442, 0.449, 0.237, 0.245};

    private static double envDb(double ms) {
        if (ms <= ENV_MS[0]) return ENV_DB[0];
        for (int i = 1; i < ENV_MS.length; i++) {
            if (ms <= ENV_MS[i]) {
                double f = (ms - ENV_MS[i - 1]) / (ENV_MS[i] - ENV_MS[i - 1]);
                return ENV_DB[i - 1] + f * (ENV_DB[i] - ENV_DB[i - 1]);
            }
        }
        return ENV_DB[ENV_DB.length - 1];
    }

    static float[][] makeIr(int sr) {
        int len = (int) (1.2 * sr);
        float[][] h = new float[2][len];
        Random rnd = new Random(20260611L);

        float[][] n = new float[2][len];
        for (int i = 0; i < len; i++) {
            float a = (float) rnd.nextGaussian();
            float b = (float) rnd.nextGaussian();
            n[0][i] = a;
            n[1][i] = 0.4f * a + 0.9165f * b;   // 좌우 상관도 약 0.4
        }
        // 음색: 옥타브마다 에너지가 비슷한 핑크 노이즈 + 110Hz 이하/5kHz 이상을 줄임
        double lpA = 1.0 - Math.exp(-2.0 * Math.PI * 5000.0 / sr);
        double hpA = Math.exp(-2.0 * Math.PI * 110.0 / sr);
        for (int c = 0; c < 2; c++) {
            double b0 = 0, b1 = 0, b2 = 0;
            double lp = 0, lp2 = 0, hpY = 0, lpPrev = 0;
            double sumSq = 0;
            for (int i = 0; i < len; i++) {
                double w = n[c][i];
                b0 = 0.99765 * b0 + w * 0.0990460;
                b1 = 0.96300 * b1 + w * 0.2965164;
                b2 = 0.57000 * b2 + w * 1.0526913;
                double pink = b0 + b1 + b2 + w * 0.1848;
                lp += lpA * (pink - lp);
                lp2 += lpA * (lp - lp2);
                hpY = hpA * (hpY + lp2 - lpPrev);
                lpPrev = lp2;
                n[c][i] = (float) hpY;
                sumSq += hpY * hpY;
            }
            float norm = (float) (1.0 / Math.sqrt(sumSq / len));   // 평균 세기를 1로
            for (int i = 0; i < len; i++) {
                double ms = i * 1000.0 / sr;
                double amp = Math.pow(10.0, (envDb(ms) + DIFFUSE_OFFSET_DB) / 20.0);
                h[c][i] = (float) (n[c][i] * norm * amp);
            }
        }
        // 초기 반사 (약 0.15ms로 짧게 울리는 부드러운 펄스: 벽에 부딪혀 둔해진 소리)
        double tau = sr * 0.00015;
        int span = (int) (sr * 0.001);
        for (int k = 0; k < ER_MS.length; k++) {
            int idx = (int) Math.round(ER_MS[k] * sr / 1000.0);
            for (int d = 0; d < span; d++) {
                int p = idx + d;
                if (p >= len) break;
                double w = Math.exp(-d / tau);
                h[0][p] += (float) (ER_SCALE * ER_L[k] * w);
                h[1][p] += (float) (ER_SCALE * ER_R[k] * w);
            }
        }
        // 전체 에너지를 1로 정규화
        double e = 0;
        for (int c = 0; c < 2; c++) for (int i = 0; i < len; i++) e += (double) h[c][i] * h[c][i];
        float g = (float) (1.0 / Math.sqrt(e / 2.0));
        for (int c = 0; c < 2; c++) for (int i = 0; i < len; i++) h[c][i] *= g;
        return h;
    }
}
