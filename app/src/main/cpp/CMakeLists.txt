# CMakeLists.txt for NDI JNI Wrapper
#
# IMPORTANT: This builds a PURE C wrapper to avoid C++ ABI compatibility issues
# with the NDI SDK on Android.
#
# Prerequisites:
# 1. Download NDI SDK from https://ndi.video/
# 2. Place libndi.so in app/src/main/jniLibs/<ABI>/
# 3. Place NDI headers in app/src/main/cpp/include/

cmake_minimum_required(VERSION 3.22.1)

project("ndi_wrapper" LANGUAGES C)

# Force C language only (no C++)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Disable C++ entirely to avoid any ABI issues
set(CMAKE_CXX_COMPILER_WORKS FALSE)

# Enable warnings
add_compile_options(-Wall -Wextra)

# ==============================================================================
# NDI SDK Configuration
# ==============================================================================

set(NDI_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/include)
set(NDI_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a)

if(NOT EXISTS "${NDI_INCLUDE_DIR}/Processing.NDI.Lib.h")
    message(FATAL_ERROR "NDI SDK headers not found at ${NDI_INCLUDE_DIR}")
endif()

if(NOT EXISTS "${NDI_LIB_DIR}/libndi.so")
    message(FATAL_ERROR "libndi.so not found at ${NDI_LIB_DIR}/libndi.so")
endif()

# Import NDI Library (prebuilt)
add_library(ndi SHARED IMPORTED)
set_target_properties(ndi PROPERTIES
    IMPORTED_LOCATION "${NDI_LIB_DIR}/libndi.so"
)

# ==============================================================================
# JNI Wrapper Library (Pure C)
# ==============================================================================

add_library(ndi_wrapper SHARED
    ndi_wrapper.c
)

# Include directories
target_include_directories(ndi_wrapper PRIVATE
    ${NDI_INCLUDE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link libraries
target_link_libraries(ndi_wrapper
    ndi
    android
    log
)
