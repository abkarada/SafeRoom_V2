cmake_minimum_required(VERSION 3.10)
project(safe_room_native_encoder)

# Java JNI Support
find_package(JNI REQUIRED)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(${JNI_INCLUDE_DIRS})

# Source files
set(SOURCES
    NativeVideoEncoder.cpp
)

# Create shared library
add_library(native_encoder SHARED ${SOURCES})

# Platform-specific settings
if (WIN32)
    # Windows specific (DLL)
    target_compile_definitions(native_encoder PRIVATE COMPILING_DLL)
elseif (APPLE)
    # macOS specific (dylib)
    target_link_libraries(native_encoder ${JNI_LIBRARIES})
elseif (UNIX)
    # Linux specific (so)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIC")
endif()

message(STATUS "JNI Include Dirs: ${JNI_INCLUDE_DIRS}")
message(STATUS "Build target: native_encoder (Shared Library)")
