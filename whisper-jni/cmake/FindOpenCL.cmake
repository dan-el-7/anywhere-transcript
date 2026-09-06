# Custom FindOpenCL for Android cross-builds (reached before CMake's builtin
# module via CMAKE_MODULE_PATH — see whisper-jni/CMakeLists.txt).
#
# Android apps must NOT DT_NEEDED-link libOpenCL.so (vendor libs are outside the
# app linker namespace), so instead of linking the platform driver we link a
# statically-built dlopen shim (opencl_shim.c) that forwards the OpenCL API to
# the driver at runtime. This module therefore reports a target (opencl_shim)
# rather than a discovered system library.

set(_OPENCL_PROVIDER_ROOT "${CMAKE_CURRENT_LIST_DIR}/..")

if(NOT TARGET opencl_shim)
    add_library(opencl_shim STATIC "${_OPENCL_PROVIDER_ROOT}/opencl_shim/opencl_shim.c")
    target_include_directories(opencl_shim PUBLIC "${_OPENCL_PROVIDER_ROOT}/third_party/OpenCL-Headers")
    find_library(OPENCL_SHIM_LOG_LIB log)
    if(OPENCL_SHIM_LOG_LIB)
        target_link_libraries(opencl_shim PUBLIC ${OPENCL_SHIM_LOG_LIB})
    endif()
endif()

set(OpenCL_FOUND TRUE)
set(OpenCL_LIBRARIES opencl_shim)
set(OpenCL_INCLUDE_DIRS "${_OPENCL_PROVIDER_ROOT}/third_party/OpenCL-Headers")
if(NOT TARGET OpenCL::OpenCL)
    add_library(OpenCL::OpenCL ALIAS opencl_shim)
endif()
