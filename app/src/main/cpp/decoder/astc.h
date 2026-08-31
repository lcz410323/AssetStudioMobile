#ifndef ASTC_H
#define ASTC_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

int decode_astc(const uint8_t *, const long, const long, const int, const int, uint32_t *);

#ifdef __cplusplus
}
#endif

#endif /* end of include guard: ASTC_H */
