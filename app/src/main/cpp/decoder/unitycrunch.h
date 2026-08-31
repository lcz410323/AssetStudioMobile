#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

bool unity_crunch_unpack_level(const uint8_t* data, uint32_t data_size, uint32_t level_index, void** ret, uint32_t* ret_size);

#ifdef __cplusplus
}
#endif
