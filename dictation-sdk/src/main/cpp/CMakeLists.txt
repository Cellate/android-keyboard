cmake_minimum_required(VERSION 3.22.1)

project("whisper_jni")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimization flags for modern ARMv8-A architecture (64-bit Android)
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -flto -DNDEBUG -fPIC -pthread")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -flto -DNDEBUG -fPIC -pthread")
    # Enable ARM NEON, FP16, and dotprod extensions where supported
    add_compile_options(-march=armv8-a+fp16+dotprod)
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -flto -DNDEBUG -fPIC -pthread -mfpu=neon")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -flto -DNDEBUG -fPIC -pthread -mfpu=neon")
endif()

# JNI bridge shared library
add_library(
    whisper_jni
    SHARED
    whisper_jni.cpp
)

target_include_directories(
    whisper_jni
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Android system libraries required by the JNI bridge
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    whisper_jni
    PRIVATE
    ${log-lib}
    ${android-lib}
)
