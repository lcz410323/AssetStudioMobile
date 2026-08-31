cmake_minimum_required(VERSION 3.22.1)
project("texture2ddecoder")

add_library(texture2ddecoder SHARED
        texture2ddecoder_jni.cpp
        decoder/astc.cpp
        decoder/atc.cpp
        decoder/bcn.cpp
        decoder/etc.cpp
        decoder/pvrtc.cpp
        decoder/crunch.cpp
        decoder/unitycrunch.cpp)

target_include_directories(texture2ddecoder PRIVATE decoder decoder/fp16)

# suppress warnings from vendored decoder sources
# -Wno-c++11-narrowing: etc.cpp 的 {data[3] >> 5, ...} 在 clang 下视为错误（MSVC 允许）
target_compile_options(texture2ddecoder PRIVATE
        -Wno-unused-variable -Wno-unused-parameter -Wno-sign-compare -Wno-shift-negative-value
        -Wno-c++11-narrowing)
