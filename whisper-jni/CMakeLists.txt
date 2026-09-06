cmake_minimum_required(VERSION 3.22)
project(whisperjni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Our custom FindOpenCL (static dlopen shim) must win over CMake's builtin.
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")

option(WH_ENABLE_HEXAGON
    "Build the experimental ggml-hexagon NPU backend (requires the Qualcomm Hexagon SDK; set HEXAGON_SDK_ROOT env var)"
    OFF)

# ---------------------------------------------------------------------------
# arm64-v8a uses a prebuilt libwhisperjni.so (contains the Hexagon NPU backend,
# built with the Hexagon SDK — see hexagon-npu-libs/HEXAGON_INTEGRATION.md).
# Building our own arm64 lib here would shadow the NPU one at packaging time,
# so for arm64 we only copy the prebuilt libraries into the output directory.
# ---------------------------------------------------------------------------
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(WH_PREBUILT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/jniLibs/arm64-v8a")
    if(EXISTS "${WH_PREBUILT_DIR}/libwhisperjni.so")
        message(STATUS "whisperjni: packaging prebuilt Hexagon NPU libraries from ${WH_PREBUILT_DIR}")
        file(GLOB WH_PREBUILT_SOS "${WH_PREBUILT_DIR}/*.so")
        set(WH_PREBUILT_OUTPUTS "")
        foreach(SO ${WH_PREBUILT_SOS})
            cmake_path(GET SO FILENAME SO_NAME)
            add_custom_command(
                OUTPUT "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/${SO_NAME}"
                COMMAND ${CMAKE_COMMAND} -E copy_if_different
                        "${SO}" "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/${SO_NAME}"
                DEPENDS "${SO}"
                COMMENT "Packaging prebuilt ${SO_NAME}")
            list(APPEND WH_PREBUILT_OUTPUTS "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/${SO_NAME}")
        endforeach()
        add_custom_target(whisperjni_prebuilt ALL DEPENDS ${WH_PREBUILT_OUTPUTS})
        return()
    endif()
    message(STATUS "whisperjni: no prebuilt arm64 lib found — building from source (no NPU)")
endif()

set(WHISPER_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../whisper-src")
if(NOT EXISTS "${WHISPER_SRC_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "whisper.cpp sources not found at ${WHISPER_SRC_DIR}\n"
        "Run: git clone --depth 1 --branch v1.9.3 https://github.com/ggml-org/whisper.cpp whisper-src")
endif()

# ---- whisper.cpp / ggml options -------------------------------------------------
set(GGML_NATIVE        OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL    OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL        ON  CACHE BOOL "" FORCE)
set(GGML_HEXAGON       OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL       OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS  OFF CACHE BOOL "" FORCE)

if(WH_ENABLE_HEXAGON)
    if(DEFINED ENV{HEXAGON_SDK_ROOT})
        set(HEXAGON_SDK_ROOT "$ENV{HEXAGON_SDK_ROOT}" CACHE PATH "Hexagon SDK root" FORCE)
    endif()
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
endif()

# GGML_OPENCL=ON requires OpenCL headers (vendored) and, at configure time,
# Python 3 (kernel embedding). find_package(OpenCL) resolves to our shim.
add_subdirectory("${WHISPER_SRC_DIR}" whisper-build EXCLUDE_FROM_ALL)

# ---- JNI wrapper ----------------------------------------------------------------
add_library(whisperjni SHARED whisper_jni.cpp)

target_include_directories(whisperjni PRIVATE
    "${WHISPER_SRC_DIR}/include"
    "${WHISPER_SRC_DIR}/ggml/include"
)

find_library(LOG_LIB log)
target_link_libraries(whisperjni PRIVATE whisper ${LOG_LIB})
