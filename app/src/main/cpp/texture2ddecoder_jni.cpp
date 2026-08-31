#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <cstdlib>

extern "C" {
int decode_bc1(const uint8_t* data, const long w, const long h, uint32_t* image);
int decode_bc3(const uint8_t* data, const long w, const long h, uint32_t* image);
int decode_bc4(const uint8_t* data, uint32_t m_width, uint32_t m_height, uint32_t* image);
int decode_bc5(const uint8_t* data, uint32_t m_width, uint32_t m_height, uint32_t* image);
int decode_bc6(const uint8_t* data, uint32_t m_width, uint32_t m_height, uint32_t* image);
int decode_bc7(const uint8_t* data, uint32_t m_width, uint32_t m_height, uint32_t* image);
int decode_etc1(const uint8_t *, const long, const long, uint32_t *);
int decode_etc2(const uint8_t *, const long, const long, uint32_t *);
int decode_etc2a1(const uint8_t *, const long, const long, uint32_t *);
int decode_etc2a8(const uint8_t *, const long, const long, uint32_t *);
int decode_eacr(const uint8_t *, const long, const long, uint32_t *);
int decode_eacr_signed(const uint8_t *, const long, const long, uint32_t *);
int decode_eacrg(const uint8_t *, const long, const long, uint32_t *);
int decode_eacrg_signed(const uint8_t *, const long, const long, uint32_t *);
int decode_astc(const uint8_t *, const long, const long, const int, const int, uint32_t *);
int decode_pvrtc(const uint8_t *, const long, const long, uint32_t *, const int);
int decode_atc_rgb4(const uint8_t* data, uint32_t m_width, uint32_t m_height, uint32_t* image);
int decode_atc_rgba8(const uint8_t* data, uint32_t m_width, uint32_t m_height, uint32_t* image);
bool crunch_unpack_level(const uint8_t* data, uint32_t data_size, uint32_t level_index, void** ret, uint32_t* ret_size);
bool unity_crunch_unpack_level(const uint8_t* data, uint32_t data_size, uint32_t level_index, void** ret, uint32_t* ret_size);
}

#define JNI_FUNC(name) Java_com_assetstudio_mobile_core_texture_NativeDecoder_##name

static inline uint8_t* getBytes(JNIEnv* env, jbyteArray arr) {
    return (uint8_t*) env->GetByteArrayElements(arr, nullptr);
}

#define DECODE_JNI(name, call)                                                                       \
extern "C" JNIEXPORT void JNICALL JNI_FUNC(name)(JNIEnv* env, jclass, jbyteArray jdata, jint w, jint h, jintArray jimage) { \
    if (jdata == nullptr || jimage == nullptr) return;                                               \
    uint8_t* data = getBytes(env, jdata);                                                            \
    uint32_t* image = (uint32_t*) env->GetIntArrayElements(jimage, nullptr);                          \
    call(data, w, h, image);                                                                          \
    env->ReleaseByteArrayElements(jdata, (jbyte*) data, JNI_ABORT);                                  \
    env->ReleaseIntArrayElements(jimage, (jint*) image, 0);                                           \
}

DECODE_JNI(decodeBC1, decode_bc1)
DECODE_JNI(decodeBC3, decode_bc3)
DECODE_JNI(decodeBC4, decode_bc4)
DECODE_JNI(decodeBC5, decode_bc5)
DECODE_JNI(decodeBC6, decode_bc6)
DECODE_JNI(decodeBC7, decode_bc7)
DECODE_JNI(decodeETC1, decode_etc1)
DECODE_JNI(decodeETC2, decode_etc2)
DECODE_JNI(decodeETC2A1, decode_etc2a1)
DECODE_JNI(decodeETC2A8, decode_etc2a8)
DECODE_JNI(decodeEACR, decode_eacr)
DECODE_JNI(decodeEACRSigned, decode_eacr_signed)
DECODE_JNI(decodeEACRG, decode_eacrg)
DECODE_JNI(decodeEACRGSigned, decode_eacrg_signed)
DECODE_JNI(decodeATCRGB4, decode_atc_rgb4)
DECODE_JNI(decodeATCRGBA8, decode_atc_rgba8)

extern "C" JNIEXPORT void JNICALL JNI_FUNC(decodeASTC)(JNIEnv* env, jclass, jbyteArray jdata, jint w, jint h, jint bw, jint bh, jintArray jimage) {
    if (jdata == nullptr || jimage == nullptr) return;
    uint8_t* data = getBytes(env, jdata);
    uint32_t* image = (uint32_t*) env->GetIntArrayElements(jimage, nullptr);
    decode_astc(data, w, h, bw, bh, image);
    env->ReleaseByteArrayElements(jdata, (jbyte*) data, JNI_ABORT);
    env->ReleaseIntArrayElements(jimage, (jint*) image, 0);
}

extern "C" JNIEXPORT void JNICALL JNI_FUNC(decodePVRTC)(JNIEnv* env, jclass, jbyteArray jdata, jint w, jint h, jboolean is2bpp, jintArray jimage) {
    if (jdata == nullptr || jimage == nullptr) return;
    uint8_t* data = getBytes(env, jdata);
    uint32_t* image = (uint32_t*) env->GetIntArrayElements(jimage, nullptr);
    decode_pvrtc(data, w, h, image, is2bpp ? 1 : 0);
    env->ReleaseByteArrayElements(jdata, (jbyte*) data, JNI_ABORT);
    env->ReleaseIntArrayElements(jimage, (jint*) image, 0);
}

#define CRUNCH_JNI(name, fn)                                                                          \
extern "C" JNIEXPORT jbyteArray JNICALL JNI_FUNC(name)(JNIEnv* env, jclass, jbyteArray jdata, jint level) { \
    if (jdata == nullptr) return nullptr;                                                             \
    uint8_t* data = getBytes(env, jdata);                                                             \
    jsize dataSize = env->GetArrayLength(jdata);                                                      \
    void* ret = nullptr;                                                                              \
    uint32_t retSize = 0;                                                                             \
    bool ok = fn(data, (uint32_t) dataSize, (uint32_t) level, &ret, &retSize);                        \
    env->ReleaseByteArrayElements(jdata, (jbyte*) data, JNI_ABORT);                                  \
    if (!ok || ret == nullptr || retSize == 0) {                                                      \
        if (ret != nullptr) delete[] (uint8_t*) ret;                                                  \
        return nullptr;                                                                               \
    }                                                                                                 \
    jbyteArray result = env->NewByteArray((jsize) retSize);                                          \
    if (result == nullptr) {                                                                          \
        delete[] (uint8_t*) ret;                                                                      \
        return nullptr;                                                                               \
    }                                                                                                 \
    env->SetByteArrayRegion(result, 0, (jsize) retSize, (jbyte*) ret);                                \
    delete[] (uint8_t*) ret;                                                                          \
    return result;                                                                                    \
}

CRUNCH_JNI(crunchUnpackLevel, crunch_unpack_level)
CRUNCH_JNI(unityCrunchUnpackLevel, unity_crunch_unpack_level)

extern "C" JNIEXPORT jint JNICALL JNI_FUNC(nativeInit)(JNIEnv*, jclass) {
    return 1;
}
